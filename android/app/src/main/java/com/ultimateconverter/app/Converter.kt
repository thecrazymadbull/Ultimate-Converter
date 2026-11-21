package com.ultimateconverter.app

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class BatchResult(val ok: Int, val failed: Int, val skipped: Int, val cancelled: Boolean)

/** "This file can't go to that format" - shown as Skipped, not as a failure. */
class UnsupportedConversion(message: String) : Exception(message)

object Converter {
    private const val PDF_MAX_SIDE = 5000 // px; keeps a poster-sized page from eating all memory

    suspend fun runBatch(
        ctx: Context,
        jobs: List<FileJob>,
        o: Options,
        sink: OutputSink,
        onProgress: (Float) -> Unit,
    ): BatchResult {
        var ok = 0
        var failed = 0
        var skipped = 0
        var doneCount = 0
        val total = maxOf(1, jobs.size)
        fun report(frac: Float) = onProgress(((doneCount + frac) / total).coerceIn(0f, 1f))

        try {
            if (o.target == Target.PDF && o.combinePdf) {
                val images = jobs.filter { it.kind == Kind.IMAGE }
                for (j in jobs) {
                    if (j.kind != Kind.IMAGE) {
                        j.state = JobState.SKIPPED
                        j.message = "Only photos can go into a PDF"
                        skipped++
                    }
                }
                if (images.isNotEmpty()) {
                    images.forEach { it.state = JobState.CONVERTING }
                    try {
                        val name = safeFileName(o.pdfName) + ".pdf"
                        val uri = writePdfFile(ctx, images, name, o, sink) { f -> onProgress(f) }
                        for (j in images) {
                            j.outputs = listOf(uri)
                            j.state = JobState.DONE
                        }
                        ok += images.size
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        for (j in images) {
                            j.state = JobState.ERROR
                            j.message = friendly(e)
                        }
                        failed += images.size
                    }
                }
                onProgress(1f)
                return BatchResult(ok, failed, skipped, false)
            }

            for (job in jobs) {
                currentCoroutineContext().ensureActive()
                job.state = JobState.CONVERTING
                try {
                    job.outputs = convertOne(ctx, job, o, sink) { f -> report(f) }
                    job.state = JobState.DONE
                    ok++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnsupportedConversion) {
                    job.state = JobState.SKIPPED
                    job.message = e.message ?: "Can't convert this"
                    skipped++
                } catch (e: Throwable) {
                    job.state = JobState.ERROR
                    job.message = friendly(e)
                    failed++
                }
                doneCount++
                report(0f)
            }
            return BatchResult(ok, failed, skipped, false)
        } catch (e: CancellationException) {
            for (j in jobs) {
                if (j.state == JobState.CONVERTING || j.state == JobState.QUEUED) j.state = JobState.CANCELLED
            }
            return BatchResult(ok, failed, skipped, true)
        }
    }

