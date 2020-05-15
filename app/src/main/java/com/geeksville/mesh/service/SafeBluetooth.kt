package com.geeksville.mesh.service

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import com.geeksville.android.GeeksvilleApplication
import com.geeksville.android.Logging
import com.geeksville.concurrent.CallbackContinuation
import com.geeksville.concurrent.Continuation
import com.geeksville.concurrent.SyncContinuation
import com.geeksville.util.exceptionReporter
import java.io.Closeable
import java.util.*


/// Return a standard BLE 128 bit UUID from the short 16 bit versions
fun longBLEUUID(hexFour: String) = UUID.fromString("0000$hexFour-0000-1000-8000-00805f9b34fb")


/**
 * A helper class to call onChanged when bluetooth is enabled or disabled
 */
class BluetoothStateReceiver(val onChanged: (Boolean) -> Unit) : BroadcastReceiver() {
    val intent = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED) // Can be used for registering

    override fun onReceive(context: Context, intent: Intent) = exceptionReporter {
        if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                // Simulate a disconnection if the user disables bluetooth entirely
                BluetoothAdapter.STATE_OFF -> onChanged(false)
                BluetoothAdapter.STATE_ON -> onChanged(true)
            }
        }
    }
}

/**
 * Uses coroutines to safely access a bluetooth GATT device with a synchronous API
 *
 * The BTLE API on android is dumb.  You can only have one outstanding operation in flight to
 * the device.  If you try to do something when something is pending, the operation just returns
 * false.  You are expected to chain your operations from the results callbacks.
 *
 * This class fixes the API by using coroutines to let you safely do a series of BTLE operations.
 */
