package com.example.audio_app.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.UUID

data class OpenMicBleStatus(
    val recording: Boolean,
    val sdPresent: Boolean?,
    val sdMounted: Boolean?,
    val batteryMv: Int?,
    val wifiIp: String?,
) {
    companion object {
        fun parse(json: String): OpenMicBleStatus {
            val obj = JSONObject(json)
            val sd = obj.optString("sdcard", "").uppercase()
            return OpenMicBleStatus(
                recording = obj.optString("record", "OFF").equals("ON", ignoreCase = true),
                sdPresent = when (sd) {
                    "NONE" -> false
                    "MOUNTED", "UNMOUNTED" -> true
                    else -> null
                },
                sdMounted = sd == "MOUNTED",
                batteryMv = obj.optInt("battery_mv", -1).takeIf { it > 0 },
                wifiIp = obj.optString("wifi", "").trim().ifBlank { null },
            )
        }
    }
}

interface OpenMicBleListener {
    fun onBleScanResult(name: String?, address: String)
    fun onBleConnected()
    fun onBleDisconnected()
    fun onBleStatus(status: OpenMicBleStatus)
    fun onBleError(message: String)
}

class OpenMicBleClient(
    private val context: Context,
    private val listener: OpenMicBleListener,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter
    private val scanner = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private var service: android.bluetooth.BluetoothGattService? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var wifiIpCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private var lastStatus: OpenMicBleStatus? = null
    private var discoveryStarted = false
    private val handler = Handler(Looper.getMainLooper())
    private val discoverFallback = Runnable { discoverServices() }
    private val scanTimeout = Runnable {
        if (isScanning) {
            stopScan()
            listener.onBleError("没有找到 OpenMic 录音卡：请确认录音卡已开机、没有进入自动关机，并且手机蓝牙已打开")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            // device.name needs BLUETOOTH_CONNECT; the advertised name does not.
            val advertisedName = record?.deviceName
                ?: runCatching { result.device.name }.getOrNull()
            val serviceUuids = record?.serviceUuids?.map { it.uuid }
            val isOpenMic = serviceUuids?.contains(SERVICE_UUID) == true ||
                advertisedName?.startsWith("OpenMic", ignoreCase = true) == true
            if (!isOpenMic) return
            stopScan()
            listener.onBleScanResult(advertisedName, result.device.address)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            handler.removeCallbacks(scanTimeout)
            listener.onBleError("蓝牙扫描失败（$errorCode）")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@OpenMicBleClient.gatt = gatt
                discoveryStarted = false
                // Android keeps the default ATT MTU of 23 until asked, which
                // caps reads and notifications at 20 bytes. The status JSON is
                // around 90 bytes, so it would arrive truncated.
                val requested = runCatching { gatt.requestMtu(REQUESTED_MTU) }.getOrDefault(false)
                Log.d(TAG, "requestMtu($REQUESTED_MTU)=$requested")
                // Do not stall if the peripheral ignores the request.
                handler.removeCallbacks(discoverFallback)
                handler.postDelayed(discoverFallback, MTU_TIMEOUT_MS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(discoverFallback)
                closeGatt()
                listener.onBleDisconnected()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "mtu=$mtu status=$status")
            discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "services discovered status=$status")
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onBleError("读取蓝牙服务失败（$status）")
                    return
                }
                val found = gatt.getService(SERVICE_UUID)
                if (found == null) {
                    listener.onBleError("设备不是 OpenMic 录音卡")
                    gatt.disconnect()
                    return
                }
                service = found
                statusCharacteristic = found.getCharacteristic(STATUS_CHARACTERISTIC)
                wifiIpCharacteristic = found.getCharacteristic(WIFI_IP_CHARACTERISTIC)
                commandCharacteristic = found.getCharacteristic(COMMAND_CHARACTERISTIC)
                Log.d(
                    TAG,
                    "chars status=${statusCharacteristic != null} ip=${wifiIpCharacteristic != null} " +
                        "cmd=${commandCharacteristic != null}",
                )

                // Report the link first: the phone is connected whether or not
                // the subscriptions below succeed.
                listener.onBleConnected()
                // FFF1's JSON already carries the Wi-Fi IP, so only it needs
                // notifications; FFF2 is read once instead of subscribed.
                setNotification(statusCharacteristic, true)
                statusCharacteristic?.let { gatt.readCharacteristic(it) }
                wifiIpCharacteristic?.let { gatt.readCharacteristic(it) }
            } catch (error: Exception) {
                fail("读取蓝牙服务失败", error)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristic(characteristic, characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            // The characteristic object does not carry the new value here.
            handleCharacteristic(characteristic, value)
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleCharacteristic(characteristic, characteristic.value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onBleError("蓝牙指令发送失败（$status）")
            }
        }
    }

    val isSupported: Boolean
        get() = adapter != null

    fun startScan() {
        if (!isSupported) {
            listener.onBleError("设备不支持蓝牙")
            return
        }
        if (adapter?.isEnabled != true) {
            listener.onBleError("请先打开手机蓝牙")
            return
        }
        if (isScanning) return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        isScanning = true
        try {
            // Scan without a platform filter and match in code. Some phones only
            // expose the service UUID once the advertisement also carries a name,
            // and a name match makes the device easy to recognise either way.
            scanner?.startScan(null, settings, scanCallback)
            handler.removeCallbacks(scanTimeout)
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (error: SecurityException) {
            isScanning = false
            listener.onBleError("没有蓝牙扫描权限")
        }
    }

    fun stopScan() {
        if (isScanning) {
            isScanning = false
            handler.removeCallbacks(scanTimeout)
            runCatching { scanner?.stopScan(scanCallback) }
        }
    }

    fun connect(address: String) {
        val device = adapter?.getRemoteDevice(address) ?: return
        runCatching {
            gatt = device.connectGatt(context, false, gattCallback)
        }.onFailure {
            listener.onBleError("无法连接蓝牙设备")
        }
    }

    fun requestWifiOn() {
        val characteristic = commandCharacteristic ?: return
        characteristic.value = "WIFI_ON".toByteArray(Charsets.UTF_8)
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        runCatching { gatt?.writeCharacteristic(characteristic) }
    }

    fun disconnect() {
        stopScan()
        closeGatt()
    }

    private fun setNotification(characteristic: BluetoothGattCharacteristic?, enabled: Boolean) {
        val gatt = gatt ?: return
        val characteristic = characteristic ?: return
        runCatching { gatt.setCharacteristicNotification(characteristic, enabled) }
            .onFailure { fail("订阅通知失败", it) }
        characteristic.descriptors
            .firstOrNull { it.uuid == CLIENT_CHARACTERISTIC_CONFIGURATION }
            ?.let { descriptor ->
                descriptor.value = if (enabled) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                runCatching { gatt.writeDescriptor(descriptor) }
                    .onFailure { fail("写入通知配置失败", it) }
                Log.d(TAG, "subscribe ${characteristic.uuid} = $enabled")
            }
    }

    private fun handleCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
        val text = value?.toString(Charsets.UTF_8) ?: return
        Log.d(TAG, "${characteristic.uuid} <- ${value.size} bytes: $text")
        when (characteristic.uuid) {
            STATUS_CHARACTERISTIC -> runCatching {
                OpenMicBleStatus.parse(text)
            }.onSuccess { status ->
                lastStatus = status
                listener.onBleStatus(status)
            }.onFailure {
                listener.onBleError("蓝牙状态数据无法解析")
            }
            // FFF2 carries a bare IPv4 address, not JSON. Merge it into the last
            // status instead of parsing it as a status document.
            WIFI_IP_CHARACTERISTIC -> {
                val ip = text.trim().ifBlank { null }
                val status = lastStatus?.copy(wifiIp = ip)
                    ?: OpenMicBleStatus(false, null, null, null, ip)
                lastStatus = status
                listener.onBleStatus(status)
            }
        }
    }

    private fun discoverServices() {
        if (discoveryStarted) return
        val gatt = gatt ?: return
        discoveryStarted = true
        handler.removeCallbacks(discoverFallback)
        runCatching { gatt.discoverServices() }
            .onFailure { fail("服务发现启动失败", it) }
    }

    /** Exceptions must never escape a BluetoothGattCallback; they kill the
     *  callback thread and drop the link with no trace in the UI. */
    private fun fail(message: String, error: Throwable) {
        Log.e(TAG, "$message: ${error.message}", error)
        listener.onBleError("$message（${error.message ?: error.javaClass.simpleName}）")
    }

    private fun closeGatt() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        service = null
        statusCharacteristic = null
        wifiIpCharacteristic = null
        commandCharacteristic = null
        lastStatus = null
        discoveryStarted = false
    }

    private companion object {
        const val TAG = "OpenMicBle"
        const val SCAN_TIMEOUT_MS = 12_000L
        const val REQUESTED_MTU = 517
        const val MTU_TIMEOUT_MS = 1_500L
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val STATUS_CHARACTERISTIC: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val WIFI_IP_CHARACTERISTIC: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val COMMAND_CHARACTERISTIC: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIGURATION: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
