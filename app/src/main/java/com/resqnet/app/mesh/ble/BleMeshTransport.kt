package com.resqnet.app.mesh.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.resqnet.app.DemoRole
import com.resqnet.app.ProfileStore
import com.resqnet.app.mesh.MeshTransport
import com.resqnet.app.mesh.TransportEvent
import com.resqnet.app.protocol.MeshFrame
import com.resqnet.app.protocol.TRANSPORT_VERSION
import com.resqnet.app.protocol.ProtocolCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleMeshTransport(
    private val context: Context,
    private val localNodeId: String,
    private val profile: ProfileStore,
) : MeshTransport {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7c36d1a0-8425-4c5f-a9b1-1f3f4b94a201")
        val DATA_UUID: UUID = UUID.fromString("7c36d1a1-8425-4c5f-a9b1-1f3f4b94a201")
        val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TARGET_MTU = 247
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 128)
    override val events: Flow<TransportEvent> = mutableEvents
    private var server: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private val clients = ConcurrentHashMap<String, ClientLink>()
    private val servers = ConcurrentHashMap<String, ServerLink>()
    private val addressToPeer = ConcurrentHashMap<String, String>()
    private val reconnectAfter = ConcurrentHashMap<String, Long>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private var started = false

    override suspend fun start() {
        if (started) return
        check(hasPermissions()) { "Nearby devices permission is missing" }
        check(adapter != null && adapter.isEnabled) { "Bluetooth is disabled" }
        started = true
        runCatching { openServer(); startAdvertising(); startScanning() }
            .onFailure { started = false; emit(TransportEvent.Error(it.message ?: "BLE start failed")) }
    }

    override suspend fun stop() {
        if (!started) return
        started = false
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        clients.values.forEach { it.close() }; clients.clear()
        servers.clear(); addressToPeer.clear(); server?.close(); server = null
    }

    override suspend fun send(peerId: String, frame: MeshFrame): Boolean {
        val bytes = ProtocolCodec.encodeFrame(frame)
        return clients[peerId]?.enqueue(bytes) ?: servers[peerId]?.enqueue(bytes) ?: false
    }

    private fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun openServer() {
        val data = BluetoothGattCharacteristic(DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        data.addDescriptor(BluetoothGattDescriptor(CCC_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(data) }
        characteristic = data
        server = manager.openGattServer(context, serverCallback).also { check(it != null) { "Cannot open GATT server" } }
        check(server!!.addService(service)) { "Cannot add GATT service" }
    }

    private fun startAdvertising() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: error("BLE advertising is not supported")
        val nodePrefix = hexToBytes(localNodeId.take(16))
        val serviceData = ByteBuffer.allocate(10).put(TRANSPORT_VERSION.toByte()).put(profile.demoRole.code).put(nodePrefix).array()
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build()
        // Legacy advertisements have separate 31-byte limits for the primary packet and
        // scan response. A 128-bit UUID plus our node metadata does not fit in one packet.
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(SERVICE_UUID), serviceData)
            .setIncludeDeviceName(false)
            .build()
        advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
    }

    private fun startScanning() {
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback) ?: error("BLE scanning is unavailable")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) = emit(TransportEvent.Error(
            "Advertising failed: ${advertiseFailureName(errorCode)} ($errorCode)"
        ))
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val bytes = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            if (bytes.size < 10 || bytes[0].toInt() != TRANSPORT_VERSION) return
            val role = DemoRole.from(bytes[1])
            if (!linkAllowed(profile.demoRole, role)) return
            val peer = bytes.copyOfRange(2, 10).joinToString("") { "%02x".format(it) }
            if (peer == localNodeId.take(16) || clients.containsKey(peer)) return
            if ((reconnectAfter[peer] ?: 0L) > SystemClock.elapsedRealtime()) return
            emit(TransportEvent.PeerFound(peer))
            if (localNodeId.take(16) < peer) {
                val gatt = result.device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
                clients[peer] = ClientLink(peer, gatt)
            }
        }
        override fun onScanFailed(errorCode: Int) = emit(TransportEvent.Error("Scanning failed ($errorCode)"))
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val link = clients.values.firstOrNull { it.gatt == gatt } ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clients.remove(link.peerId); scheduleReconnect(link.peerId); link.close(); emit(TransportEvent.PeerDisconnected(link.peerId))
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val link = clients.values.firstOrNull { it.gatt == gatt } ?: return
            val value = gatt.getService(SERVICE_UUID)?.getCharacteristic(DATA_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || value == null) { link.close(); return }
            link.characteristic = value; gatt.setCharacteristicNotification(value, true)
            val descriptor = value.getDescriptor(CCC_UUID) ?: return
            if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else writeDescriptorLegacy(gatt, descriptor)
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!gatt.requestMtu(TARGET_MTU)) ready(gatt, 23)
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = ready(gatt, if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            clients.values.firstOrNull { it.gatt == gatt }?.written(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = receiveClient(gatt, value)
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) = receiveClient(gatt, characteristic.value)
    }

    private fun ready(gatt: BluetoothGatt, mtu: Int) {
        clients.values.firstOrNull { it.gatt == gatt }?.let {
            it.mtu = mtu; reconnectAttempts.remove(it.peerId); reconnectAfter.remove(it.peerId)
            emit(TransportEvent.PeerConnected(it.peerId))
        }
    }
    private fun receiveClient(gatt: BluetoothGatt, value: ByteArray) {
        val link = clients.values.firstOrNull { it.gatt == gatt } ?: return
        runCatching { link.assembler.accept(value) }.onSuccess { frame -> if (frame != null) decode(link.peerId, frame) }
            .onFailure { emit(TransportEvent.Error("Bad chunk from ${link.peerId}: ${it.message}")) }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val peer = addressToPeer.remove(device.address) ?: device.address
                servers.remove(peer); emit(TransportEvent.PeerDisconnected(peer))
            }
        }
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { servers.values.firstOrNull { it.device == device }?.mtu = mtu }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, target: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            val key = addressToPeer[device.address] ?: device.address
            val link = servers.computeIfAbsent(key) { ServerLink(key, device) }
            runCatching { link.assembler.accept(value) }.onSuccess { frame ->
                if (frame != null) {
                    if (!addressToPeer.containsKey(device.address)) { addressToPeer[device.address] = key; emit(TransportEvent.PeerConnected(key)) }
                    decode(key, frame)
                }
            }.onFailure { emit(TransportEvent.Error("Bad server chunk: ${it.message}")) }
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val key = addressToPeer[device.address] ?: device.address
            servers[key]?.notified(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private inner class ClientLink(val peerId: String, val gatt: BluetoothGatt) {
        var characteristic: BluetoothGattCharacteristic? = null
        var mtu = 23
        val assembler = ChunkCodec.Assembler()
        private val queue = ArrayDeque<ByteArray>()
        private var writing = false
        fun enqueue(frame: ByteArray): Boolean { queue.addAll(ChunkCodec.split(frame, mtu)); pump(); return true }
        fun written(success: Boolean) { writing = false; if (!success) queue.clear(); pump() }
        private fun pump() {
            if (writing || queue.isEmpty()) return
            val target = characteristic ?: return
            val bytes = queue.removeFirst(); writing = true
            val code = if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(target, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            else writeCharacteristicLegacy(gatt, target, bytes)
            if (code != BluetoothStatusCodes.SUCCESS) { writing = false; queue.clear() }
        }
        fun close() = runCatching { gatt.disconnect(); gatt.close() }.let { Unit }
    }

    private inner class ServerLink(val peerId: String, val device: BluetoothDevice) {
        var mtu = 23
        val assembler = ChunkCodec.Assembler()
        private val queue = ArrayDeque<ByteArray>()
        private var notifying = false
        fun enqueue(frame: ByteArray): Boolean { queue.addAll(ChunkCodec.split(frame, mtu)); pump(); return true }
        fun notified(success: Boolean) { notifying = false; if (!success) queue.clear(); pump() }
        private fun pump() {
            if (notifying || queue.isEmpty()) return
            val target = characteristic ?: return
            val bytes = queue.removeFirst(); notifying = true
            val code = if (Build.VERSION.SDK_INT >= 33) server?.notifyCharacteristicChanged(device, target, false, bytes)
            else notifyLegacy(device, target, bytes)
            if (code != BluetoothStatusCodes.SUCCESS) { notifying = false; queue.clear() }
        }
    }

    private fun decode(peerId: String, bytes: ByteArray) = runCatching { ProtocolCodec.decodeFrame(bytes) }
        .onSuccess { emit(TransportEvent.FrameReceived(peerId, it)) }
        .onFailure { emit(TransportEvent.Error("Malformed frame from ${peerId.take(8)}: ${it.message}")) }
    private fun emit(event: TransportEvent) { mutableEvents.tryEmit(event) }
    private fun advertiseFailureName(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
        else -> "unknown error"
    }
    private fun scheduleReconnect(peerId: String) {
        val attempt = (reconnectAttempts[peerId] ?: 0) + 1
        reconnectAttempts[peerId] = attempt
        val delayMs = (1_000L shl minOf(attempt - 1, 6)).coerceAtMost(60_000L)
        reconnectAfter[peerId] = SystemClock.elapsedRealtime() + delayMs
    }
    @Suppress("DEPRECATION")
    private fun writeDescriptorLegacy(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }
    @Suppress("DEPRECATION")
    private fun writeCharacteristicLegacy(gatt: BluetoothGatt, target: BluetoothGattCharacteristic, bytes: ByteArray): Int {
        target.value = bytes
        return if (gatt.writeCharacteristic(target)) BluetoothGatt.GATT_SUCCESS else -1
    }
    @Suppress("DEPRECATION")
    private fun notifyLegacy(device: BluetoothDevice, target: BluetoothGattCharacteristic, bytes: ByteArray): Int {
        target.value = bytes
        return if (server?.notifyCharacteristicChanged(device, target, false) == true) BluetoothGatt.GATT_SUCCESS else -1
    }
    private fun linkAllowed(local: DemoRole, remote: DemoRole): Boolean {
        if (!profile.demoTopologyEnabled || local == DemoRole.NONE || remote == DemoRole.NONE) return true
        return (local == DemoRole.A && remote == DemoRole.B) || (local == DemoRole.B && remote in setOf(DemoRole.A, DemoRole.C)) ||
            (local == DemoRole.C && remote == DemoRole.B)
    }
    private fun hexToBytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
