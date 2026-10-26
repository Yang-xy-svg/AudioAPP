package com.example.audio_app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio_app.AudioAppViewModel
import com.example.audio_app.AudioUiState
import com.example.audio_app.BleLinkState
import com.example.audio_app.ble.OpenMicBleStatus
import com.example.audio_app.data.DownloadState
import com.example.audio_app.data.OpenMicProtocol
import com.example.audio_app.data.Recording
import com.example.audio_app.data.formatByteCount
import com.example.audio_app.ui.theme.AudioAPPTheme
import androidx.core.content.ContextCompat

@Composable
fun AudioAppScreen(viewModel: AudioAppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }
    val hasBlePermissions = blePermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.startBle()
        } else {
            viewModel.blePermissionDenied()
        }
    }
    AudioAppContent(
        state = state,
        onAddressChange = viewModel::updateAddress,
        onConnect = { viewModel.connect() },
        onQuerySdCard = viewModel::querySdCard,
        onDiscover = viewModel::discoverDevice,
        onStartBle = {
            if (hasBlePermissions) viewModel.startBle() else permissionLauncher.launch(blePermissions)
        },
        onStopBle = viewModel::stopBle,
        onDownload = viewModel::download,
        onDownloadAndPlay = viewModel::downloadAndPlay,
        onTogglePlayback = viewModel::togglePlayback,
        onDeleteCached = viewModel::deleteCached,
        onDismissFeedback = viewModel::clearFeedback,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioAppContent(
    state: AudioUiState,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
    onQuerySdCard: () -> Unit,
    onDiscover: () -> Unit,
    onStartBle: () -> Unit,
    onStopBle: () -> Unit,
    onDownload: (Recording) -> Unit,
    onDownloadAndPlay: (Recording) -> Unit,
    onTogglePlayback: (Recording) -> Unit,
    onDeleteCached: (Recording) -> Unit,
    onDismissFeedback: () -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<Recording?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenMic 录音库", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (state.connectedAddress == null) "手机端播放器" else "已连接",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onQuerySdCard,
                        enabled = !state.isLoading && state.address.isNotBlank(),
                    ) {
                        Text("查询 SD 卡")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ConnectionCard(
                    state = state,
                    onAddressChange = onAddressChange,
                    onConnect = onConnect,
                    onDiscover = onDiscover,
                )
            }

            item {
                BleStandbyCard(
                    state = state,
                    onStartBle = onStartBle,
                    onStopBle = onStopBle,
                )
            }

            if (state.error != null) {
                item {
                    FeedbackBanner(
                        text = state.error,
                        isError = true,
                        onDismiss = onDismissFeedback,
                    )
                }
            } else if (state.notice != null) {
                item {
                    FeedbackBanner(
                        text = state.notice,
                        isError = false,
                        onDismiss = onDismissFeedback,
                    )
                }
            }

            if (state.connectedAddress == null && !state.isLoading) {
                item { ConnectionHelpCard() }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.usingCachedLibrary) "本机录音" else "SD 卡录音",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = "${state.recordings.size} 条 WAV",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.recordings.isEmpty() && !state.isLoading) {
                item {
                    EmptyRecordingsCard(usingCachedLibrary = state.usingCachedLibrary)
                }
            }

            items(
                items = state.recordings,
                key = { it.name },
            ) { recording ->
                RecordingCard(
                    recording = recording,
                    cached = state.cachedNames.contains(recording.name),
                    transfer = state.transfers[recording.name],
                    playingName = state.playingName,
                    isPlaying = state.isPlaying,
                    onDownload = { onDownload(recording) },
                    onDownloadAndPlay = { onDownloadAndPlay(recording) },
                    onTogglePlayback = { onTogglePlayback(recording) },
                    onDelete = { deleteTarget = recording },
                )
            }

            item {
                Text(
                    text = "录音保存在应用私有目录，卸载应用前请注意保留需要的文件。",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除本机录音？") },
            text = { Text("只会删除手机中已下载的副本，录音卡 SD 卡上的文件不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteCached(target)
                        deleteTarget = null
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ConnectionCard(
    state: AudioUiState,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDiscover: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "连接录音卡",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "输入录音卡的 IP 地址或主机名，手机和录音卡需要连接同一个 Wi-Fi。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = state.address,
                onValueChange = onAddressChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("录音卡地址") },
                placeholder = { Text("例如 192.168.1.50 或 mic.local") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        onConnect()
                    },
                ),
                shape = RoundedCornerShape(14.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onConnect()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = state.address.isNotBlank() && !state.isLoading,
                ) {
                    Text(if (state.isLoading) "连接中…" else "连接")
                }
                OutlinedButton(
                    onClick = onDiscover,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isLoading,
                ) {
                    Text(if (state.isDiscovering) "停止查找" else "自动发现")
                }
            }

            if (state.connectedAddress != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "●",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = state.connectedAddress,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BleStandbyCard(
    state: AudioUiState,
    onStartBle: () -> Unit,
    onStopBle: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "蓝牙待机连接",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = bleStateText(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.bleState == BleLinkState.SCANNING ||
                    state.bleState == BleLinkState.CONNECTING
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            if (state.bleState == BleLinkState.CONNECTED) {
                state.bleStatus?.let { status ->
                    Text(
                        text = listOfNotNull(
                            state.bleDeviceName?.let { "设备：$it" },
                            "录音：${if (status.recording) "进行中" else "待机"}",
                            status.sdPresent?.let {
                                "SD 卡：${if (it) if (status.sdMounted == true) "已挂载" else "未挂载" else "未插入"}"
                            },
                            status.batteryMv?.let { "电池：${"%.2f".format(it / 1000.0)} V" },
                            status.wifiIp?.let { "Wi-Fi：$it" },
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.bleState == BleLinkState.CONNECTED ||
                    state.bleState == BleLinkState.SCANNING ||
                    state.bleState == BleLinkState.CONNECTING
                ) {
                    OutlinedButton(onClick = onStopBle, modifier = Modifier.weight(1f)) {
                        Text("断开蓝牙")
                    }
                } else {
                    Button(
                        onClick = onStartBle,
                        modifier = Modifier.weight(1f),
                        enabled = state.bleSupported,
                    ) {
                        Text("蓝牙待机连接")
                    }
                }
            }
        }
    }
}

@Composable
private fun bleStateText(state: AudioUiState): String {
    if (!state.bleSupported) return "当前手机不支持蓝牙"
    return when (state.bleState) {
        BleLinkState.IDLE -> "待机时保持蓝牙连接，传输时切换 Wi-Fi"
        BleLinkState.SCANNING -> "正在查找录音卡的蓝牙广播…"
        BleLinkState.CONNECTING -> "正在建立连接…"
        BleLinkState.CONNECTED -> "蓝牙已连接，录音状态实时可见"
        BleLinkState.DISCONNECTED -> "连接已断开，可重新连接"
    }
}

@Composable
private fun RecordingCard(
    recording: Recording,
    cached: Boolean,
    transfer: DownloadState?,
    playingName: String?,
    isPlaying: Boolean,
    onDownload: () -> Unit,
    onDownloadAndPlay: () -> Unit,
    onTogglePlayback: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCurrent = playingName == recording.name
    val isDownloading = transfer != null
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = OpenMicProtocol.displayName(recording.name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OpenMicProtocol.sequenceLabel(recording.name)?.let {
                            MetaText(it)
                        }
                        recording.sizeLabel?.takeIf { it.isNotBlank() }?.let {
                            MetaText(it)
                        }
                        if (cached) {
                            MetaText("已保存到手机")
                        }
                    }
                }
                if (isCurrent) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = if (isPlaying) "播放中" else "已暂停",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            if (transfer != null) {
                val fraction = if (transfer.total > 0) {
                    (transfer.received.toFloat() / transfer.total).coerceIn(0f, 1f)
                } else {
                    null
                }
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = if (transfer.total > 0) {
                        "${formatByteCount(transfer.received)} / ${formatByteCount(transfer.total)}"
                    } else {
                        "已接收 ${formatByteCount(transfer.received)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cached) {
                    FilledTonalButton(
                        onClick = onTogglePlayback,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            when {
                                isCurrent && isPlaying -> "暂停"
                                isCurrent -> "继续播放"
                                else -> "播放"
                            },
                        )
                    }
                    TextButton(
                        onClick = onDelete,
                        enabled = !isDownloading,
                    ) {
                        Text("删除")
                    }
                } else {
                    Button(
                        onClick = onDownloadAndPlay,
                        modifier = Modifier.weight(1f),
                        enabled = !isDownloading,
                    ) {
                        Text(if (isDownloading) "下载中…" else "下载并播放")
                    }
                    TextButton(
                        onClick = onDownload,
                        enabled = !isDownloading,
                    ) {
                        Text("仅下载")
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FeedbackBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) {
                Text("关闭", color = content)
            }
        }
    }
}

@Composable
private fun ConnectionHelpCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = "连接提示",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "• App 通过 http://设备地址/files 查询 SD 卡录音文件。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "• 录音卡默认会在录音期间关闭 Wi-Fi，停止录音后再连接。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "• 下载时不要拔出 SD 卡；下载的是 WAV 原文件。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EmptyRecordingsCard(usingCachedLibrary: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 30.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (usingCachedLibrary) "还没有下载过录音" else "SD 卡上没有 WAV 录音",
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "在录音卡上开始录音，停止后点击“查询 SD 卡”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun AudioAppPreview() {
    val sample = listOf(
        Recording("0002-20261021T203015.WAV", "2.4 MB"),
        Recording("0001-20261020T091422.WAV", "11.8 MB"),
    )
    AudioAPPTheme(dynamicColor = false) {
        AudioAppContent(
            state = AudioUiState(
                address = "http://mic.local",
                connectedAddress = "http://mic.local",
                recordings = sample,
                cachedNames = setOf(sample.first().name),
                playingName = sample.first().name,
                isPlaying = true,
                notice = "已连接，找到 2 条录音",
            ),
            onAddressChange = {},
            onConnect = {},
            onQuerySdCard = {},
            onDiscover = {},
            onStartBle = {},
            onStopBle = {},
            onDownload = {},
            onDownloadAndPlay = {},
            onTogglePlayback = {},
            onDeleteCached = {},
            onDismissFeedback = {},
        )
    }
}
