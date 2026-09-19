package com.kc0ver.ncmdump.model

import android.net.Uri
import java.io.File

/**
 * 一个待转换的 ncm 来源。
 *
 * Android 的存储模型决定了有两种完全不同的来源：
 *  - [RealFile]：可以直接拿到真实路径（需要「所有文件访问权限」，或位于应用自己的目录）
 *  - [Document]：只有一个 SAF 的 content:// URI（无需任何权限，但要先把内容复制出来）
 */
sealed interface NcmSource {
    val displayName: String
    val sizeBytes: Long
    val locationLabel: String

    data class RealFile(
        val file: File,
        override val sizeBytes: Long,
    ) : NcmSource {
        override val displayName: String get() = file.name
        override val locationLabel: String get() = file.parent.orEmpty()
    }

    data class Document(
        val uri: Uri,
        override val displayName: String,
        override val sizeBytes: Long,
        override val locationLabel: String,
    ) : NcmSource
}

enum class ConvertStatus { PENDING, RUNNING, SUCCESS, FAILED }

data class NcmItem(
    val id: String,
    val source: NcmSource,
    val status: ConvertStatus = ConvertStatus.PENDING,
    val outputName: String? = null,
    val message: String? = null,
)

/**
 * 转换结果的去处。
 *  - [realDir] 可直接写入时优先用它（快，不需要二次复制）
 *  - 否则退化为 [treeUri]，转换完再通过 ContentResolver 写进 SAF 目录树
 */
data class OutputTarget(
    val realDir: File?,
    val treeUri: Uri?,
    val label: String,
) {
    val isUsable: Boolean get() = realDir != null || treeUri != null

    companion object {
        val NONE = OutputTarget(null, null, "")
    }
}
