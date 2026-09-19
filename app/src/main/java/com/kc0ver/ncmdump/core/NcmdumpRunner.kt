package com.kc0ver.ncmdump.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * ncmdump 原生命令行程序的执行器。
 *
 * 二进制被编译成 `libncmdump.so` 放在 jniLibs 里，安装后 Android 会把它解压到
 * `applicationInfo.nativeLibraryDir` —— 这是 Android 10 之后少数几个仍然允许
 * `exec()` 的目录（应用私有目录里的可执行文件会被 W^X 策略拒绝执行）。
 */
class NcmdumpRunner(private val context: Context) {

    @Volatile
    private var process: Process? = null

    val binary: File get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    fun isAvailable(): Boolean = binary.let { it.isFile && it.canExecute() }

    fun cancel() {
        process?.let { runCatching { it.destroy() } }
        process = null
    }

    data class ExecResult(val exitCode: Int, val lines: List<String>) {
        val text: String get() = lines.joinToString("\n")
    }

    /** 执行一次 ncmdump 命令，返回退出码和合并后的 stdout/stderr（已去掉 ANSI 颜色码） */
    suspend fun exec(args: List<String>): ExecResult = withContext(Dispatchers.IO) {
        val exe = binary
        if (!exe.isFile) {
            return@withContext ExecResult(-1, listOf("找不到原生程序：${exe.absolutePath}"))
        }

        val command = ArrayList<String>(args.size + 1)
        command.add(exe.absolutePath)
        command.addAll(args)

        val proc = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .directory(context.cacheDir)
                .start()
        } catch (t: Throwable) {
            return@withContext ExecResult(-1, listOf("无法启动 ncmdump：${t.message ?: t.javaClass.simpleName}"))
        }

        process = proc
        val lines = ArrayList<String>()
        try {
            proc.inputStream.bufferedReader().useLines { sequence ->
                for (line in sequence) lines.add(stripAnsi(line))
            }
        } catch (_: IOException) {
            // 进程被 cancel() 杀掉时会走到这里，属于正常情况
        }
        val code = try {
            proc.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }
        process = null
        Log.d(TAG, "exec ${args.joinToString(" ")} -> exit=$code, output=${lines.size} lines")
        ExecResult(code, lines)
    }

    companion object {
        private const val TAG = "NcmdumpRunner"

        /** 必须叫 lib*.so，Android 才会把它当作 native library 解压出来 */
        const val BINARY_NAME = "libncmdump.so"

        private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

        fun stripAnsi(value: String): String = ANSI.replace(value, "")

        /** `[Done] 'src' -> 'dst'` */
        private val DONE_LINE = Regex("\\[Done]\\s*'(.*?)'\\s*->\\s*'(.*?)'")

        fun parseDoneLine(line: String): String? =
            DONE_LINE.find(line)?.groupValues?.getOrNull(2)

        fun parseErrorLine(line: String): String? =
            if (line.contains("[Error]") || line.contains("[Exception]")) line else null
    }
}
