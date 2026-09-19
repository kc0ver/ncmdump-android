package com.kc0ver.ncmdump

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kc0ver.ncmdump.core.Converter
import com.kc0ver.ncmdump.core.NcmdumpRunner
import com.kc0ver.ncmdump.core.Storage
import com.kc0ver.ncmdump.data.AppPrefs
import com.kc0ver.ncmdump.data.NcmScanner
import com.kc0ver.ncmdump.data.NeteaseLocator
import com.kc0ver.ncmdump.model.ConvertStatus
import com.kc0ver.ncmdump.model.NcmItem
import com.kc0ver.ncmdump.model.NcmSource
import com.kc0ver.ncmdump.model.OutputTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val items: List<NcmItem> = emptyList(),
    val converting: Boolean = false,
    val scanning: Boolean = false,
    val currentLabel: String? = null,
    val target: OutputTarget = OutputTarget.NONE,
    val askEveryTime: Boolean = true,
    val deleteSource: Boolean = false,
    val allFilesAccess: Boolean = false,
    val binaryReady: Boolean = true,
    val ncmdumpVersion: String? = null,
    val neteaseInstalled: Boolean = false,
    val message: String? = null,
) {
    val pendingCount: Int
        get() = items.count { it.status == ConvertStatus.PENDING || it.status == ConvertStatus.RUNNING }

    val successCount: Int get() = items.count { it.status == ConvertStatus.SUCCESS }

    val failedCount: Int get() = items.count { it.status == ConvertStatus.FAILED }
}

class NcmViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPrefs(application)
    private val runner = NcmdumpRunner(application)
    private val converter = Converter(application, runner)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    @Volatile
    private var cancelRequested = false

    init {
        refreshPermission()
        _ui.update {
            it.copy(
                target = prefs.savedTarget(),
                askEveryTime = prefs.askEveryTime,
                deleteSource = prefs.deleteSource,
                binaryReady = runner.isAvailable(),
                neteaseInstalled = NeteaseLocator.isInstalled(application),
            )
        }
        viewModelScope.launch {
            val result = runner.exec(listOf("--version"))
            val version = result.lines
                .firstOrNull { it.contains("ncmdump version") }
                ?.substringAfter("ncmdump version")
                ?.trim()
            if (version != null) _ui.update { it.copy(ncmdumpVersion = version) }
        }
    }

    // ------------------------------------------------------------------ 权限

    fun refreshPermission() {
        _ui.update { it.copy(allFilesAccess = Storage.hasAllFilesAccess(getApplication())) }
    }

    fun allFilesAccessIntent() = Storage.allFilesAccessIntent(getApplication())

    fun requireAllFilesAccess(): Boolean {
        if (_ui.value.allFilesAccess) return false
        postMessage("这个功能需要「所有文件访问权限」")
        return true
    }

    // ------------------------------------------------------------ 添加文件

    fun addDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val sources = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val (name, size) = queryDocument(uri) ?: return@mapNotNull null
                    if (!NcmScanner.isNcm(name)) return@mapNotNull null
                    NcmSource.Document(uri, name, size, documentLabel(uri))
                }
            }
            addSources(sources, "已选择")
        }
    }

    fun addTree(uri: Uri, label: String) {
        viewModelScope.launch {
            _ui.update { it.copy(scanning = true) }
            // 只有真的握有「所有文件访问权限」时才敢用真实路径。
            // 没有权限时 scoped storage 会让 listFiles() 返回一个「非 null 但为空」的列表，
            // 光靠 canListFiles() 判断会静默地扫出 0 个文件。
            val realDir = if (_ui.value.allFilesAccess) {
                Storage.treeToRealDir(uri)?.takeIf { Storage.canListFiles(it) }
            } else {
                null
            }
            val sources = withContext(Dispatchers.IO) {
                if (realDir != null) {
                    NcmScanner.scanDir(realDir, recursive = true)
                        .map { NcmSource.RealFile(it, it.length()) }
                } else {
                    NcmScanner.scanTree(getApplication(), uri)
                        .map { NcmSource.Document(it.uri, it.name, it.size, label) }
                }
            }
            _ui.update { it.copy(scanning = false) }
            addSources(sources, "扫描「$label」")
        }
    }

    /** 添加真实路径的文件/目录（需要可读权限） */
    fun addRealPath(root: File, label: String) {
        viewModelScope.launch {
            _ui.update { it.copy(scanning = true) }
            val files = withContext(Dispatchers.IO) {
                if (root.isDirectory) NcmScanner.scanDir(root, recursive = true)
                else if (root.isFile && NcmScanner.isNcm(root.name)) listOf(root)
                else emptyList()
            }
            _ui.update { it.copy(scanning = false) }
            addSources(files.map { NcmSource.RealFile(it, it.length()) }, label)
        }
    }

    private fun addSources(sources: List<NcmSource>, label: String) {
        if (sources.isEmpty()) {
            postMessage("$label：没有找到 .ncm 文件")
            return
        }
        val known = _ui.value.items.map { it.source.key }.toMutableSet()
        val fresh = sources
            .filter { known.add(it.key) }
            .map { NcmItem(id = it.key, source = it) }
        if (fresh.isEmpty()) {
            postMessage("$label：文件已经在列表里了")
            return
        }
        _ui.update { it.copy(items = it.items + fresh) }
        postMessage("$label：新增 ${fresh.size} 个文件")
    }

    fun removeItem(id: String) {
        if (_ui.value.converting) {
            postMessage("转换中，暂时不能移除文件")
            return
        }
        _ui.update { state -> state.copy(items = state.items.filterNot { it.id == id }) }
    }

    fun clearAll() {
        if (_ui.value.converting) {
            postMessage("转换中，暂时不能清空列表")
            return
        }
        _ui.update { it.copy(items = emptyList()) }
    }

    fun retryFailed() {
        _ui.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.status == ConvertStatus.FAILED) it.copy(status = ConvertStatus.PENDING, message = null) else it
                },
            )
        }
    }

    // -------------------------------------------------------- 网易云自动识别

    fun scanNetease() {
        viewModelScope.launch {
            _ui.update { it.copy(scanning = true) }
            val located = withContext(Dispatchers.IO) { NeteaseLocator.locate() }
            val files = withContext(Dispatchers.IO) {
                located.keys.flatMap { NcmScanner.scanDir(it, recursive = true) }
            }
            _ui.update { it.copy(scanning = false) }
            if (located.isEmpty()) {
                postMessage(
                    if (_ui.value.allFilesAccess) {
                        "没找到网易云音乐的 ncm 目录，可以试试「深度扫描」或手动选择文件夹"
                    } else {
                        "没找到 ncm。网易云的歌曲通常在 Android/data 下，" +
                            "授予「所有文件访问权限」后才能扫到，也可以在设置里手动选择文件夹"
                    },
                )
            } else {
                addSources(files.map { NcmSource.RealFile(it, it.length()) }, "网易云目录")
            }
        }
    }

    fun deepScan() {
        if (requireAllFilesAccess()) return
        viewModelScope.launch {
            _ui.update { it.copy(scanning = true) }
            val files = withContext(Dispatchers.IO) { NcmScanner.deepScan() }
            _ui.update { it.copy(scanning = false) }
            addSources(files.map { NcmSource.RealFile(it, it.length()) }, "深度扫描")
        }
    }

    // ------------------------------------------------------------ 保存位置

    fun buildTarget(uri: Uri): OutputTarget {
        val real = Storage.treeToRealDir(uri)?.takeIf { Storage.canWriteDir(it) }
        return OutputTarget(
            realDir = real,
            // 真实路径不可写时（比如没给全文件访问权限）就退回 SAF 目录树
            treeUri = uri,
            label = real?.absolutePath ?: Storage.treeLabel(uri),
        )
    }

    fun setTarget(target: OutputTarget, rememberAsDefault: Boolean) {
        _ui.update { it.copy(target = target) }
        if (rememberAsDefault) prefs.saveTarget(target)
    }

    fun clearDefaultTarget() {
        prefs.clearTarget()
        _ui.update { it.copy(target = OutputTarget.NONE) }
    }

    fun setAskEveryTime(value: Boolean) {
        prefs.askEveryTime = value
        _ui.update { it.copy(askEveryTime = value) }
    }

    fun setDeleteSource(value: Boolean) {
        prefs.deleteSource = value
        _ui.update { it.copy(deleteSource = value) }
    }

    /** 目标目录此刻是否真的能写（真实路径可写，或者有 SAF 目录树兜底） */
    fun targetIsWritable(): Boolean {
        val target = _ui.value.target
        val realWritable = target.realDir?.let { Storage.canWriteDir(it) } == true
        return realWritable || target.treeUri != null
    }

    /** 开始转换前是否必须先弹目录选择器 */
    fun needsTargetPicker(): Boolean = _ui.value.askEveryTime || !targetIsWritable()

    // -------------------------------------------------------------- 转换

    fun startConversion() {
        val state = _ui.value
        if (state.converting) return
        if (!state.binaryReady) {
            postMessage("原生 ncmdump 程序缺失，请重新安装 APK")
            return
        }
        if (state.items.none { it.status != ConvertStatus.SUCCESS }) {
            postMessage("没有待转换的文件")
            return
        }
        if (!targetIsWritable()) {
            postMessage("请先选择保存位置")
            return
        }
        execute(state.target)
    }

    fun cancelConversion() {
        if (!_ui.value.converting) return
        cancelRequested = true
        runner.cancel()
    }

    private fun execute(target: OutputTarget) {
        cancelRequested = false
        viewModelScope.launch {
            _ui.update { it.copy(converting = true) }
            val queue = _ui.value.items.filter { it.status != ConvertStatus.SUCCESS }
            val deleteSource = _ui.value.deleteSource

            for (item in queue) {
                if (cancelRequested) break
                updateItem(item.id) { it.copy(status = ConvertStatus.RUNNING, message = null) }
                _ui.update { it.copy(currentLabel = item.source.displayName) }

                val outcome = converter.convert(item, target, deleteSource)

                if (cancelRequested) {
                    updateItem(item.id) { it.copy(status = ConvertStatus.PENDING, message = "已取消") }
                    break
                }
                updateItem(item.id) {
                    if (outcome.success) {
                        it.copy(
                            status = ConvertStatus.SUCCESS,
                            outputName = outcome.outputName,
                            message = null,
                        )
                    } else {
                        it.copy(status = ConvertStatus.FAILED, message = outcome.message)
                    }
                }
            }

            converter.cleanup()
            _ui.update { state ->
                state.copy(
                    converting = false,
                    currentLabel = null,
                    message = if (cancelRequested) {
                        "已取消：成功 ${state.successCount} 个，失败 ${state.failedCount} 个"
                    } else {
                        "转换结束：成功 ${state.successCount} 个，失败 ${state.failedCount} 个"
                    },
                )
            }
        }
    }

    // -------------------------------------------------------------- 杂项

    fun postMessage(text: String) {
        _ui.update { it.copy(message = text) }
    }

    fun consumeMessage() {
        _ui.update { it.copy(message = null) }
    }

    private fun updateItem(id: String, transform: (NcmItem) -> NcmItem) {
        _ui.update { state ->
            state.copy(items = state.items.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun queryDocument(uri: Uri): Pair<String, Long>? = runCatching {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        getApplication<Application>().contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0) ?: uri.lastPathSegment.orEmpty()
            val size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
            name to size
        }
    }.getOrNull()

    private fun documentLabel(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return ""
        val parent = docId.substringBeforeLast('/', "")
        return if (parent.isEmpty()) docId.replace(':', '/') else "/" + parent.replace(':', '/')
    }
}

private val NcmSource.key: String
    get() = when (this) {
        is NcmSource.RealFile -> "file:${file.absolutePath}"
        is NcmSource.Document -> "doc:$uri"
    }
