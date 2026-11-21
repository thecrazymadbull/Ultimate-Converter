package com.ultimateconverter.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ConverterViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val jobs = mutableStateListOf<FileJob>()

    var target by mutableStateOf(Target.PNG)
    var quality by mutableIntStateOf(85)
    var lossless by mutableStateOf(false)
    var combinePdf by mutableStateOf(true)
    var pdfName by mutableStateOf("combined")
    var pageSize by mutableStateOf(PageSize.MATCH)
    var dpi by mutableIntStateOf(200)
    var outputTree by mutableStateOf<Uri?>(null)

    var converting by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var statusText by mutableStateOf("")

    private var nextId = 1L
    private var runJob: Job? = null
    private val thumbGate = Semaphore(2) // decode at most two pictures at once (memory)

    init {
        loadSettings()
    }

    // ------------------------------------------------------------------ targets
    /** Formats that make sense for what is currently in the list. */
    val availableTargets: List<Target>
        get() {
            val kinds = jobs.map { it.kind }.toSet()
            if (kinds.isEmpty()) return Target.values().filter { Kind.IMAGE in it.accepts }
            return Target.values().filter { t -> kinds.any { it in t.accepts } }
        }

    private fun ensureTarget() {
        val available = availableTargets
        if (target !in available) target = available.first()
    }

    val hasPdfInput: Boolean get() = jobs.any { it.kind == Kind.PDF }
    val hasImageInput: Boolean get() = jobs.any { it.kind == Kind.IMAGE }

    /** The ▲▼ buttons only matter when photos are being merged into one PDF. */
    val reorderable: Boolean get() = target == Target.PDF && combinePdf

    // ------------------------------------------------------------------ adding files
    fun addUris(uris: List<Uri>) {
        if (converting) return
        val known = jobs.map { it.uri }.toSet()
        val fresh = ArrayList<FileJob>()
        var unsupported = 0
        for (u in uris.distinct()) {
            if (u in known) continue
            val meta = MediaIO.queryMeta(ctx, u)
            val kind = MediaIO.classify(meta.mime, meta.name)
            if (kind == null) {
                unsupported++
                continue
            }
            fresh.add(FileJob(nextId++, u, meta.name, kind, meta.size))
        }
        fresh.sortWith { a, b -> naturalCompare(a.name, b.name) }
        jobs.addAll(fresh)
        ensureTarget()
        fresh.forEach { loadThumb(it) }
        statusText = if (unsupported > 0) {
            "$unsupported file${if (unsupported == 1) "" else "s"} skipped (not a photo, video or PDF)"
        } else {
            ""
        }
    }

    private fun loadThumb(job: FileJob) {
        viewModelScope.launch(Dispatchers.IO) {
            thumbGate.withPermit {
                val (bmp, info) = MediaIO.thumbnail(ctx, job, 256)
                job.thumbBase = bmp
                job.info = listOf(info, humanSize(job.sizeBytes)).filter { it.isNotEmpty() }.joinToString(" · ")
            }
        }
    }

    suspend fun loadBigPreview(job: FileJob) = MediaIO.bigPreview(ctx, job)

    // ------------------------------------------------------------------ editing the list
    fun remove(job: FileJob) {
        if (converting) return
        jobs.remove(job)
        ensureTarget()
    }

    fun clearAll() {
        if (converting) return
        jobs.clear()
        statusText = ""
        ensureTarget()
    }

    fun rotate(job: FileJob, deltaDegrees: Int) {
        if (converting || job.kind != Kind.IMAGE) return
        job.rotation = (((job.rotation + deltaDegrees) % 360) + 360) % 360
    }

    fun move(job: FileJob, direction: Int) {
        if (converting) return
        val i = jobs.indexOf(job)
        val j = i + direction
        if (i < 0 || j < 0 || j >= jobs.size) return
        jobs.removeAt(i)
        jobs.add(j, job)
    }

    // ------------------------------------------------------------------ output folder
    @JvmName("applyOutputTree")
    fun setOutputTree(uri: Uri?) {
        outputTree = uri
        saveSettings()
    }

    val outputLabel: String
        get() {
            val tree = outputTree
            return if (tree != null) treeLabel(tree) else defaultFolderLabel(target.cat)
        }

    // ------------------------------------------------------------------ converting
    fun start() {
        if (converting || jobs.isEmpty()) return
        saveSettings()
        val options = Options(target, quality, lossless, combinePdf, pdfName, pageSize, dpi)
        val list = jobs.toList()
        for (j in list) {
            j.state = JobState.QUEUED
            j.message = ""
            j.outputs = emptyList()
        }
        val sink: OutputSink = outputTree.let { if (it != null) TreeSink(ctx, it) else MediaStoreSink(ctx) }
        converting = true
        progress = 0f
        statusText = "Converting…"
        runJob = viewModelScope.launch(Dispatchers.Default) {
            val result = try {
                Converter.runBatch(ctx, list, options, sink) { progress = it }
            } catch (e: Throwable) {
                statusText = "Stopped: ${Converter.friendly(e)}"
                converting = false
                return@launch
            }
            statusText = when {
                result.cancelled -> "Cancelled"
                else -> buildString {
                    append("Done · ${result.ok} converted")
                    if (result.failed > 0) append(" · ${result.failed} failed")
                    if (result.skipped > 0) append(" · ${result.skipped} skipped")
                    if (result.ok > 0) append("\nSaved in $outputLabel")
                }
            }
            progress = if (result.cancelled) progress else 1f
            converting = false
        }
    }

    fun cancel() {
        runJob?.cancel()
    }

    // ------------------------------------------------------------------ remembered settings
    private fun loadSettings() {
        try {
            prefs.getString("target", null)?.let { n -> Target.values().firstOrNull { it.name == n }?.let { target = it } }
            quality = prefs.getInt("quality", 85).coerceIn(1, 100)
            lossless = prefs.getBoolean("lossless", false)
            combinePdf = prefs.getBoolean("combine", true)
            pdfName = prefs.getString("pdfName", "combined") ?: "combined"
            prefs.getString("pageSize", null)?.let { n -> PageSize.values().firstOrNull { it.name == n }?.let { pageSize = it } }
            dpi = prefs.getInt("dpi", 200).takeIf { it in DPI_CHOICES } ?: 200
            val saved = prefs.getString("tree", null)
            if (saved != null) {
                val uri = Uri.parse(saved)
                val stillAllowed = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
                if (stillAllowed) outputTree = uri
            }
        } catch (e: Exception) {
            // a damaged settings file must never stop the app from starting
        }
    }

    private fun saveSettings() {
        try {
            prefs.edit()
                .putString("target", target.name)
                .putInt("quality", quality)
                .putBoolean("lossless", lossless)
                .putBoolean("combine", combinePdf)
                .putString("pdfName", pdfName)
                .putString("pageSize", pageSize.name)
                .putInt("dpi", dpi)
                .putString("tree", outputTree?.toString())
                .apply()
        } catch (e: Exception) {
        }
    }
}
