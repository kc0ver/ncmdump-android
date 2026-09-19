package com.kc0ver.ncmdump.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.kc0ver.ncmdump.model.NcmItem
import com.kc0ver.ncmdump.model.NcmSource
import com.kc0ver.ncmdump.model.OutputTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单个 ncm 文件的转换流程：
 *
 *  1. 准备输入 —— 有真实路径就直接用，否则把 SAF 文档复制到缓存目录；
 *  2. 选择落地目录 —— 目标目录可直接写就一步到位，否则先转到缓存再发布出去；
 *  3. 调用 ncmdump 命令行；
 *  4. 校验产物是否真的生成（ncmdump 即使全部失败退出码也是 0，不能只看退出码）；
 *  5. 按需把产物写入 SAF 目录 / 删除源文件。
 */
class Converter(
    private val context: Context,
    private val runner: NcmdumpRunner,
) {

    data class Outcome(
        val success: Boolean,
        val outputName: String? = null,
        val message: String? = null,
    )

    private val workRoot: File get() = File(context.cacheDir, "ncmwork")

    suspend fun convert(
        item: NcmItem,
        target: OutputTarget,
        deleteSource: Boolean,
    ): Outcome = withContext(Dispatchers.IO) {
        val inbox = File(workRoot, "in").apply { mkdirs() }
        val outbox = File(workRoot, "out").apply { mkdirs() }
        outbox.listFiles()?.forEach { it.delete() }

        // 1) 输入
        val directInput = (item.source as? NcmSource.RealFile)?.file?.takeIf { canReadFile(it) }
        val input: File = directInput ?: run {
            val copy = File(inbox, safeName(item.source.displayName))
            if (!copySource(item.source, copy)) {
                return@withContext Outcome(false, message = "无法读取源文件")
            }
            copy
        }

        // 2) 输出目录：能直接写就省掉一次复制
        val directDir = target.realDir?.takeIf { Storage.canWriteDir(it) }
        val stageDir = directDir ?: outbox

        // 3) ncmdump 命令行本体
        val startedAt = System.currentTimeMillis() - 2_000L
        val result = runner.exec(
            listOf("-o", stageDir.absolutePath, input.absolutePath),
        )

        // 4) 以文件系统为准判断是否成功
        val baseName = input.name.substringBeforeLast('.', input.name)
        val produced = findProduced(stageDir, baseName, startedAt)

        if (produced == null) {
            val detail = result.lines.firstNotNullOfOrNull { NcmdumpRunner.parseErrorLine(it) }
                ?: result.lines.lastOrNull { it.isNotBlank() }
                ?: "ncmdump 未生成输出文件（退出码 ${result.exitCode}）"
            if (directInput == null) input.delete()
            return@withContext Outcome(false, message = detail.trim())
        }

        // 5) 发布到最终目标
        if (directDir == null) {
            if (!publish(produced, target)) {
                if (directInput == null) input.delete()
                return@withContext Outcome(false, message = "无法写入目标目录，请重新选择保存位置")
            }
        }

        if (deleteSource) removeSource(item.source)
        if (directInput == null) input.delete()

        Outcome(true, outputName = produced.name)
    }

    fun cleanup() {
        runCatching { workRoot.deleteRecursively() }
    }

    private fun findProduced(dir: File, baseName: String, startedAt: Long): File? {
        val expected = AUDIO_EXTENSIONS
            .map { File(dir, "$baseName.$it") }
            .firstOrNull { it.isFile && it.length() > 0L && it.lastModified() >= startedAt }
        if (expected != null) return expected

        // 兜底：ncmdump 可能对文件名做了转义，直接挑最新产生的音频文件
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0L && it.lastModified() >= startedAt }
            ?.firstOrNull { it.extension.lowercase() in AUDIO_EXTENSIONS }
    }

    /** canRead() 在 scoped storage 下会说谎，真正打开一次才作数 */
    private fun canReadFile(file: File): Boolean =
        file.isFile && runCatching { file.inputStream().use { true } }.getOrDefault(false)

    private fun copySource(source: NcmSource, destination: File): Boolean = runCatching {
        when (source) {
            is NcmSource.RealFile -> source.file.inputStream().use { input ->
                destination.outputStream().use { input.copyTo(it) }
            }

            is NcmSource.Document -> context.contentResolver.openInputStream(source.uri)?.use { input ->
                destination.outputStream().use { input.copyTo(it) }
            } ?: return false
        }
        true
    }.getOrDefault(false)

    /** 把缓存里的产物写进用户选定的目标（真实目录或 SAF 目录树） */
    private fun publish(file: File, target: OutputTarget): Boolean {
        target.realDir?.let { dir ->
            if (Storage.canWriteDir(dir)) {
                return runCatching {
                    file.copyTo(File(dir, file.name), overwrite = true)
                    true
                }.getOrDefault(false)
            }
        }

        val tree = target.treeUri ?: return false
        return runCatching {
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            val mime = if (file.extension.equals("flac", ignoreCase = true)) "audio/flac" else "audio/mpeg"
            val document = DocumentsContract.createDocument(resolver, parent, mime, file.name)
                ?: return false
            resolver.openOutputStream(document)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    private fun removeSource(source: NcmSource) {
        runCatching {
            when (source) {
                is NcmSource.RealFile -> source.file.delete()
                is NcmSource.Document ->
                    DocumentsContract.deleteDocument(context.contentResolver, source.uri)
            }
        }
    }

    private fun safeName(name: String): String {
        val sanitized = name.map { if (it.isLetterOrDigit() || it in "._- ()[]" || it.code > 127) it else '_' }
        return sanitized.joinToString("")
    }

    private companion object {
        val AUDIO_EXTENSIONS = listOf("flac", "mp3")
    }
}
