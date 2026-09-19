package com.kc0ver.ncmdump.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/**
 * 存储相关的工具方法。
 *
 * 本项目同时支持两条路径：
 *  1. 「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE）——可以直接拿真实路径喂给 ncmdump 命令行；
 *  2. SAF（Storage Access Framework）——不需要任何权限，但内容要通过 ContentResolver 复制，
 *     而且 SAF 拿到的 content:// URI 无法直接交给原生程序。
 */
object Storage {

    fun hasAllFilesAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 跳转到系统的「所有文件访问权限」授权页 */
    fun allFilesAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        }

    /**
     * 尽力把 SAF 目录树 URI 还原成真实路径。
     *
     * 例如 `content://…/tree/primary%3AMusic%2Fncm` -> `/storage/emulated/0/Music/ncm`。
     * 还原成功不代表进程真的能读写它（那取决于权限），调用方必须再用 canListFiles()/canWrite() 验证。
     */
    fun treeToRealDir(uri: Uri): File? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val index = docId.indexOf(':')
        val volume = if (index >= 0) docId.substring(0, index) else "primary"
        val relative = if (index >= 0) docId.substring(index + 1) else docId
        val base = when {
            volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
            volume.equals("home", ignoreCase = true) -> Environment.getExternalStorageDirectory()
            else -> File("/storage/$volume")
        }
        return if (relative.isEmpty()) base else File(base, relative)
    }

    /**
     * 目录是否真的能列出来。
     *
     * 注意不能用 `File.canRead()`：在 scoped storage 下它对没有存储权限的应用
     * 依然可能返回 true（access() 被放行），但 `listFiles()` 会返回 null。
     * 判断「能不能读」必须以 listFiles() 是否成功为准。
     */
    fun canListFiles(dir: File): Boolean = dir.isDirectory && dir.listFiles() != null

    /**
     * 目录是否真的写得进去。
     *
     * 同样不能用 `File.canWrite()`：scoped storage 下它可能报 true，真正写入时才失败。
     * 这里直接建一个临时文件再删掉，是唯一可靠的判断方式。
     */
    fun canWriteDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return runCatching {
            val probe = File.createTempFile(".ncmdump-probe", ".tmp", dir)
            probe.delete()
            true
        }.getOrDefault(false)
    }

    /** 给用户看的目录描述 */
    fun treeLabel(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return uri.toString()
        return docId.replace(':', '/').let { if (it.startsWith("/")) it else "/$it" }
    }
}
