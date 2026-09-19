package com.kc0ver.ncmdump.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kc0ver.ncmdump.NcmViewModel
import com.kc0ver.ncmdump.R
import com.kc0ver.ncmdump.UiState
import com.kc0ver.ncmdump.core.Storage
import com.kc0ver.ncmdump.model.ConvertStatus
import com.kc0ver.ncmdump.model.NcmItem
import com.kc0ver.ncmdump.ui.theme.StatColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(viewModel: NcmViewModel = viewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.addDocuments(uris) }

    // 三个互相独立的目录选择器。
    // 用独立 launcher（而不是用 remember 状态记住「这次选目录是为了干嘛」）是有意为之：
    // 选择器在前台时本进程可能被系统回收，ActivityResultRegistry 能按 key 把结果恢复回来，
    // 但 remember 的状态会丢，导致回调里分不清意图、什么也不做。
    val scanFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            persistTreePermission(context, uri)
            viewModel.addTree(uri, Storage.treeLabel(uri))
        }
    }

    val defaultDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            persistTreePermission(context, uri)
            val target = viewModel.buildTarget(uri)
            viewModel.setTarget(target, rememberAsDefault = true)
            viewModel.postMessage("默认保存位置：${target.label}")
        }
    }

    val runDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            persistTreePermission(context, uri)
            viewModel.setTarget(
                viewModel.buildTarget(uri),
                rememberAsDefault = !state.askEveryTime,
            )
            viewModel.startConversion()
        }
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshPermission() }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermission() }

    val requestAllFilesAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesLauncher.launch(viewModel.allFilesAccessIntent())
        } else {
            legacyPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ConvertFab(
                state = state,
                onStart = {
                    if (state.items.none { it.status != ConvertStatus.SUCCESS }) {
                        viewModel.postMessage("先添加一些 .ncm 文件吧")
                    } else if (viewModel.needsTargetPicker()) {
                        runDirLauncher.launch(state.target.treeUri)
                    } else {
                        viewModel.startConversion()
                    }
                },
                onCancel = viewModel::cancelConversion,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            StatsHeader(state = state)

            if (state.converting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OutputRow(
                state = state,
                onPick = { defaultDirLauncher.launch(state.target.treeUri) },
                onClear = viewModel::clearDefaultTarget,
            )

            QuickActions(
                state = state,
                onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                onScanFolder = { scanFolderLauncher.launch(null) },
                onScanNetease = viewModel::scanNetease,
                onDeepScan = {
                    if (!viewModel.requireAllFilesAccess()) viewModel.deepScan()
                },
                onClear = viewModel::clearAll,
                onRetry = viewModel::retryFailed,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Box(modifier = Modifier.weight(1f)) {
                if (state.items.isEmpty()) {
                    EmptyState(
                        onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                        onScanNetease = viewModel::scanNetease,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        items(items = state.items, key = { it.id }) { item ->
                            FileRow(
                                item = item,
                                onRemove = { viewModel.removeItem(item.id) },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            state = state,
            onDismiss = { showSettings = false },
            onAskEveryTimeChange = viewModel::setAskEveryTime,
            onDeleteSourceChange = viewModel::setDeleteSource,
            onPickDefaultDir = { defaultDirLauncher.launch(state.target.treeUri) },
            onClearDefaultDir = viewModel::clearDefaultTarget,
            onRequestAllFilesAccess = requestAllFilesAccess,
        )
    }
}

// ------------------------------------------------------------------ 顶部计数

@Composable
private fun StatsHeader(state: UiState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(
                label = stringResource(R.string.stats_pending),
                value = state.pendingCount,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StatCell(
                label = stringResource(R.string.stats_success),
                value = state.successCount,
                color = StatColors.success,
            )
            StatCell(
                label = stringResource(R.string.stats_failed),
                value = state.failedCount,
                color = StatColors.failure,
            )
        }
        if (state.converting || state.scanning) {
            Text(
                text = when {
                    state.scanning -> "正在扫描…"
                    state.binaryReady -> "正在转换：${state.currentLabel ?: "…"}"
                    else -> "原生 ncmdump 不可用"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp)
                    .padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: Int, color: Color) {
    Column(
        modifier = Modifier.width(104.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -------------------------------------------------------------- 保存位置一行

@Composable
private fun OutputRow(
    state: UiState,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = if (state.askEveryTime) "保存位置 · 每次询问" else "保存位置 · 默认目录",
                style = MaterialTheme.typography.titleSmall,
            )
        },
        supportingContent = {
            Text(
                text = when {
                    state.askEveryTime && state.target.isUsable -> "本次：${state.target.label}"
                    state.askEveryTime -> "转换前会弹出目录选择器"
                    state.target.isUsable -> state.target.label
                    else -> "尚未设置默认目录"
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!state.askEveryTime && state.target.isUsable) {
                    TextButton(onClick = onClear) { Text("清除") }
                }
                TextButton(onClick = onPick) { Text("选择") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

// ------------------------------------------------------------------ 快捷操作

@Composable
private fun QuickActions(
    state: UiState,
    onPickFiles: () -> Unit,
    onScanFolder: () -> Unit,
    onScanNetease: () -> Unit,
    onDeepScan: () -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
) {
    val enabled = !state.converting && !state.scanning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onPickFiles,
            enabled = enabled,
            label = { Text("选择文件") },
            leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
        )
        AssistChip(
            onClick = onScanFolder,
            enabled = enabled,
            label = { Text("扫描文件夹") },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
        )
        AssistChip(
            onClick = onScanNetease,
            enabled = enabled,
            label = { Text("网易云目录") },
            leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) },
        )
        AssistChip(
            onClick = onDeepScan,
            enabled = enabled,
            label = { Text("深度扫描") },
            leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)) },
        )
        if (state.failedCount > 0) {
            AssistChip(
                onClick = onRetry,
                enabled = enabled,
                label = { Text("重试失败") },
                leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)) },
            )
        }
        if (state.items.isNotEmpty()) {
            AssistChip(
                onClick = onClear,
                enabled = enabled,
                label = { Text("清空列表") },
                leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)) },
            )
        }
    }
}

