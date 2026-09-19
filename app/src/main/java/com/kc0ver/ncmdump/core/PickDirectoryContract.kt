package com.kc0ver.ncmdump.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/**
 * 目录选择契约，比 `ActivityResultContracts.OpenDocumentTree` 多两处优化：
 *
 * 1. `EXTRA_LOCAL_ONLY = true`：让 DocumentsUI 跳过云端 provider（Drive 等）的
 *    roots 查询，只列本地存储；
 * 2. `EXTRA_INITIAL_URI`：直接把选择器落在此前用过的目录上。
 *
 * 之所以要自己写：`DocumentsUI` 的 `PickActivity` 启动开销完全在系统侧，应用改不了。
 * 在 Android 16 x86_64 模拟器上实测 START → Displayed 冷启动约 1.41 s、热启动约 0.68 s，
 * 而应用从点击到 `startActivity` 只花了不到 0.1 s。既然无法让它变快，就只能
 * ①少查一点 provider ②少让用户点几层 ③有权限时干脆用应用内置的选择器绕过它。
 */
class PickDirectoryContract : ActivityResultContract<Uri?, Uri?>() {

    override fun createIntent(context: Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            if (input != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
            }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
