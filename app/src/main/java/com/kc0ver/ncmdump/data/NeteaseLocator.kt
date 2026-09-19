package com.kc0ver.ncmdump.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 自动识别网易云音乐的下载 / 缓存目录。
 *
 * 网易云音乐在不同版本里把 ncm 放在不同位置，这里做两件事：
 *  1. 先试一份已知路径清单（快）；
 *  2. 再在外部存储根目录下按目录名做启发式搜索（`cloudmusic` / `netease` /
 *     `com.netease.cloudmusic`），避免版本换了目录名就找不到。
 */
object NeteaseLocator {

    const val PACKAGE_NAME = "com.netease.cloudmusic"

    /** 网易云音乐历史上用过的 ncm 存放位置（相对外部存储根目录） */
    private val KNOWN_RELATIVE_PATHS = listOf(
        "netease/cloudmusic/Music",
        "netease/cloudmusic",
        "cloudmusic/Music",
        "cloudmusic",
        "Music/netease/cloudmusic",
        "Download/netease/cloudmusic/Music",
        "Documents/netease/cloudmusic/Music",
        "Android/data/com.netease.cloudmusic/files/Music",
        "Android/data/com.netease.cloudmusic/files/Download",
        "Android/data/com.netease.cloudmusic/files/download",
        "Android/data/com.netease.cloudmusic/files",
        "Android/data/com.netease.cloudmusic/cache",
        "Android/data/com.netease.cloudmusic/cache/Music",
    )

    private val HEURISTIC_NAMES = setOf("cloudmusic", "netease", "com.netease.cloudmusic")

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** 已知路径里存在、且至少含有一个 ncm 文件的目录 */
    fun knownDirs(): List<File> {
        val storage = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return emptyList()
        return KNOWN_RELATIVE_PATHS
            .map { File(storage, it) }
            .filter { it.isDirectory && it.listFiles() != null }
    }

    /**
     * 启发式搜索：在外部存储里找名字像网易云的目录，再看里面有没有 ncm。
     * 只下钻 4 层，代价可控。
     */
    fun heuristicDirs(maxDepth: Int = 4): List<File> {
        val storage = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return emptyList()
        val found = LinkedHashSet<File>()

        fun search(dir: File, depth: Int) {
            if (depth > maxDepth || found.size >= 12) return
            if (!dir.isDirectory || dir.listFiles() == null) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (!child.isDirectory) continue
                if (child.name.lowercase() in HEURISTIC_NAMES) {
                    found.add(child)
                    // 网易云目录本身可能还有子目录，交给上层递归扫描即可
                } else if (!child.name.startsWith(".")) {
                    search(child, depth + 1)
                }
            }
        }

        search(storage, 0)
        return found.toList()
    }

    /**
     * 汇总所有「确实含 ncm 文件」的候选目录。
     * 返回 目录 -> ncm 数量。
     */
    fun locate(): Map<File, Int> {
        val candidates = LinkedHashSet<File>()
        candidates.addAll(knownDirs())
        candidates.addAll(heuristicDirs())

        val result = LinkedHashMap<File, Int>()
        for (dir in candidates) {
            val files = NcmScanner.scanDir(dir, recursive = true, limit = 500)
            if (files.isNotEmpty()) {
                // 如果父目录已经覆盖了这个结果，就跳过子目录，避免重复
                if (result.keys.any { dir.absolutePath.startsWith(it.absolutePath + "/") }) continue
                result[dir] = files.size
            }
        }
        return result
    }
}