// ------------------------------------------------------------------ 文件条目

@Composable
private fun FileRow(item: NcmItem, onRemove: () -> Unit) {
    val trailing: (@Composable () -> Unit)? =
        if (item.status == ConvertStatus.PENDING) {
            {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "移除")
                }
            }
        } else {
            null
        }

    ListItem(
        headlineContent = {
            Text(
                text = item.source.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = item.source.locationLabel.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatSize(item.source.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusLabel(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(item.status),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        leadingContent = { StatusIcon(item.status) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun StatusIcon(status: ConvertStatus) {
    when (status) {
        ConvertStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )

        ConvertStatus.SUCCESS -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = StatColors.success,
        )

        ConvertStatus.FAILED -> Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = StatColors.failure,
        )

        ConvertStatus.PENDING -> Box(
            modifier = Modifier
                .size(14.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .background(Color.Transparent, CircleShape),
        )
    }
}

@Composable
private fun statusLabel(item: NcmItem): String = when (item.status) {
    ConvertStatus.PENDING -> "等待转换"
    ConvertStatus.RUNNING -> "转换中…"
    ConvertStatus.SUCCESS -> item.outputName?.let { "已输出 $it" } ?: "转换成功"
    ConvertStatus.FAILED -> item.message?.take(120) ?: "转换失败"
}

@Composable
private fun statusColor(status: ConvertStatus): Color = when (status) {
    ConvertStatus.SUCCESS -> StatColors.success
    ConvertStatus.FAILED -> StatColors.failure
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ------------------------------------------------------------------ 空状态

@Composable
private fun EmptyState(onPickFiles: () -> Unit, onScanNetease: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.List,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "还没有待转换的 .ncm 文件",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "可以自己挑文件，也可以让应用自动找出网易云音乐下载的歌曲。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPickFiles) { Text("选择文件") }
            OutlinedButton(onClick = onScanNetease) { Text("自动识别网易云") }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "提示：网易云音乐新版把歌曲放在 Android/data 下，想自动扫描到它需要在设置里授予「所有文件访问权限」。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

// ------------------------------------------------------------------ 悬浮按钮

@Composable
private fun ConvertFab(state: UiState, onStart: () -> Unit, onCancel: () -> Unit) {
    if (state.converting) {
        ExtendedFloatingActionButton(
            text = { Text("取消转换") },
            icon = { Icon(Icons.Default.Close, contentDescription = null) },
            onClick = onCancel,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        ExtendedFloatingActionButton(
            text = {
                Text(
                    if (state.pendingCount > 0) "开始转换 (${state.pendingCount})" else "开始转换",
                )
            },
            icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
            onClick = onStart,
        )
    }
}

internal fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "大小未知"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}

/** 把目录树的持久化读写权限记下来，重启应用后仍然可用 */
private fun persistTreePermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}
