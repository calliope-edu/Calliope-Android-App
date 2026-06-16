package cc.calliope.mini.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Open-mode GATT session for the campus native-proxy.
 *
 * Wraps a single `BluetoothGatt` with a serialized op queue. Android's GATT
 * API only allows one outstanding operation per peripheral; every helper
 * (`read`, `write`, `enableNotify`) enqueues a [GattOp] that fires the next
 * one once the previous completes (or its timeout fires).
 *
 * No bonding. The campus widget uses CODAL open-mode firmware, and the
 * proxy path is only ever used for that — pairing UI doesn't belong here.
 */
class BridgeBleSession(
    private val appCtx: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onConnected(deviceName: String?)
        fun onDisconnected(reason: String)
        fun onNotify(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray)
        fun onError(message: String)
    }

    /** A single pending GATT op. `run` returns true if the op was
     *  dispatched successfully (and the queue should wait for a callback)
     *  or false if the caller should be unblocked immediately.
     *
     *  `done` latches completion so the op's `complete` callback fires
     *  exactly once even if the GATT callback (binder thread) and the op
     *  timeout (main thread) race. */
    private class GattOp(
        val description: String,
        val run: (BluetoothGatt) -> Boolean,
        val complete: (Boolean, ByteArray?) -> Unit,
    ) {
        val done = AtomicBoolean(false)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ConcurrentLinkedQueue<GattOp>()
    private val busy = AtomicBoolean(false)
    private var current: GattOp? = null
    private var currentTimeout: Runnable? = null

    private var gatt: BluetoothGatt? = null
    private var mtuPayload: Int = 20

    private val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (gatt != null) {
            listener.onConnected(device.name)
            return
        }
        val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appCtx, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appCtx, false, callback)
        }
        if (g == null) {
            listener.onError("connectGatt returned null")
            return
        }
        gatt = g
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        disconnect(onClosed = null, timeoutMs = 0)
    }

    /**
     * Disconnect and invoke [onClosed] after the GATT either reports
     * STATE_DISCONNECTED via the callback OR after [timeoutMs] elapses —
     * whichever comes first. Use this before handing the device off to
     * another GATT consumer (e.g. FlashingService) so the radio is
     * actually free.
     *
     * Calling with `onClosed == null` skips the wait and behaves like
     * the legacy synchronous disconnect.
     */
    @SuppressLint("MissingPermission")
    fun disconnect(onClosed: (() -> Unit)?, timeoutMs: Long = 1500) {
        val g = gatt
        if (g == null) {
            onClosed?.invoke()
            return
        }
        if (onClosed != null) {
            // Latch the callback so neither the GATT disconnect callback
            // nor the timeout fires it twice.
            val fired = AtomicBoolean(false)
            val safeFire: () -> Unit = {
                if (fired.compareAndSet(false, true)) {
                    handler.post { onClosed() }
                }
            }
            disconnectWaiter = safeFire
            handler.postDelayed({ safeFire() }, timeoutMs)
        }
        try { g.disconnect() } catch (_: Exception) {}
        // NOTE: don't close() synchronously here — onConnectionStateChange
        // still needs to fire so disconnectWaiter resolves. The callback
        // closes the gatt itself.
        clearQueue("disconnect")
    }

    private var disconnectWaiter: (() -> Unit)? = null

    val isConnected: Boolean get() = gatt != null

    // ---- Public op surface ------------------------------------------------

    fun read(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        onResult: (ByteArray?) -> Unit,
    ) {
        enqueue(
            GattOp(
                description = "read $characteristicUuid",
                run = { g ->
                    val ch = findChar(g, serviceUuid, characteristicUuid)
                        ?: return@GattOp false
                    g.readCharacteristic(ch)
                },
                complete = { ok, data -> onResult(if (ok) data else null) },
            ),
        )
    }

    @SuppressLint("MissingPermission")
    fun write(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        data: ByteArray,
        withResponse: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        // Chunk to negotiated payload size — CODAL accepts whole writes only
        // up to MTU-3. For Blocks frames (≤20 bytes typical) one chunk is
        // enough; for larger payloads we just serialize multiple writes.
        val payload = mtuPayload.coerceAtLeast(20)
        if (data.size <= payload) {
            enqueueWrite(serviceUuid, characteristicUuid, data, withResponse, onResult)
            return
        }
        var offset = 0
        var lastOk = true
        while (offset < data.size) {
            val end = (offset + payload).coerceAtMost(data.size)
            val chunk = data.copyOfRange(offset, end)
            val isLast = end >= data.size
            enqueueWrite(serviceUuid, characteristicUuid, chunk, withResponse) { ok ->
                if (!ok) lastOk = false
                if (isLast) onResult(lastOk)
            }
            offset = end
        }
    }

    private fun enqueueWrite(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        chunk: ByteArray,
        withResponse: Boolean,
        cb: (Boolean) -> Unit,
    ) {
        enqueue(
            GattOp(
                description = "write $characteristicUuid (${chunk.size} bytes)",
                run = { g ->
                    val ch = findChar(g, serviceUuid, characteristicUuid)
                        ?: return@GattOp false
                    ch.writeType = if (withResponse) {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }
                    ch.value = chunk
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                },
                complete = { ok, _ -> cb(ok) },
            ),
        )
    }

    fun enableNotify(serviceUuid: UUID, characteristicUuid: UUID, onResult: (Boolean) -> Unit) {
        enqueue(
            GattOp(
                description = "enableNotify $characteristicUuid",
                run = { g ->
                    val ch = findChar(g, serviceUuid, characteristicUuid)
                        ?: return@GattOp false
                    if (!g.setCharacteristicNotification(ch, true)) return@GattOp false
                    val desc = ch.getDescriptor(cccd) ?: return@GattOp false
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                },
                complete = { ok, _ -> onResult(ok) },
            ),
        )
    }

    fun disableNotify(serviceUuid: UUID, characteristicUuid: UUID, onResult: (Boolean) -> Unit) {
        enqueue(
            GattOp(
                description = "disableNotify $characteristicUuid",
                run = { g ->
                    val ch = findChar(g, serviceUuid, characteristicUuid)
                        ?: return@GattOp false
                    if (!g.setCharacteristicNotification(ch, false)) return@GattOp false
                    val desc = ch.getDescriptor(cccd) ?: return@GattOp false
                    desc.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                },
                complete = { ok, _ -> onResult(ok) },
            ),
        )
    }

    // ---- Queue plumbing ----------------------------------------------------

    private fun enqueue(op: GattOp) {
        queue.add(op)
        handler.post { pump() }
    }

    private fun pump() {
        if (busy.get()) return
        val g = gatt
        if (g == null) {
            // Drop everything if we got disconnected while ops queued.
            clearQueue("not connected")
            return
        }
        val op = queue.poll() ?: return
        if (!busy.compareAndSet(false, true)) {
            // Lost the race — re-queue and let the winner drain.
            queue.add(op)
            return
        }
        current = op
        // 3-second hard ceiling per op so a wedged callback doesn't stall
        // the entire bridge. Empirically every CODAL char op finishes in
        // <300ms; partial-flash bursts run at ~50ms/op. The timeout targets
        // *this* op explicitly so it can never complete a later one.
        currentTimeout = Runnable {
            Log.w(TAG, "op timeout: ${op.description}")
            finishOp(op, false, null)
        }.also { handler.postDelayed(it, 3000) }
        val dispatched = try {
            op.run(g)
        } catch (e: Exception) {
            Log.w(TAG, "op threw: ${op.description}: ${e.message}")
            false
        }
        if (!dispatched) finishCurrent(false, null)
    }

    private fun finishCurrent(ok: Boolean, data: ByteArray?) {
        finishOp(current ?: return, ok, data)
    }

    /** Complete [op] at most once. Only advances the queue if [op] is still
     *  the in-flight op — a stale callback for an already-finished op just
     *  no-ops on the latch. */
    private fun finishOp(op: GattOp, ok: Boolean, data: ByteArray?) {
        if (!op.done.compareAndSet(false, true)) return
        if (current === op) {
            current = null
            currentTimeout?.let { handler.removeCallbacks(it) }
            currentTimeout = null
            busy.set(false)
        }
        try { op.complete(ok, data) } catch (e: Exception) {
            Log.w(TAG, "op completion threw: ${e.message}")
        }
        handler.post { pump() }
    }

    private fun clearQueue(reason: String) {
        currentTimeout?.let { handler.removeCallbacks(it) }
        currentTimeout = null
        val cur = current
        current = null
        busy.set(false)
        if (cur != null && cur.done.compareAndSet(false, true)) {
            try { cur.complete(false, null) } catch (_: Exception) {}
        }
        while (true) {
            val op = queue.poll() ?: break
            if (op.done.compareAndSet(false, true)) {
                try { op.complete(false, null) } catch (_: Exception) {}
            }
        }
        Log.d(TAG, "queue cleared ($reason)")
    }

    private fun findChar(
        g: BluetoothGatt,
        serviceUuid: UUID,
        characteristicUuid: UUID,
    ): BluetoothGattCharacteristic? {
        val svc = g.getService(serviceUuid) ?: return null
        return svc.getCharacteristic(characteristicUuid)
    }

    // ---- GATT callback -----------------------------------------------------

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
                    g.requestMtu(247)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val name = try { g.device?.name } catch (_: Exception) { null }
                    try { g.close() } catch (_: Exception) {}
                    gatt = null
                    mtuPayload = 20
                    clearQueue("disconnected")
                    val waiter = disconnectWaiter
                    disconnectWaiter = null
                    waiter?.invoke()
                    listener.onDisconnected("gatt status=$status")
                    Log.d(TAG, "disconnected from $name (status=$status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuPayload = (if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23) - 3
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val name = try { g.device?.name } catch (_: Exception) { null }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onConnected(name)
            } else {
                listener.onError("service discovery failed (status=$status)")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            finishCurrent(status == BluetoothGatt.GATT_SUCCESS, ch.value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            finishCurrent(status == BluetoothGatt.GATT_SUCCESS, null)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val svcUuid = ch.service?.uuid ?: return
            val data = ch.value ?: return
            listener.onNotify(svcUuid, ch.uuid, data)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == cccd) finishCurrent(status == BluetoothGatt.GATT_SUCCESS, null)
        }
    }

    companion object { private const val TAG = "BridgeBleSession" }
}
