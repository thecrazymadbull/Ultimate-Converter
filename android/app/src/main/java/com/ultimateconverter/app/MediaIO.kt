package com.ultimateconverter.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

class FileMeta(val name: String, val size: Long, val mime: String?)

class Decoded(val bitmap: Bitmap, val width: Int, val height: Int)

/** An open PDF. Close it when done. */
class PdfHandle(
    val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor,
    private val temp: File?,
) : Closeable {
    val pageCount: Int get() = renderer.pageCount

    override fun close() {
        try { renderer.close() } catch (ignored: Exception) {}
        try { pfd.close() } catch (ignored: Exception) {}
        temp?.delete()
    }
}

object MediaIO {
    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    private val videoExts = setOf("mp4", "mov", "mkv", "webm", "3gp", "avi", "m4v")

    fun queryMeta(ctx: Context, uri: Uri): FileMeta {
        var name: String? = null
        var size = -1L
        try {
            ctx.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (ignored: Exception) {
        }
        val mime = try { ctx.contentResolver.getType(uri) } catch (ignored: Exception) { null }
        return FileMeta(name ?: uri.lastPathSegment ?: "file", size, mime)
    }

    fun classify(mime: String?, name: String): Kind? {
        val m = mime?.lowercase(Locale.ROOT) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            m == "application/pdf" || ext == "pdf" -> Kind.PDF
            m.startsWith("image/") -> Kind.IMAGE
            m.startsWith("video/") -> Kind.VIDEO
            ext in imageExts -> Kind.IMAGE
            ext in videoExts -> Kind.VIDEO
            else -> null
        }
    }

    /**
     * Decodes a picture the way a gallery shows it: the phone's "rotated" flag is already
     * applied. [maxSide] shrinks big pictures while decoding (for thumbnails / PDFs).
     */
    fun decodeImage(ctx: Context, uri: Uri, maxSide: Int? = null): Decoded {
        var w = 0
        var h = 0
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            w = info.size.width
            h = info.size.height
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            if (maxSide != null) {
                val longest = maxOf(w, h)
                if (longest > maxSide) {
                    val s = maxSide.toFloat() / longest
                    decoder.setTargetSize(
                        maxOf(1, (w * s).roundToInt()),
                        maxOf(1, (h * s).roundToInt()),
                    )
                }
            }
        }
        return Decoded(bmp, w, h)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val m = Matrix()
        m.postRotate(d.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Puts transparent pictures on white paper (JPEG, BMP and PDF have no transparency). */
    fun flattenOnWhite(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    // ---------------------------------------------------------------- PDF input
    fun openPdf(ctx: Context, uri: Uri): PdfHandle {
        fun fromFd(pfd: ParcelFileDescriptor, temp: File?): PdfHandle {
            try {
                return PdfHandle(PdfRenderer(pfd), pfd, temp)
            } catch (e: Throwable) {
                try { pfd.close() } catch (ignored: Exception) {}
                throw e
            }
        }

        val direct: ParcelFileDescriptor? = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")
        } catch (ignored: Exception) {
            null
        }
        if (direct != null) {
            try {
                return fromFd(direct, null)
            } catch (e: SecurityException) {
                throw IOException("This PDF is password-protected")
            } catch (ignored: Exception) {
                // maybe not a seekable file - fall through and try a copy
            }
        }

        val temp = File.createTempFile("pdf", ".pdf", ctx.cacheDir)
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't read the PDF")
            input.use { i -> temp.outputStream().use { o -> i.copyTo(o) } }
            val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            return fromFd(pfd, temp)
        } catch (e: SecurityException) {
            temp.delete()
            throw IOException("This PDF is password-protected")
        } catch (e: IOException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw IOException("Couldn't open this PDF (it may be damaged)")
        }
    }

    /** Draws page [index] on white paper. [scale] = pixels per PDF point (dpi / 72). */
    fun renderPdfPage(handle: PdfHandle, index: Int, scale: Float, maxSide: Int): Bitmap {
        return handle.renderer.openPage(index).use { page ->
            var s = scale
            val longest = maxOf(page.width, page.height)
            if (longest * s > maxSide) s = maxSide.toFloat() / longest
            val w = maxOf(1, (page.width * s).roundToInt())
            val h = maxOf(1, (page.height * s).roundToInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    // ---------------------------------------------------------------- video
    class VideoFacts(val width: Int, val height: Int, val durationMs: Long, val fps: Float)

    fun videoFacts(ctx: Context, uri: Uri): VideoFacts {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val f = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
            return VideoFacts(w, h, d, f)
        } finally {
            try { r.release() } catch (ignored: Exception) {}
        }
    }

    fun videoFrame(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val frame = r.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L) ?: return null
            val longest = maxOf(frame.width, frame.height)
            if (longest <= maxSide) return frame
            val s = maxSide.toFloat() / longest
            return Bitmap.createScaledBitmap(
                frame, maxOf(1, (frame.width * s).roundToInt()), maxOf(1, (frame.height * s).roundToInt()), true,
            )
        } finally {
            try { r.release() } catch (ignored: Exception) {}
        }
    }

    // ---------------------------------------------------------------- previews
    /** Small picture + one line of facts for the list. Never throws. */
    fun thumbnail(ctx: Context, job: FileJob, maxSide: Int): Pair<Bitmap?, String> {
        return try {
            when (job.kind) {
                Kind.IMAGE -> {
                    val d = decodeImage(ctx, job.uri, maxSide)
                    d.bitmap to "${d.width} × ${d.height}"
                }
                Kind.PDF -> openPdf(ctx, job.uri).use { h ->
                    val page = renderPdfPage(h, 0, 1f, maxSide)
                    // (scale 1.0 = 72 dpi; a normal page is ~600 px, then capped at maxSide)
                    page to "${h.pageCount} page${if (h.pageCount == 1) "" else "s"}"
                }
                Kind.VIDEO -> {
                    val f = videoFacts(ctx, job.uri)
                    videoFrame(ctx, job.uri, maxSide) to "${f.width} × ${f.height} · ${formatDuration(f.durationMs)}"
                }
            }
        } catch (ignored: Throwable) {
            null to "No preview"
        }
    }

    /** Bigger picture for the preview dialog (first page for a PDF, a frame for a video). */
    fun bigPreview(ctx: Context, job: FileJob): Bitmap? {
        return try {
            when (job.kind) {
                Kind.IMAGE -> decodeImage(ctx, job.uri, 1400).bitmap
                Kind.PDF -> openPdf(ctx, job.uri).use { h -> renderPdfPage(h, 0, 2f, 1400) }
                Kind.VIDEO -> videoFrame(ctx, job.uri, 1400)
            }
        } catch (ignored: Throwable) {
            null
        }
    }
}
