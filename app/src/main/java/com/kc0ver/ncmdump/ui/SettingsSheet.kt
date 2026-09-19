package com.kc0ver.ncmdump.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kc0ver.ncmdump.BuildConfig
import com.kc0ver.ncmdump.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onAskEveryTimeChange: (Boolean) -> Unit,
    onDeleteSourceChange: (Boolean) -> Unit,
    onPickDefaultDir: () -> Unit,
    onClearDefaultDir: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SwitchRow(
                title = "每次转换前询问保存位置",
                subtitle = "没有设置默认目录时，每次开始转换都会弹出目录选择器",
                checked = state.askEveryTime,
                onCheckedChange = onAskEveryTimeChange,
            )

            SwitchRow(
                title = "转换成功后删除源文件",
                subtitle = "直接删掉本地的 .ncm，请谨慎开启",
                checked = state.deleteSource,
                onCheckedChange = onDeleteSourceChange,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ListItem(
                headlineContent = { Text("默认保存位置") },
                supportingContent = {
                    Text(
                        text = state.target.takeIf { it.isUsable }?.label ?: "未设置",
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.target.isUsable) {
                            TextButton(onClick = onClearDefaultDir) { Text("清除") }
                        }
                        TextButton(onClick = onPickDefaultDir) { Text("选择") }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            Text(
                text = "提示：Android 11 起系统禁止把「Download」根目录授权给应用。" +
                    "想存到下载目录，请选它下面的子文件夹（例如 Download/NCM），或选择 Music、Documents 等目录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ListItem(
                headlineContent = { Text("所有文件访问权限") },
                supportingContent = {
                    Text(
                        text = if (state.allFilesAccess) {
                            "已授予，可以直接读写任意目录，扫描网易云目录更彻底"
                        } else {
                            "未授予。仍可通过 SAF 选择文件和目录，但无法自动扫描 Android/data 下的网易云缓存"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    if (!state.allFilesAccess) {
                        TextButton(onClick = onRequestAllFilesAccess) { Text("去授权") }
                    } else {
                        Text("已开启", color = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("关于", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "NCM 转换器 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "转换内核：taurusxin/ncmdump " +
                        (state.ncmdumpVersion?.let { "v$it" } ?: "（版本未知）") +
                        "，以 Android NDK 交叉编译的原生二进制随 APK 分发",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (state.binaryReady) "原生程序状态：正常" else "原生程序状态：缺失（请重新安装 APK）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.binaryReady) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                LinkRow(
                    label = "项目地址",
                    value = "github.com/kc0ver/ncmdump-android",
                    url = "https://github.com/kc0ver/ncmdump-android",
                )
                LinkRow(
                    label = "灵感来源",
                    value = "lilyco-42/ncmdump-android",
                    url = "https://github.com/lilyco-42/ncmdump-android",
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** 「标签：可点击链接」一行，点了用系统浏览器打开 */
@Composable
private fun LinkRow(label: String, value: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