    fun friendly(e: Throwable): String = when {
        e is OutOfMemoryError -> "Not enough memory for this file - try a smaller one"
        e.javaClass.simpleName == "ExportException" ->
            "This phone couldn't convert that file (unsupported format?)"
        e is UnsatisfiedLinkError || e is ExceptionInInitializerError ->
            "This format isn't supported on this phone"
        e is SecurityException -> e.message ?: "Permission problem"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private suspend fun convertOne(
        ctx: Context,
        job: FileJob,
        o: Options,
        sink: OutputSink,
        progress: (Float) -> Unit,
    ): List<Uri> {
        val t = o.target
        if (job.kind !in t.accepts) {
            throw UnsupportedConversion(
                when {
                    job.kind == Kind.PDF && t == Target.PDF -> "Already a PDF"
                    job.kind == Kind.VIDEO -> "Videos can become MP4, MOV, GIF or audio"
                    job.kind == Kind.AUDIO -> "Sound files can only become audio (M4A or WAV)"
                    job.kind == Kind.PDF -> "A PDF can only become pictures"
                    else -> "Photos can't become ${t.label}"
                },
            )
        }
        return when (job.kind) {
            Kind.IMAGE ->
                if (t == Target.PDF) {
                    listOf(writePdfFile(ctx, listOf(job), stemOf(job.name) + ".pdf", o, sink, progress))
                } else {
                    listOf(convertImage(ctx, job, o, sink))
                }
            Kind.PDF -> pdfToImages(ctx, job, o, sink, progress)
            Kind.VIDEO, Kind.AUDIO -> listOf(convertVideo(ctx, job, o, sink, progress))
        }
    }

    // ------------------------------------------------------------ photo -> photo
    private fun convertImage(ctx: Context, job: FileJob, o: Options, sink: OutputSink): Uri {
        val bmp = MediaIO.rotate(MediaIO.decodeImage(ctx, job.uri, null).bitmap, job.rotation)
        val uri = sink.create(stemOf(job.name) + "." + o.target.ext, o.target.mime, OutCat.IMAGE, null)
        try {
            sink.openStream(uri).use { out -> ImageEngine.encode(bmp, o.target, o.quality, o.lossless, out) }
            sink.finish(uri)
        } catch (e: Throwable) {
            sink.abort(uri)
            throw e
        }
        return uri
    }

    // ------------------------------------------------------------ photos -> PDF
    private suspend fun writePdfFile(
        ctx: Context,
        items: List<FileJob>,
        fileName: String,
        o: Options,
        sink: OutputSink,
        progress: (Float) -> Unit,
    ): Uri {
        val uri = sink.create(fileName, "application/pdf", OutCat.DOC, null)
        try {
            sink.openStream(uri).use { out ->
                ImageEngine.writePdf(ctx, items, out, o.pageSize, o.lossless, o.quality, progress)
            }
            sink.finish(uri)
        } catch (e: Throwable) {
            sink.abort(uri)
            throw e
        }
        return uri
    }

    // ------------------------------------------------------------ PDF -> photos
    private suspend fun pdfToImages(
        ctx: Context,
        job: FileJob,
        o: Options,
        sink: OutputSink,
        progress: (Float) -> Unit,
    ): List<Uri> {
        return MediaIO.openPdf(ctx, job.uri).use { handle ->
            val n = handle.pageCount
            if (n == 0) throw IOException("This PDF has no pages")
            val stem = stemOf(job.name)
            val sub = if (n > 1) safeFileName(stem, "pdf") else null
            val created = ArrayList<Uri>()
            try {
                for (i in 0 until n) {
                    currentCoroutineContext().ensureActive()
                    val bmp = MediaIO.renderPdfPage(handle, i, o.dpi / 72f, PDF_MAX_SIDE)
                    val name = pageFileName(stem, o.target.ext, i, n)
                    val uri = sink.create(name, o.target.mime, OutCat.IMAGE, sub)
                    try {
                        sink.openStream(uri).use { out ->
                            ImageEngine.encode(bmp, o.target, o.quality, o.lossless, out)
                        }
                        sink.finish(uri)
                    } catch (e: Throwable) {
                        sink.abort(uri)
                        throw e
                    }
                    created.add(uri)
                    progress((i + 1f) / n)
                }
            } catch (e: Throwable) {
                for (u in created) sink.abort(u) // never leave half a document behind
                throw e
            }
            created
        }
    }

    // ------------------------------------------------------------ video / audio
    private suspend fun convertVideo(
        ctx: Context,
        job: FileJob,
        o: Options,
        sink: OutputSink,
        progress: (Float) -> Unit,
    ): Uri {
        val t = o.target
        if (t == Target.GIF) return videoToGif(ctx, job, o, sink, progress)

        // MOV is the same H.264 video in a QuickTime-style name; the phone writes MP4 first
        val workExt = if (t == Target.MOV) "mp4" else t.ext
        val tmp = File(ctx.cacheDir, "convert_${job.id}.$workExt")
        try {
            if (t == Target.WAV) {
                AudioEngine.toWav(ctx, job.uri, tmp, progress)
            } else {
                val audioOnly = t == Target.M4A
                val bitrate = if (audioOnly) null else VideoEngine.pickBitrate(ctx, job.uri, o.quality, o.lossless)
                VideoEngine.convert(ctx, job.uri, tmp, audioOnly, bitrate, progress)
            }
            val uri = sink.create(stemOf(job.name) + "." + t.ext, t.mime, t.cat, null)
            try {
                tmp.inputStream().use { input -> sink.openStream(uri).use { out -> input.copyTo(out) } }
                sink.finish(uri)
            } catch (e: Throwable) {
                sink.abort(uri)
                throw e
            }
            return uri
        } finally {
            tmp.delete()
        }
    }

    private suspend fun videoToGif(
        ctx: Context,
        job: FileJob,
        o: Options,
        sink: OutputSink,
        progress: (Float) -> Unit,
    ): Uri {
        val uri = sink.create(stemOf(job.name) + ".gif", "image/gif", OutCat.IMAGE, null)
        try {
            var note: String? = null
            sink.openStream(uri).use { raw ->
                val out = BufferedOutputStream(raw, 1 shl 16)
                note = GifVideo.write(ctx, job.uri, out, o.gifSize.maxSide, progress)
                out.flush()
            }
            sink.finish(uri)
            note?.let { job.message = it }
        } catch (e: Throwable) {
            sink.abort(uri)
            throw e
        }
        return uri
    }
}
