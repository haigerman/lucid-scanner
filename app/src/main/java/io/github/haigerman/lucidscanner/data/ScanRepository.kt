package io.github.haigerman.lucidscanner.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import io.github.haigerman.lucidscanner.cloud.OpenCloudClient
import io.github.haigerman.lucidscanner.pdf.PdfWriter
import io.github.haigerman.lucidscanner.scan.PageProcessor
import io.github.haigerman.lucidscanner.scan.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Eine fertig aufbereitete Seite (JPEG-Datei). */
data class ScanPage(val id: Long, val file: File)

data class ScanState(
    val pages: List<ScanPage> = emptyList(),
    /** Aufnahmen, die noch aufbereitet werden. */
    val processing: Int = 0,
    /** Aufnahmen, die sich nicht verarbeiten ließen. */
    val failed: Int = 0,
)

/** Das fertige PDF, wie es zur Kontrolle gezeigt und danach abgelegt wird. */
data class PdfResult(val file: File, val defaultName: String, val pageCount: Int)

data class HistoryEntry(val name: String, val pages: Int, val time: Long, val destination: String)

/** Der laufende Scan: Seiten sammeln, PDF bauen, in OpenCloud ablegen. Gemeinsamer Zustand aller Bildschirme. */
object ScanRepository {
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private val worker = Executors.newSingleThreadExecutor()
    private var nextId = 1L

    /** Zählt hoch, wenn ein Scan verworfen wird – Aufnahmen aus einem alten Scan werden dann ignoriert. */
    @Volatile private var generation = 0

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    private val _pdf = MutableStateFlow<PdfResult?>(null)
    val pdf: StateFlow<PdfResult?> = _pdf

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.applicationContext.cacheDir, "scan")
        prefs = context.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)
        _history.value = loadHistory()
        clearFiles() // Reste eines abgebrochenen Scans
    }

    /** Beginnt einen neuen, leeren Scan. */
    fun start() {
        generation++
        clearFiles()
        _state.value = ScanState()
        _pdf.value = null
    }

    fun discard() = start()

    /** Nimmt ein Kamerafoto an und bereitet es im Hintergrund zur Seite auf. */
    fun addCapture(jpeg: ByteArray, rotation: Int, crop: Rect?, hint: Quad?) {
        val gen = generation
        val id = nextId++
        val enhance = Settings.config.value.enhance
        _state.update { it.copy(processing = it.processing + 1) }
        worker.execute {
            val file = File(dir, "page-$id.jpg")
            val ok = try {
                dir.mkdirs()
                PageProcessor.process(jpeg, rotation, crop, hint, enhance, file)
                true
            } catch (_: Throwable) { // auch OutOfMemoryError bei sehr großen Fotos
                file.delete()
                false
            }
            if (gen != generation) {
                file.delete()
                return@execute
            }
            _state.update {
                if (ok) it.copy(pages = it.pages + ScanPage(id, file), processing = it.processing - 1)
                else it.copy(processing = it.processing - 1, failed = it.failed + 1)
            }
        }
    }

    fun removeLastPage() {
        _state.update { s ->
            val last = s.pages.lastOrNull() ?: return
            last.file.delete()
            s.copy(pages = s.pages.dropLast(1))
        }
    }

    /** Wartet, bis alle Aufnahmen aufbereitet sind, und schreibt dann das PDF. */
    suspend fun buildPdf(): PdfResult {
        val pages = state.first { it.processing == 0 }.pages
        check(pages.isNotEmpty()) { "Keine Seiten" }
        val now = Date()
        val name = "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(now)
        val result = withContext(Dispatchers.IO) {
            val file = File(dir, "scan.pdf")
            file.outputStream().buffered().use { PdfWriter.write(pages.map { it.file }, it, name, now) }
            PdfResult(file, name, pages.size)
        }
        _pdf.value = result
        return result
    }

    /** Legt das PDF im Ordner [folder] der OpenCloud ab. Wirft [io.github.haigerman.lucidscanner.cloud.OpenCloudException] mit verständlicher Meldung. */
    suspend fun upload(name: String, folder: String, onProgress: (Float) -> Unit): String {
        val result = checkNotNull(_pdf.value)
        val config = Settings.config.value
        val fileName = OpenCloudClient.pdfFileName(name, result.defaultName)
        val remote = withContext(Dispatchers.IO) {
            OpenCloudClient(config.serverUrl, config.user, config.token).upload(folder, fileName, result.file, onProgress)
        }
        val entry = HistoryEntry(fileName, result.pageCount, System.currentTimeMillis(), "${config.host} › ${folderLabel(folder)}")
        _history.update { (listOf(entry) + it).take(20) }
        saveHistory()
        start()
        return remote
    }

    private fun clearFiles() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun loadHistory(): List<HistoryEntry> = try {
        val arr = JSONArray(prefs.getString("entries", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryEntry(o.getString("name"), o.getInt("pages"), o.getLong("time"), o.optString("destination"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveHistory() {
        val arr = JSONArray()
        for (e in _history.value) {
            arr.put(JSONObject().put("name", e.name).put("pages", e.pages).put("time", e.time).put("destination", e.destination))
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }
}
