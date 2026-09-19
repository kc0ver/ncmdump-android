package com.kc0ver.ncmdump.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * 扫描本地的 .ncm 文件。
 *
 * 支持三种来源：真实目录、SAF 目录树、以及全盘深度扫描。
 */
object NcmScanner {

    const val EXTENSION = "ncm"

    fun isNcm(name: String): Boolean =
        name.substringAfterLast('.', "").equals(EXTENSION, ignoreCase = true)

    /** 扫描一个真实目录（需要可读权限） */
    fun scanDir(root: File, recursive: Boolean = true, limit: Int = MAX_FILES): List<File> {
        // 不用 canRead()：scoped storage 下它不可靠，listFiles() 返回 null 才是真读不到
        if (!root.isDirectory || root.listFiles() == null) return emptyList()
        val result = ArrayList<File>()
        walk(root, recursive, limit, result)
        return result.sortedBy { it.name.lowercase() }
    }

    private fun walk(dir: File, recursive: Boolean, limit: Int, into: MutableList<File>) {
        if (into.size >= limit) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (into.size >= limit) return
            if (child.isDirectory) {
                if (recursive && !child.name.startsWith(".")) walk(child, true, limit, into)
            } else if (isNcm(child.name) && child.length() > 0L) {
                into.add(child)
            }
        }
    }

    data class TreeEntry(val uri: Uri, val name: String, val size: Long, val parent: String)

    /** 扫描一棵 SAF 目录树 */
    fun scanTree(context: Context, treeUri: Uri, limit: Int = MAX_FILES): List<TreeEntry> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val result = ArrayList<TreeEntry>()
        val pending = ArrayDeque<Uri>()
        runCatching {
            pending.addLast(
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                ),
            )
        }.onFailure {
            Log.w(TAG, "无法解析目录树 $treeUri", it)
            return emptyList()
        }

        while (pending.isNotEmpty() && result.size < limit) {
            val childrenUri = pending.removeLast()
            runCatching {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext() && result.size < limit) {
                        val documentId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val mime = cursor.getString(2)
                        val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.addLast(
                                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId),
                            )
                        } else if (isNcm(name)) {
                            result.add(
                                TreeEntry(
                                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                                    name = name,
                                    size = size,
                                    parent = treeUri.toString(),
                                ),
                            )
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "查询 $childrenUri 失败", it) }
        }
        Log.d(TAG, "scanTree $treeUri -> ${result.size} 个 ncm")
        return result.sortedBy { it.name.lowercase() }
    }

    /**
     * 全盘深度扫描（需要「所有文件访问权限」）。
     * 带深度/数量/时间预算，避免在文件极多的机器上卡死。
     */
    fun deepScan(
        roots: List<File> = defaultRoots(),
        maxDepth: Int = 8,
        limit: Int = 2000,
        budgetMillis: Long = 20_000L,
    ): List<File> {
        val deadline = System.currentTimeMillis() + budgetMillis
        val result = ArrayList<File>()
        val visited = HashSet<String>()

        fun recurse(dir: File, depth: Int) {
            if (depth > maxDepth || result.size >= limit) return
            if (System.currentTimeMillis() > deadline) return
            if (!dir.isDirectory || dir.listFiles() == null) return
            if (!visited.add(dir.absolutePath)) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (result.size >= limit || System.currentTimeMillis() > deadline) return
                if (child.isDirectory) {
                    val name = child.name
                    if (name.startsWith(".") || name == "obb") continue
                    recurse(child, depth + 1)
                } else if (isNcm(name = child.name) && child.length() > 0L) {
                    result.add(child)
                }
            }
        }

        roots.filter { it.isDirectory }.forEach { recurse(it, 0) }
        return result.sortedBy { it.name.lowercase() }
    }

    fun defaultRoots(): List<File> = buildList {
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { add(it) }
        File("/storage").listFiles()
            ?.filter { it.name != "emulated" && it.name != "self" && it.listFiles() != null }
            ?.forEach { add(it) }
    }

    const val MAX_FILES = 5000

    private const val TAG = "NcmScanner"
}
