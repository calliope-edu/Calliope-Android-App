package cc.calliope.mini.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.calliope.mini.utils.bluetooth.BluetoothUtils
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One GATT link, from `connectGatt` to `close`, with a serialized
 * operation queue.
 *
 * This is the single BLE transport of the app (phase 3). It grew out of
 * the campus bridge session — which already had the right shape (queued
 * ops, per-op timeout, a latch per op so a callback and a timeout can't
 * both complete it) — and absorbed what the bonding / legacy-DFU scripts
 * and the Cardboard UART link used to duplicate: the PHY masks, the
 * service-cache refresh, the settle delays, the API 24 discovery quirk
 * (B6) and waiting for an in-progress bond before discovery.
 *
 * Lifecycle: `connect` → (optional MTU) → (optional bond wait) →
 * (optional delay) → service discovery → [Listener.onConnected]. Any
 * `STATE_DISCONNECTED`, including a failed connect, ends in
 * [Listener.onDisconnected] with the GATT status, after the client is
 * closed. Retry policy belongs to the caller: one instance is one attempt.
 *
 * Threading: listener callbacks arrive on the Bluetooth binder thread;
 * callers marshal to their own thread. Ops may be enqueued from any thread.
 */
class GattConnection(
    private val appCtx: Context,
    private val listener: Listener,
    private val config: Config = Config(),
) {

    /**
     * Per-link behaviour. Defaults match the open-mode editor sessions
     * (campus bridge, Scratch Link); the DFU scripts turn the knobs.
     */
    data class Config(
        /** MTU to negotiate after connect; null keeps the default 23. */
        val requestMtu: Int? = DEFAULT_REQUEST_MTU,
        /** Ask for CONNECTION_PRIORITY_HIGH (low connection interval). */
        val highPriority: Boolean = true,
        /** Prefer LE 1M/2M PHY (API 26+), as the DFU scripts always did. */
        val phyMask: Boolean = false,
        /**
         * Call the hidden `BluetoothGatt.refresh()` before discovery and
         * before close, so a board that switches between application and
         * DFU/pairing services is never served from a stale cache.
         */
        val refreshServiceCache: Boolean = false,
        /** Settle time between connect (or bond) and discovery. */
        val discoveryDelayMs: Long = 0,
        /**
         * If the device is BOND_BONDING when the link comes up (firmware
         * that requests pairing on connect), wait for the bond to complete
         * before discovering; a failed bond is reported via [Listener.onError].
         */
        val waitForBond: Boolean = false,
        /** Delay between STATE_DISCONNECTED and `close()` (legacy DFU hand-off). */
        val closeDelayMs: Long = 0,
        /** Give up (disconnect + onError) if discovery hasn't completed by then; null = no ceiling. */
        val connectTimeoutMs: Long? = null,
        /** Hard ceiling per queued operation. */
        val opTimeoutMs: Long = OP_TIMEOUT_MS,
    )

    interface Listener {
        /** Services are discovered; the link is ready for operations. */
        fun onConnected(deviceName: String?)

        /**
         * The link is down and the client closed. [status] is the GATT
         * status of the STATE_DISCONNECTED callback: 0 for a disconnect we
         * asked for, [GATT_DISCONNECTED_BY_DEVICE] when the board dropped
         * it, 133 for the stack's generic connect failure.
         */
        fun onDisconnected(status: Int)

        fun onNotify(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray)

        /** Something failed while the link is still up; the caller decides whether to disconnect. */
        fun onError(message: String)
    }

    /** A single pending GATT op. `run` returns true if the op was
     *  dispatched (the queue then waits for a callback) or false if the
     *  caller should be unblocked immediately. `done` latches completion so
     *  `complete` fires exactly once even if the GATT callback (binder
     *  thread) and the op timeout (main thread) race. */
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

    @Volatile
    private var gatt: BluetoothGatt? = null
    @Volatile
    private var connectedDevice: BluetoothDevice? = null
    @Volatile
    private var mtuPayloadSize: Int = DEFAULT_MTU_PAYLOAD
    /** Guards against discovering services twice and lets the MTU-timeout
     *  fallback know whether discovery already started. */
    @Volatile
    private var discoveryStarted = false
    @Volatile
    private var ready = false
    private var disconnectWaiter: (() -> Unit)? = null
    private var bondReceiverRegistered = false

    /** Posted after STATE_CONNECTED: if onMtuChanged never arrives (some
     *  stacks silently drop it, or requestMtu fails to dispatch), discover
     *  services anyway so the connect can't hang forever. */
    private val mtuFallback = Runnable {
        if (gatt == null) return@Runnable
        Log.w(TAG, "onMtuChanged not received — discovering services anyway")
        prepareDiscovery()
    }

    private val connectTimeout = Runnable {
        if (gatt == null || ready) return@Runnable
        Log.w(TAG, "connect timed out")
        listener.onError("connect timed out")
        disconnect()
    }

    // ---- Lifecycle --------------------------------------------------------

    val device: BluetoothDevice? get() = connectedDevice

    /** True from `connectGatt` until the client is closed. */
    val isConnected: Boolean get() = gatt != null

    /** True once services are discovered. */
    val isReady: Boolean get() = ready

    /** Bytes per write after MTU negotiation (MTU − 3). */
    val mtuPayload: Int get() = mtuPayloadSize

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (gatt != null) {
            listener.onConnected(deviceNameSafe(device))
            return
        }
        connectedDevice = device
        ready = false
        discoveryStarted = false
        val g = if (config.phyMask && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(
                appCtx, false, callback, BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK,
            )
        } else {
            device.connectGatt(appCtx, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        if (g == null) {
            listener.onError("connectGatt returned null")
            return
        }
        gatt = g
        config.connectTimeoutMs?.let { handler.postDelayed(connectTimeout, it) }
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
     * actually free. With `onClosed == null` there is no wait.
     */
    @SuppressLint("MissingPermission")
    fun disconnect(onClosed: (() -> Unit)?, timeoutMs: Long = DISCONNECT_WAIT_MS) {
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
        // Don't close() here — onConnectionStateChange still needs to fire
        // so the waiter resolves; the callback closes the client itself.
        clearQueue("disconnect")
    }

    // ---- Attribute lookup --------------------------------------------------

    fun hasService(serviceUuid: UUID): Boolean =
        gatt?.getService(serviceUuid) != null

    /** True if [characteristicUuid] exists under [serviceUuid] on the
     *  connected peripheral. */
    fun hasCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): Boolean {
        val g = gatt ?: return false
        return findChar(g, serviceUuid, characteristicUuid) != null
    }

    /** Whether the characteristic advertises Write-Without-Response.
     *  null if not connected / not found. */
    fun canWriteWithoutResponse(serviceUuid: UUID, characteristicUuid: UUID): Boolean? {
        val g = gatt ?: return null
        val ch = findChar(g, serviceUuid, characteristicUuid) ?: return null
        return (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }

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

    /**
     * Write [data], chunked to the negotiated payload size (the CODAL
     * runtime accepts whole writes only up to MTU − 3). Chunks are
     * serialized through the queue; [onResult] fires once, after the last.
     */
    fun write(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        data: ByteArray,
        withResponse: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        val payload = mtuPayloadSize.coerceAtLeast(DEFAULT_MTU_PAYLOAD)
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
                    @Suppress("DEPRECATION")
                    ch.value = chunk
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                },
                complete = { ok, _ -> cb(ok) },
            ),
        )
    }

    fun enableNotify(serviceUuid: UUID, characteristicUuid: UUID, onResult: (Boolean) -> Unit) =
        setNotify(serviceUuid, characteristicUuid, enable = true, onResult)

    fun disableNotify(serviceUuid: UUID, characteristicUuid: UUID, onResult: (Boolean) -> Unit) =
        setNotify(serviceUuid, characteristicUuid, enable = false, onResult)

    private fun setNotify(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        enable: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        enqueue(
            GattOp(
                description = "${if (enable) "enable" else "disable"}Notify $characteristicUuid",
                run = { g ->
                    val ch = findChar(g, serviceUuid, characteristicUuid)
                        ?: return@GattOp false
                    if (!g.setCharacteristicNotification(ch, enable)) return@GattOp false
                    val desc = ch.getDescriptor(BleUuids.CCCD) ?: return@GattOp false
                    @Suppress("DEPRECATION")
                    desc.value = if (enable) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    }
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
        // Hard ceiling per op so a wedged callback doesn't stall the queue.
        // Empirically every CODAL char op finishes in <300 ms. The timeout
        // targets *this* op explicitly so it can never complete a later one.
        currentTimeout = Runnable {
            Log.w(TAG, "op timeout: ${op.description}")
            finishOp(op, false, null)
        }.also { handler.postDelayed(it, config.opTimeoutMs) }
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
    ): BluetoothGattCharacteristic? =
        g.getService(serviceUuid)?.getCharacteristic(characteristicUuid)

    // ---- Connect sequence --------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun afterConnected(g: BluetoothGatt) {
        if (config.highPriority) {
            try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
        }
        if (config.waitForBond && g.device.bondState == BluetoothDevice.BOND_BONDING) {
            // The board asked for pairing on connect; the system dialog is
            // up. Discovering now would race the SMP exchange.
            Log.w(TAG, "waiting for bonding to complete")
            registerBondReceiver()
            return
        }
        negotiateMtu(g)
    }

    @SuppressLint("MissingPermission")
    private fun negotiateMtu(g: BluetoothGatt) {
        val mtu = config.requestMtu
        if (mtu == null) {
            prepareDiscovery()
            return
        }
        val requested = try { g.requestMtu(mtu) } catch (_: Exception) { false }
        if (requested) {
            // Normal path: onMtuChanged triggers discovery. Arm a fallback in
            // case that callback never lands.
            handler.postDelayed(mtuFallback, MTU_FALLBACK_MS)
        } else {
            prepareDiscovery()
        }
    }

    /**
     * Refresh the cache if asked, wait the configured settle time, then
     * discover. On API ≤ 24 the result of `discoverServices()` is only
     * trustworthy when the call itself is made after an extra settle
     * delay (B6: reading it right after connect always saw `false`).
     */
    @SuppressLint("MissingPermission")
    private fun prepareDiscovery() {
        if (discoveryStarted) return
        discoveryStarted = true
        handler.removeCallbacks(mtuFallback)
        val g = gatt ?: return
        if (config.refreshServiceCache) BluetoothUtils.clearServicesCache(g)
        var delay = config.discoveryDelayMs
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) delay += DISCOVERY_DELAY_API24_MS
        val start = Runnable {
            if (gatt !== g) return@Runnable
            if (!g.discoverServices()) {
                Log.e(TAG, "discoverServices failed to start")
                listener.onError("service discovery failed to start")
            }
        }
        if (delay > 0) handler.postDelayed(start, delay) else start.run()
    }

    // ---- Bond wait ---------------------------------------------------------

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val g = gatt ?: return
            @Suppress("DEPRECATION")
            val who: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (who?.address != g.device.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> {
                    Log.i(TAG, "bonded — continuing with discovery")
                    unregisterBondReceiver()
                    negotiateMtu(g)
                }
                BluetoothDevice.BOND_NONE -> {
                    Log.e(TAG, "pairing failed or was cancelled")
                    unregisterBondReceiver()
                    listener.onError("pairing failed or was cancelled")
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        bondReceiverRegistered = true
        appCtx.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        bondReceiverRegistered = false
        try { appCtx.unregisterReceiver(bondReceiver) } catch (_: Exception) {}
    }

    // ---- GATT callback -----------------------------------------------------

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    discoveryStarted = false
                    afterConnected(g)
                }
                BluetoothProfile.STATE_DISCONNECTED -> onLinkDown(g, status)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuPayloadSize = (if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU) - 3
            prepareDiscovery()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.removeCallbacks(connectTimeout)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready = true
                listener.onConnected(deviceNameSafe(g.device))
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
            if (d.uuid == BleUuids.CCCD) finishCurrent(status == BluetoothGatt.GATT_SUCCESS, null)
        }
    }

    /** Tear down after STATE_DISCONNECTED (ours, the board's, or a failed connect). */
    @SuppressLint("MissingPermission")
    private fun onLinkDown(g: BluetoothGatt, status: Int) {
        handler.removeCallbacks(mtuFallback)
        handler.removeCallbacks(connectTimeout)
        unregisterBondReceiver()
        discoveryStarted = false
        ready = false
        mtuPayloadSize = DEFAULT_MTU_PAYLOAD
        clearQueue("disconnected")
        val name = deviceNameSafe(g.device)
        val finish = Runnable {
            if (config.refreshServiceCache) BluetoothUtils.clearServicesCache(g)
            try { g.close() } catch (_: Exception) {}
            if (gatt === g) gatt = null
            val waiter = disconnectWaiter
            disconnectWaiter = null
            waiter?.invoke()
            Log.d(TAG, "disconnected from $name (status=$status)")
            listener.onDisconnected(status)
        }
        if (config.closeDelayMs > 0) handler.postDelayed(finish, config.closeDelayMs) else finish.run()
    }

    @SuppressLint("MissingPermission")
    private fun deviceNameSafe(device: BluetoothDevice?): String? =
        try { device?.name } catch (_: Exception) { null }

    companion object {
        private const val TAG = "GattConnection"

        /** GATT status of STATE_DISCONNECTED when the board closed the link (HCI 0x13). */
        const val GATT_DISCONNECTED_BY_DEVICE = 19
        /** HCI connection timeout on a link drop (same value as GATT_INSUFFICIENT_AUTHORIZATION). */
        const val GATT_CONNECTION_TIMEOUT = 8
        /** The stack's generic connect failure. */
        const val GATT_ERROR_133 = 133

        const val DEFAULT_MTU = 23
        const val DEFAULT_MTU_PAYLOAD = DEFAULT_MTU - 3
        const val DEFAULT_REQUEST_MTU = 247
        const val OP_TIMEOUT_MS = 3000L
        const val MTU_FALLBACK_MS = 1500L
        const val DISCONNECT_WAIT_MS = 1500L
        /** API ≤ 24: settle time before `discoverServices()` returns a trustworthy result (B6). */
        const val DISCOVERY_DELAY_API24_MS = 1600L
    }
}
