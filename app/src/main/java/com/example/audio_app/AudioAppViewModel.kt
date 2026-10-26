package com.example.audio_app

import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio_app.data.DownloadState
import com.example.audio_app.data.OpenMicProtocol
import com.example.audio_app.data.OpenMicRepository
import com.example.audio_app.data.Recording
import com.example.audio_app.ble.OpenMicBleClient
import com.example.audio_app.ble.OpenMicBleListener
import com.example.audio_app.ble.OpenMicBleStatus
import com.example.audio_app.discovery.DeviceDiscovery
import com.example.audio_app.playback.AudioPlaybackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class AudioUiState(
    val address: String = "",
    val connectedAddress: String? = null,
    val recordings: List<Recording> = emptyList(),
    val cachedNames: Set<String> = emptySet(),
    val transfers: Map<String, DownloadState> = emptyMap(),
    val isLoading: Boolean = false,
    val isDiscovering: Boolean = false,
    val usingCachedLibrary: Boolean = false,
    val playingName: String? = null,
    val isPlaying: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val bleSupported: Boolean = true,
    val bleState: BleLinkState = BleLinkState.IDLE,
    val bleDeviceName: String? = null,
    val bleStatus: OpenMicBleStatus? = null,
)

enum class BleLinkState {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}

class AudioAppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OpenMicRepository(application)
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, 0)
    private val discovery = DeviceDiscovery(application)
    private val downloadJobs = mutableMapOf<String, Job>()
    private var discoveryProbeInProgress = false
    private var discoveryJob: Job? = null
    private var autoQueryOnWifi = false
    private var bleEnabled = false
    private val pendingDiscoveryCandidates = ArrayDeque<String>()
    private val probedDiscoveryCandidates = mutableSetOf<String>()
    private var bleDevice: String? = null
    private val bleHardwareSupported =
        application.getSystemService(BluetoothManager::class.java)?.adapter != null
    private lateinit var bleClient: OpenMicBleClient

    private val playback = AudioPlaybackController(
        context = application,
        onStateChanged = { name, playing ->
            _state.update { it.copy(playingName = name, isPlaying = playing) }
        },
        onError = { message ->
            _state.update { it.copy(error = message) }
        },
    )

    private val initialAddress = preferences.getString(KEY_ADDRESS, "").orEmpty()
    private val _state = MutableStateFlow(
        AudioUiState(
            address = initialAddress,
            recordings = repository.cachedRecordings(),
            cachedNames = repository.cachedRecordings().mapTo(mutableSetOf()) { it.name },
            usingCachedLibrary = initialAddress.isBlank(),
            bleSupported = bleHardwareSupported,
        ),
    )
    val state: StateFlow<AudioUiState> = _state.asStateFlow()

    init {
        bleClient = OpenMicBleClient(
            context = application,
            listener = object : OpenMicBleListener {
            override fun onBleScanResult(name: String?, address: String) {
                bleDevice = name ?: address
                _state.update {
                    it.copy(
                        bleState = BleLinkState.CONNECTING,
                        bleDeviceName = bleDevice,
                        error = null,
                        notice = "正在通过蓝牙连接录音卡…",
                    )
                }
                bleClient.connect(address)
            }

            override fun onBleConnected() {
                _state.update {
                    it.copy(
                        bleState = BleLinkState.CONNECTED,
                        error = null,
                        notice = "蓝牙待机连接已建立",
                    )
                }
            }

            override fun onBleDisconnected() {
                _state.update {
                    it.copy(
                        bleState = BleLinkState.DISCONNECTED,
                        bleStatus = null,
                        error = null,
                        notice = "蓝牙连接已断开，正在重新查找…",
                    )
                }
                if (bleEnabled) {
                    viewModelScope.launch {
                        delay(BLE_RESCAN_DELAY_MS)
                        if (bleEnabled && _state.value.bleState != BleLinkState.CONNECTED) {
                            bleClient.startScan()
                            _state.update { it.copy(bleState = BleLinkState.SCANNING) }
                        }
                    }
                }
            }

            override fun onBleStatus(status: OpenMicBleStatus) {
                _state.update { it.copy(bleStatus = status) }
                if (autoQueryOnWifi && !status.wifiIp.isNullOrBlank()) {
                    autoQueryOnWifi = false
                    connect("http://${status.wifiIp}")
                }
            }

            override fun onBleError(message: String) {
                _state.update {
                    it.copy(
                        bleState = BleLinkState.DISCONNECTED,
                        error = message,
                        notice = null,
                    )
                }
            }
            },
        )
        if (initialAddress.isNotBlank()) {
            connect(initialAddress)
        }
    }

    fun updateAddress(value: String) {
        _state.update { it.copy(address = value, error = null, notice = null) }
    }

    fun connect(input: String = _state.value.address) {
        val baseUrl = runCatching { OpenMicProtocol.normalizeBaseUrl(input) }
            .getOrElse { error ->
                _state.update { it.copy(error = error.message ?: "地址格式不正确") }
                return
            }
        preferences.edit().putString(KEY_ADDRESS, baseUrl).apply()
        _state.update {
            it.copy(
                address = baseUrl,
                isLoading = true,
                error = null,
                notice = null,
            )
        }
        viewModelScope.launch {
            loadRecordings(baseUrl)
        }
    }

    fun querySdCard() {
        if (_state.value.bleState == BleLinkState.CONNECTED) {
            val ip = _state.value.bleStatus?.wifiIp
            if (!ip.isNullOrBlank()) {
                connect("http://$ip")
                return
            }
            autoQueryOnWifi = true
            bleClient.requestWifiOn()
            _state.update {
                it.copy(
                    error = null,
                    notice = "已通过蓝牙请求打开 Wi-Fi，等待录音卡上线后自动查询…",
                )
            }
            return
        }
        val address = _state.value.connectedAddress ?: _state.value.address
        connect(address)
    }

    fun startBle() {
        if (!bleClient.isSupported) {
            _state.update {
                it.copy(
                    bleSupported = false,
                    error = "当前手机不支持蓝牙",
                )
            }
            return
        }
        bleEnabled = true
        autoQueryOnWifi = false
        _state.update {
            it.copy(
                bleState = BleLinkState.SCANNING,
                error = null,
                notice = "正在查找录音卡的蓝牙广播…",
            )
        }
        bleClient.startScan()
    }

    fun stopBle() {
        bleEnabled = false
        autoQueryOnWifi = false
        bleClient.disconnect()
        _state.update {
            it.copy(
                bleState = BleLinkState.IDLE,
                bleDeviceName = null,
                bleStatus = null,
                notice = "蓝牙待机连接已停止",
            )
        }
    }

    fun blePermissionDenied() {
        _state.update {
            it.copy(error = "需要蓝牙权限才能使用待机连接")
        }
    }

    fun discoverDevice() {
        if (_state.value.isDiscovering) {
            discovery.stop()
            discoveryJob?.cancel()
            discoveryJob = null
            discoveryProbeInProgress = false
            pendingDiscoveryCandidates.clear()
            _state.update {
                it.copy(isDiscovering = false, notice = "已停止查找")
            }
            return
        }
        discoveryProbeInProgress = false
        pendingDiscoveryCandidates.clear()
        probedDiscoveryCandidates.clear()
        _state.update {
            it.copy(
                isDiscovering = true,
                isLoading = false,
                error = null,
                notice = "正在局域网中查找 HTTP 设备…",
            )
        }
        discovery.start(
            onCandidate = { candidate ->
                probeDiscoveredDevice(candidate)
            },
            onFinished = {
                if (_state.value.isDiscovering) {
                    discoveryJob?.cancel()
                    discoveryJob = null
                    discoveryProbeInProgress = false
                    pendingDiscoveryCandidates.clear()
                    _state.update {
                        it.copy(
                            isDiscovering = false,
                            notice = "没有自动发现录音卡，请手动输入它的 IP 或 .local 地址",
                        )
                    }
                }
            },
        )
    }

    fun download(recording: Recording) {
        downloadFile(recording, playWhenDone = false)
    }

    fun downloadAndPlay(recording: Recording) {
        if (repository.isCached(recording.name)) {
            togglePlayback(recording)
        } else {
            downloadFile(recording, playWhenDone = true)
        }
    }

    fun togglePlayback(recording: Recording) {
        if (!repository.isCached(recording.name)) {
            downloadAndPlay(recording)
            return
        }
        if (_state.value.playingName == recording.name) {
            playback.toggle()
        } else {
            playback.play(repository.fileFor(recording.name), recording.name)
        }
    }

    fun deleteCached(recording: Recording) {
        playback.pauseIfPlaying(recording.name)
        if (repository.deleteCached(recording.name)) {
            val cached = repository.cachedRecordings()
            _state.update {
                it.copy(
                    cachedNames = cached.mapTo(mutableSetOf()) { item -> item.name },
                    recordings = if (it.connectedAddress == null) cached else it.recordings,
                    error = null,
                    notice = "已删除本机保存的录音，录音卡上的原文件没有删除",
                )
            }
        } else {
            _state.update { it.copy(error = "无法删除本地录音") }
        }
    }

    fun isCached(name: String): Boolean = repository.isCached(name)

    fun clearFeedback() {
        _state.update { it.copy(error = null, notice = null) }
    }

    override fun onCleared() {
        discovery.stop()
        discoveryJob?.cancel()
        bleClient.disconnect()
        playback.release()
        super.onCleared()
    }

    private suspend fun loadRecordings(baseUrl: String) {
        try {
            val recordings = repository.fetchRecordings(baseUrl)
            val cachedNames = repository.cachedRecordings().mapTo(mutableSetOf()) { it.name }
            _state.update {
                it.copy(
                    address = baseUrl,
                    connectedAddress = baseUrl,
                    recordings = recordings,
                    cachedNames = cachedNames,
                    isLoading = false,
                    isDiscovering = false,
                    usingCachedLibrary = false,
                    error = null,
                    notice = if (recordings.isEmpty()) {
                        "SD 卡查询完成，当前没有 WAV 录音"
                    } else {
                        "SD 卡查询完成，找到 ${recordings.size} 条录音"
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val cached = repository.cachedRecordings()
            _state.update {
                it.copy(
                    address = baseUrl,
                    connectedAddress = null,
                    recordings = cached,
                    cachedNames = cached.mapTo(mutableSetOf()) { item -> item.name },
                    isLoading = false,
                    isDiscovering = false,
                    usingCachedLibrary = true,
                    error = readableNetworkError(error),
                    notice = if (cached.isEmpty()) null else "显示本机已保存的录音",
                )
            }
        }
    }

    private fun probeDiscoveredDevice(candidate: String) {
        if (!_state.value.isDiscovering || !probedDiscoveryCandidates.add(candidate)) return
        pendingDiscoveryCandidates.addLast(candidate)
        probeNextDiscoveredDevice()
    }

    private fun probeNextDiscoveredDevice() {
        if (discoveryProbeInProgress || !_state.value.isDiscovering) return
        val candidate = pendingDiscoveryCandidates.removeFirstOrNull() ?: return
        discoveryProbeInProgress = true
        discoveryJob = viewModelScope.launch {
            try {
                val recordings = runCatching {
                    repository.fetchRecordings(candidate)
                }.getOrNull()
                if (recordings != null && _state.value.isDiscovering) {
                    discovery.stop()
                    val cachedNames = repository.cachedRecordings().mapTo(mutableSetOf()) { it.name }
                    preferences.edit().putString(KEY_ADDRESS, candidate).apply()
                    _state.update {
                        it.copy(
                            address = candidate,
                            connectedAddress = candidate,
                            recordings = recordings,
                            cachedNames = cachedNames,
                            isLoading = false,
                            isDiscovering = false,
                            usingCachedLibrary = false,
                            error = null,
                            notice = "已自动连接录音卡",
                        )
                    }
                }
            } finally {
                discoveryProbeInProgress = false
                discoveryJob = null
            }
            if (_state.value.isDiscovering) {
                probeNextDiscoveredDevice()
            }
        }
    }

    private fun downloadFile(recording: Recording, playWhenDone: Boolean) {
        if (downloadJobs[recording.name]?.isActive == true) return
        val baseUrl = _state.value.connectedAddress
        if (baseUrl == null) {
            _state.update {
                it.copy(error = "请先连接录音卡，再下载新录音")
            }
            return
        }

        _state.update {
            it.copy(
                transfers = it.transfers + (recording.name to DownloadState(0, -1)),
                error = null,
                notice = "正在下载 ${OpenMicProtocol.displayName(recording.name)}",
            )
        }
        var lastReported = 0L
        downloadJobs[recording.name] = viewModelScope.launch {
            try {
                repository.download(baseUrl, recording) { received, total ->
                    if (received - lastReported >= PROGRESS_STEP_BYTES || received == total) {
                        lastReported = received
                        _state.update {
                            it.copy(
                                transfers = it.transfers + (
                                    recording.name to DownloadState(received, total)
                                    ),
                            )
                        }
                    }
                }
                val cachedNames = repository.cachedRecordings().mapTo(mutableSetOf()) { it.name }
                _state.update {
                    it.copy(
                        cachedNames = cachedNames,
                        transfers = it.transfers - recording.name,
                        notice = "已保存到手机",
                        error = null,
                    )
                }
                if (playWhenDone) {
                    playback.play(repository.fileFor(recording.name), recording.name)
                }
            } catch (error: CancellationException) {
                _state.update { it.copy(transfers = it.transfers - recording.name) }
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        transfers = it.transfers - recording.name,
                        error = readableNetworkError(error),
                    )
                }
            } finally {
                downloadJobs.remove(recording.name)
            }
        }
    }

    private fun readableNetworkError(error: Throwable): String {
        return when (error) {
            is UnknownHostException -> "找不到录音卡，请确认地址正确且手机与录音卡在同一 Wi-Fi"
            is ConnectException -> "无法连接录音卡，请检查设备是否开机以及 Wi-Fi 是否已开启"
            is SocketTimeoutException -> "连接超时，录音卡可能正在录音或网络信号较弱"
            is SecurityException -> "系统阻止了局域网访问，请检查应用的网络权限"
            is IOException -> error.message?.takeIf { it.isNotBlank() } ?: "网络传输失败"
            else -> error.message?.takeIf { it.isNotBlank() } ?: "操作失败"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "openmic"
        const val KEY_ADDRESS = "device_address"
        const val PROGRESS_STEP_BYTES = 256L * 1024
        const val BLE_RESCAN_DELAY_MS = 2_000L
    }
}
