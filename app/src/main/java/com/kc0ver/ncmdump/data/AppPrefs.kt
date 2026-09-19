package com.kc0ver.ncmdump.data

import android.content.Context
import android.net.Uri
import com.kc0ver.ncmdump.model.OutputTarget
import java.io.File

/** 轻量设置存储（SharedPreferences 足够，避免引入 DataStore） */
class AppPrefs(context: Context) {

    private val sp = context.getSharedPreferences("ncmdump_settings", Context.MODE_PRIVATE)

    /** 没有设置默认目录时，每次转换前都询问保存位置 */
    var askEveryTime: Boolean
        get() = sp.getBoolean(KEY_ASK, true)
        set(value) = sp.edit().putBoolean(KEY_ASK, value).apply()

    /** 转换成功后删除源文件 */
    var deleteSource: Boolean
        get() = sp.getBoolean(KEY_DELETE_SOURCE, false)
        set(value) = sp.edit().putBoolean(KEY_DELETE_SOURCE, value).apply()

    private var treeUri: String?
        get() = sp.getString(KEY_TREE_URI, null)
        set(value) = sp.edit().putString(KEY_TREE_URI, value).apply()

    private var realPath: String?
        get() = sp.getString(KEY_REAL_PATH, null)
        set(value) = sp.edit().putString(KEY_REAL_PATH, value).apply()

    private var label: String
        get() = sp.getString(KEY_LABEL, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LABEL, value).apply()

    fun savedTarget(): OutputTarget {
        val dir = realPath?.let(::File)
        val tree = treeUri?.let(Uri::parse)
        if (dir == null && tree == null) return OutputTarget.NONE
        // 两个都留着：有全文件访问权限时走真实路径，权限被收回时自动退回 SAF
        return OutputTarget(dir, tree, label)
    }

    fun saveTarget(target: OutputTarget) {
        realPath = target.realDir?.absolutePath
        treeUri = target.treeUri?.toString()
        label = target.label
    }

    fun clearTarget() {
        realPath = null
        treeUri = null
        label = ""
    }

    private companion object {
        const val KEY_ASK = "ask_every_time"
        const val KEY_DELETE_SOURCE = "delete_source"
        const val KEY_TREE_URI = "output_tree_uri"
        const val KEY_REAL_PATH = "output_real_path"
        const val KEY_LABEL = "output_label"
    }
}