class SafeBluetooth(private val context: Context, private val device: BluetoothDevice) :
    Logging, Closeable {

    /// Timeout before we declare a bluetooth operation failed
    var timeoutMsec = 30 * 1000L

    /// Users can access the GATT directly as needed
    var gatt: BluetoothGatt? = null

    var state = BluetoothProfile.STATE_DISCONNECTED
    private var currentWork: BluetoothContinuation? = null
    private val workQueue = mutableListOf<BluetoothContinuation>()

    // Called for reconnection attemps
    private var connectionCallback: ((Result<Unit>) -> Unit)? = null
    private var lostConnectCallback: (() -> Unit)? = null

    /// from characteristic UUIDs to the handler function for notfies
    private val notifyHandlers = mutableMapOf<UUID, (BluetoothGattCharacteristic) -> Unit>()

    /// When we see the BT stack getting disabled/renabled we handle that as a connect/disconnect event
    private val btStateReceiver = BluetoothStateReceiver { enabled ->
        if (!enabled) {
            if (state == BluetoothProfile.STATE_CONNECTED)
                gattCallback.onConnectionStateChange(
                    gatt!!,
                    0,
                    BluetoothProfile.STATE_DISCONNECTED
                )
            else
                debug("We were not connected, so ignoring bluetooth shutdown")
        } else {
            warn("requeue a connect anytime bluetooth is reenabled")
            reconnect()
        }
    }

    /**
     * A BLE status code based error
     */
    class BLEStatusException(val status: Int, msg: String) : BLEException(msg)

    // 0x2902 org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
    private val configurationDescriptorUUID =
        longBLEUUID("2902")

    init {
        context.registerReceiver(
            btStateReceiver,
            btStateReceiver.intent
        )
    }

    /**
     * a schedulable bit of bluetooth work, includes both the closure to call to start the operation
     * and the completion (either async or sync) to call when it completes
     */
    private class BluetoothContinuation(
        val tag: String,
        val completion: com.geeksville.concurrent.Continuation<*>,
        val startWorkFn: () -> Boolean
    ) : Logging {

        /// Start running a queued bit of work, return true for success or false for fatal bluetooth error
        fun startWork(): Boolean {
            debug("Starting work: $tag")
            return startWorkFn()
        }
    }

    /**
     * skanky hack to restart BLE if it says it is hosed
     * https://stackoverflow.com/questions/35103701/ble-android-onconnectionstatechange-not-being-called
     */
    var mHandler: Handler = Handler()

    fun restartBle() {
        GeeksvilleApplication.analytics.track("ble_restart") // record # of times we needed to use this nasty hack
        errormsg("Doing emergency BLE restart")
        val mgr =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adp = mgr.adapter
        if (null != adp) {
            if (adp.isEnabled) {
                adp.disable()
                // TODO: display some kind of UI about restarting BLE
                mHandler.postDelayed(object : Runnable {
                    override fun run() {
                        if (!adp.isEnabled) {
                            adp.enable()
                        } else {
                            mHandler.postDelayed(this, 2500)
                        }
                    }
                }, 2500)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int
        ) = exceptionReporter {
            info("new bluetooth connection state $newState, status $status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    state =
                        newState // we only care about connected/disconnected - not the transitional states

                    // If autoconnect is on and this connect attempt failed, hopefully some future attempt will succeed
                    if (status != BluetoothGatt.GATT_SUCCESS && autoConnect) {
                        errormsg("Connect attempt failed $status, not calling connect completion handler...")
                    } else
                        completeWork(status, Unit)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // cancel any queued ops if we were already connected
                    val oldstate = state
                    state = newState
                    if (oldstate == BluetoothProfile.STATE_CONNECTED) {
                        info("Lost connection - aborting current work")

                        /*
                        Supposedly this reconnect attempt happens automatically
                        "If the connection was established through an auto connect, Android will
                        automatically try to reconnect to the remote device when it gets disconnected
                        until you manually call disconnect() or close(). Once a connection established
                        through direct connect disconnects, no attempt is made to reconnect to the remote device."
                        https://stackoverflow.com/questions/37965337/what-exactly-does-androids-bluetooth-autoconnect-parameter-do?rq=1

                        closeConnection()
                        */
                        failAllWork(BLEException("Lost connection"))

                        // Cancel any notifications - because when the device comes back it might have forgotten about us
                        notifyHandlers.clear()

                        lostConnectCallback?.let {
                            debug("calling lostConnect handler")
                            it.invoke()
                        }

                        // Queue a new connection attempt
                        val cb = connectionCallback
                        if (cb != null) {
                            debug("queuing a reconnection callback")
                            assert(currentWork == null)

                            // note - we don't need an init fn (because that would normally redo the connectGatt call - which we don't need
                            queueWork("reconnect", CallbackContinuation(cb)) { -> true }
                        } else {
                            debug("No connectionCallback registered")
                        }
                    }

                    if (status == 257) { // mystery error code when phone is hung
                        //throw Exception("Mystery bluetooth failure - debug me")
                        restartBle()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // For testing lie and claim failure
            completeWork(status, Unit)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            completeWork(status, characteristic)
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) {
            completeWork(status, Unit)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val reliable = currentReliableWrite
            if (reliable != null)
                if (!characteristic.value.contentEquals(reliable)) {
                    errormsg("A reliable write failed!")
                    gatt.abortReliableWrite();
                    completeWork(42, characteristic) // skanky code to indicate failure
                } else {
                    logAssert(gatt.executeReliableWrite())
                    // After this execute reliable completes - we can continue with normal operations (see onReliableWriteCompleted)
                }
            else // Just a standard write - do the normal flow
                completeWork(status, characteristic)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Alas, passing back an Int mtu isn't working and since I don't really care what MTU
            // the device was willing to let us have I'm just punting and returning Unit
            completeWork(status, Unit)
        }

        /**
         * Callback triggered as a result of a remote characteristic notification.
         *
         * @param gatt GATT client the characteristic is associated with
         * @param characteristic Characteristic that has been updated as a result of a remote
         * notification event.
         */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val handler = notifyHandlers.get(characteristic.uuid)
            if (handler == null)
                warn("Received notification from $characteristic, but no handler registered")
            else {
                exceptionReporter {
                    handler(characteristic)
                }
            }
        }

        /**
         * Callback indicating the result of a descriptor write operation.
         *
         * @param gatt GATT client invoked [BluetoothGatt.writeDescriptor]
         * @param descriptor Descriptor that was writte to the associated remote device.
         * @param status The result of the write operation [BluetoothGatt.GATT_SUCCESS] if the
         * operation succeeds.
         */
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            completeWork(status, descriptor)
        }

        /**
         * Callback reporting the result of a descriptor read operation.
         *
         * @param gatt GATT client invoked [BluetoothGatt.readDescriptor]
         * @param descriptor Descriptor that was read from the associated remote device.
         * @param status [BluetoothGatt.GATT_SUCCESS] if the read operation was completed
         * successfully
         */
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            completeWork(status, descriptor)
        }
    }


    /// If we have work we can do, start doing it.
    private fun startNewWork() {
        logAssert(currentWork == null)

        if (workQueue.isNotEmpty()) {
            val newWork = workQueue.removeAt(0)
            currentWork = newWork
            logAssert(newWork.startWork())
        }
    }

    private fun <T> queueWork(tag: String, cont: Continuation<T>, initFn: () -> Boolean) {
        val btCont =
            BluetoothContinuation(
                tag,
                cont,
                initFn
            )

        synchronized(workQueue) {
            debug("Enqueuing work: ${btCont.tag}")
            workQueue.add(btCont)

            // if we don't have any outstanding operations, run first item in queue
            if (currentWork == null)
                startNewWork()
        }
    }

    /**
     * Called from our big GATT callback, completes the current job and then schedules a new one
     */
    private fun <T : Any> completeWork(status: Int, res: T) {
        exceptionReporter {
            // We might unexpectedly fail inside here, but we don't want to pass that exception back up to the bluetooth GATT layer

            // startup next job in queue before calling the completion handler
            val work =
                synchronized(workQueue) {
                    val w =
                        currentWork
                            ?: throw Exception("currentWork was null") // will throw if null, which is helpful (FIXME - throws in the field)
                    currentWork = null // We are now no longer working on anything

                    startNewWork()
                    w
                }

            debug("work ${work.tag} is completed, resuming status=$status, res=$res")
            if (status != 0)
                work.completion.resumeWithException(
                    BLEStatusException(
                        status,
                        "Bluetooth status=$status while doing ${work.tag}"
                    )
                )
            else
                work.completion.resume(Result.success(res) as Result<Nothing>)
        }
    }

    /**
     * Something went wrong, abort all queued
     */
    private fun failAllWork(ex: Exception) {
        synchronized(workQueue) {
            workQueue.forEach {
                it.completion.resumeWithException(ex)
            }
            workQueue.clear()
            currentWork = null
        }
    }

    /// helper glue to make sync continuations and then wait for the result
    private fun <T> makeSync(wrappedFn: (SyncContinuation<T>) -> Unit): T {
        val cont = SyncContinuation<T>()
        wrappedFn(cont)
        return cont.await(timeoutMsec)
    }

    // Is the gatt trying to repeatedly connect as needed?
    private var autoConnect = false

    // FIXME, pass in true for autoconnect - so we will autoconnect whenever the radio
    // comes in range (even if we made this connect call long ago when we got powered on)
    // see https://stackoverflow.com/questions/40156699/which-correct-flag-of-autoconnect-in-connectgatt-of-ble for
    // more info.
    // Otherwise if you pass in false, it will try to connect now and will timeout and fail in 30 seconds.
    private fun queueConnect(autoConnect: Boolean = false, cont: Continuation<Unit>) {
        this.autoConnect = autoConnect

        // assert(gatt == null) this now might be !null with our new reconnect support
        queueWork("connect", cont) {
            val g =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(
                        context,
                        autoConnect,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    device.connectGatt(context, autoConnect, gattCallback)
                }
            if (g != null)
                gatt = g
            g != null
        }
    }

    /**
     * start a connection attempt.
     *
     * Note: if autoConnect is true, the callback you provide will be kept around _even after the connection is complete.
     * If we ever lose the connection, this class will immediately requque the attempt (after canceling
     * any outstanding queued operations).
     *
     * So you should expect your callback might be called multiple times, each time to reestablish a new connection.
     */
    fun asyncConnect(
        autoConnect: Boolean = false,
        cb: (Result<Unit>) -> Unit,
        lostConnectCb: () -> Unit
    ) {
        logAssert(workQueue.isEmpty())
        logAssert(currentWork == null) // I don't think anything should be able to sneak in front

        lostConnectCallback = lostConnectCb
        connectionCallback = if (autoConnect)
            cb
        else
            null
        queueConnect(autoConnect, CallbackContinuation(cb))
    }

    /// Restart any previous connect attempts
    private fun reconnect() {
        connectionCallback?.let { cb ->
            queueConnect(true, CallbackContinuation(cb))
        }
    }

    fun connect(autoConnect: Boolean = false) = makeSync<Unit> { queueConnect(autoConnect, it) }

    private fun queueReadCharacteristic(
        c: BluetoothGattCharacteristic,
        cont: Continuation<BluetoothGattCharacteristic>
    ) = queueWork("readC ${c.uuid}", cont) { gatt!!.readCharacteristic(c) }

    fun asyncReadCharacteristic(
        c: BluetoothGattCharacteristic,
        cb: (Result<BluetoothGattCharacteristic>) -> Unit
    ) = queueReadCharacteristic(c, CallbackContinuation(cb))

    fun readCharacteristic(c: BluetoothGattCharacteristic): BluetoothGattCharacteristic =
        makeSync { queueReadCharacteristic(c, it) }

    private fun queueDiscoverServices(cont: Continuation<Unit>) {
        queueWork("discover", cont) {
            gatt!!.discoverServices()
        }
    }

    fun asyncDiscoverServices(cb: (Result<Unit>) -> Unit) {
        logAssert(workQueue.isEmpty() && currentWork == null) // I don't think anything should be able to sneak in front
        queueDiscoverServices(CallbackContinuation(cb))
    }

    fun discoverServices() = makeSync<Unit> { queueDiscoverServices(it) }

    private fun queueRequestMtu(
        len: Int,
        cont: Continuation<Unit>
    ) = queueWork("reqMtu", cont) { gatt!!.requestMtu(len) }

    fun asyncRequestMtu(
        len: Int,
        cb: (Result<Unit>) -> Unit
    ) {
        logAssert(workQueue.isEmpty() && currentWork == null) // I don't think anything should be able to sneak in front
        queueRequestMtu(len, CallbackContinuation(cb))
    }

    fun requestMtu(len: Int): Unit = makeSync { queueRequestMtu(len, it) }

    private var currentReliableWrite: ByteArray? = null

    private fun queueWriteCharacteristic(
        c: BluetoothGattCharacteristic,
        cont: Continuation<BluetoothGattCharacteristic>
    ) = queueWork("writeC ${c.uuid}", cont) {
        currentReliableWrite = null
        gatt!!.writeCharacteristic(c)
    }

    fun asyncWriteCharacteristic(
        c: BluetoothGattCharacteristic,
        cb: (Result<BluetoothGattCharacteristic>) -> Unit
    ) = queueWriteCharacteristic(c, CallbackContinuation(cb))

    fun writeCharacteristic(c: BluetoothGattCharacteristic): BluetoothGattCharacteristic =
        makeSync { queueWriteCharacteristic(c, it) }

    /** Like write, but we use the extra reliable flow documented here:
     * https://stackoverflow.com/questions/24485536/what-is-reliable-write-in-ble
     */
    private fun queueWriteReliable(
        c: BluetoothGattCharacteristic,
        cont: Continuation<Unit>
    ) = queueWork("rwriteC ${c.uuid}", cont) {
        logAssert(gatt!!.beginReliableWrite())
        currentReliableWrite = c.value.clone()
        gatt!!.writeCharacteristic(c)
    }

    /* fun asyncWriteReliable(
        c: BluetoothGattCharacteristic,
        cb: (Result<Unit>) -> Unit
    ) = queueWriteCharacteristic(c, CallbackContinuation(cb)) */

    fun writeReliable(c: BluetoothGattCharacteristic): Unit =
        makeSync { queueWriteReliable(c, it) }

    private fun queueWriteDescriptor(
        c: BluetoothGattDescriptor,
        cont: Continuation<BluetoothGattDescriptor>
    ) = queueWork("writeD", cont) { gatt!!.writeDescriptor(c) }

    fun asyncWriteDescriptor(
        c: BluetoothGattDescriptor,
        cb: (Result<BluetoothGattDescriptor>) -> Unit
    ) = queueWriteDescriptor(c, CallbackContinuation(cb))


    /**
     * Close down any existing connection, any existing calls (including async connects will be
     * cancelled and you'll need to recall connect to use this againt
     */
    fun closeConnection() {
        // Set these to null _before_ calling gatt.disconnect(), because we don't want the old lostConnectCallback to get called
        lostConnectCallback = null
        connectionCallback = null

        failAllWork(BLEException("Connection closing"))

        if (gatt != null) {
            info("Closing our GATT connection")
            gatt!!.disconnect()
            gatt!!.close()
            gatt = null
        }
    }

    /**
     * Close and destroy this SafeBluetooth instance.  You'll need to make a new instance before using it again
     */
    override fun close() {
        closeConnection()

        context.unregisterReceiver(btStateReceiver)
    }


    /// asyncronously turn notification on/off for a characteristic
    fun setNotify(
        c: BluetoothGattCharacteristic,
        enable: Boolean,
        onChanged: (BluetoothGattCharacteristic) -> Unit
    ) {
        debug("starting setNotify(${c.uuid}, $enable)")
        notifyHandlers[c.uuid] = onChanged
        // c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt!!.setCharacteristicNotification(c, enable)

        /*
        c is null sometimes
2020-04-13 15:59:38.222 2111-2182/com.geeksville.mesh D/BluetoothGatt: setCharacteristicNotification() - uuid: ed9da18c-a800-4f66-a670-aa7547e34453 enable: true
2020-04-13 15:59:38.225 2111-2182/com.geeksville.mesh E/com.geeksville.util.Exceptions: exceptionReporter Uncaught Exception
    kotlin.KotlinNullPointerException
        at com.geeksville.mesh.service.SafeBluetooth.setNotify(SafeBluetooth.kt:505)
        at com.geeksville.mesh.service.RadioInterfaceService$onConnect$1$1.invoke(RadioInterfaceService.kt:328)
        at com.geeksville.mesh.service.RadioInterfaceService$onConnect$1$1.invoke(RadioInterfaceService.kt:90)
        at com.geeksville.concurrent.CallbackContinuation.resume(SyncContinuation.kt:20)
        at com.geeksville.mesh.service.SafeBluetooth$completeWork$1.invoke(SafeBluetooth.kt:329)
        at com.geeksville.mesh.service.SafeBluetooth$completeWork$1.invoke(SafeBluetooth.kt:33)
        at com.geeksville.util.ExceptionsKt.exceptionReporter(Exceptions.kt:34)
        at com.geeksville.mesh.service.SafeBluetooth.completeWork(SafeBluetooth.kt:312)
        at com.geeksville.mesh.service.SafeBluetooth.access$completeWork(SafeBluetooth.kt:33)
        at com.geeksville.mesh.service.SafeBluetooth$gattCallback$1.onMtuChanged(SafeBluetooth.kt:221)
        at android.bluetooth.BluetoothGatt$1$13.run(BluetoothGatt.java:658)
        at android.bluetooth.BluetoothGatt.runOrQueueCallback(BluetoothGatt.java:780)
        at android.bluetooth.BluetoothGatt.access$200(BluetoothGatt.java:41)
        at android.bluetooth.BluetoothGatt$1.onConfigureMTU(BluetoothGatt.java:653)
        at android.bluetooth.IBluetoothGattCallback$Stub.onTransact(IBluetoothGattCallback.java:330)
        at android.os.Binder.execTransactInternal(Binder.java:1021)
        at android.os.Binder.execTransact(Binder.java:994)
         */
        // per https://stackoverflow.com/questions/27068673/subscribe-to-a-ble-gatt-notification-android
        val descriptor: BluetoothGattDescriptor = c.getDescriptor(configurationDescriptorUUID)
            ?: throw BLEException("Notify descriptor not found for ${c.uuid}") // This can happen on buggy BLE implementations
        descriptor.value =
            if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        asyncWriteDescriptor(descriptor) {
            debug("Notify enable=$enable completed")
        }
    }
}

