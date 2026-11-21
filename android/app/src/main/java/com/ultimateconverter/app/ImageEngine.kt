package com.ultimateconverter.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Build
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

object ImageEngine {

    /** Writes [src] to [out] in a picture format (PNG/JPEG/WEBP/BMP). */
    fun encode(src: Bitmap, target: Target, quality: Int, lossless: Boolean, out: OutputStream) {
        val q = quality.coerceIn(1, 100)
        when (target) {
            Target.PNG -> requireOk(src.compress(Bitmap.CompressFormat.PNG, 100, out))
            Target.JPEG -> {
                val flat = MediaIO.flattenOnWhite(src)
                requireOk(flat.compress(Bitmap.CompressFormat.JPEG, if (lossless) 100 else q, out))
            }
            Target.WEBP -> {
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (lossless) src.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                    else src.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, out)
                } else {
                    @Suppress("DEPRECATION")
                    src.compress(Bitmap.CompressFormat.WEBP, if (lossless) 100 else q, out)
                }
                requireOk(ok)
            }
            Target.BMP -> {
                val flat = MediaIO.flattenOnWhite(src)
                writeBmp24(flat.width, flat.height, out) { y, row ->
                    flat.getPixels(row, 0, flat.width, 0, y, flat.width, 1)
                }
            }
            Target.AVIF -> {
                val argb = if (src.config == Bitmap.Config.ARGB_8888) src else (src.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("Not enough memory for this picture"))
                out.write(AvifEncoder.encode(argb))
            }
            Target.TIFF -> {
                val buffered = BufferedOutputStream(out, 1 shl 16)
                writeTiff(src.width, src.height, buffered) { y, row ->
                    src.getPixels(row, 0, src.width, 0, y, src.width, 1)
                }
                buffered.flush()
            }
            Target.GIF -> {
                val small = fitWithin(src, GIF_STILL_MAX_SIDE)
                val w = small.width
                val h = small.height
                val pixels = IntArray(w * h)
                small.getPixels(pixels, 0, w, 0, 0, w, h)
                val buffered = BufferedOutputStream(out, 1 shl 16)
                val gif = GifWriter(buffered, w, h, false)
                gif.addFrame(pixels, 100, true)
                gif.finish()
                buffered.flush()
            }
            Target.ICO -> writeIcon(src, out)
            else -> throw IllegalArgumentException("${target.label} is not a picture format")
        }
    }

    private const val GIF_STILL_MAX_SIDE = 4096
    private val ICON_SIZES = intArrayOf(16, 32, 48, 64, 128, 256)

    /** Shrinks [src] so its longest side is at most [maxSide] (never enlarges). */
    fun fitWithin(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val s = maxSide.toFloat() / longest
        return smoothScale(src, maxOf(1, (src.width * s).roundToInt()), maxOf(1, (src.height * s).roundToInt()))
    }

    /** Resizes in halving steps so big photos shrink without ugly jagged edges. */
    fun smoothScale(src: Bitmap, w: Int, h: Int): Bitmap {
        var cur = src
        while (cur.width > w * 2 || cur.height > h * 2) {
            cur = Bitmap.createScaledBitmap(cur, maxOf(w, cur.width / 2), maxOf(h, cur.height / 2), true)
        }
        return if (cur.width == w && cur.height == h) cur else Bitmap.createScaledBitmap(cur, w, h, true)
    }

    /** Windows icon with 16 to 256 px versions of the picture (kept in proportion on a see-through square). */
    private fun writeIcon(src: Bitmap, out: OutputStream) {
        val pngs = ArrayList<Pair<Int, ByteArray>>()
        for (size in ICON_SIZES) {
            val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height)
            val w = maxOf(1, (src.width * scale).roundToInt())
            val h = maxOf(1, (src.height * scale).roundToInt())
            val scaled = smoothScale(src, w, h)
            val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(square).drawBitmap(scaled, (size - w) / 2f, (size - h) / 2f, null)
            val bytes = ByteArrayOutputStream()
            requireOk(square.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            pngs.add(Pair(size, bytes.toByteArray()))
        }
        writeIco(pngs, out)
    }

    private fun requireOk(ok: Boolean) {
        if (!ok) throw IOException("The picture couldn't be saved in this format")
    }

    /**
     * Makes a PDF from [items] (one page each, in list order).
     * Compressed mode shrinks very large pictures first so the PDF stays small.
     */
    suspend fun writePdf(
        ctx: Context,
        items: List<FileJob>,
        out: OutputStream,
        pageSize: PageSize,
        lossless: Boolean,
        quality: Int,
        onProgress: (Float) -> Unit,
    ) {
        val doc = PdfDocument()
        try {
            val maxSide: Int? = if (lossless) null else 800 + quality.coerceIn(1, 100) * 20
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for ((i, job) in items.withIndex()) {
                currentCoroutineContext().ensureActive()
                val bmp = MediaIO.rotate(MediaIO.decodeImage(ctx, job.uri, maxSide).bitmap, job.rotation)

                var pw: Int
                var ph: Int
                when (pageSize) {
                    PageSize.MATCH -> {
                        // treat the picture as 150 dpi: 1 PDF point = 1/72 inch
                        pw = maxOf(1, (bmp.width * 72f / 150f).roundToInt())
                        ph = maxOf(1, (bmp.height * 72f / 150f).roundToInt())
                    }
                    PageSize.A4 -> { pw = 595; ph = 842 }
                    PageSize.LETTER -> { pw = 612; ph = 792 }
                }
                if (pageSize != PageSize.MATCH && bmp.width > bmp.height) {
                    val t = pw; pw = ph; ph = t // landscape picture -> landscape page
                }
                val margin = if (pageSize == PageSize.MATCH) 0f else 28f
                val scale = minOf((pw - 2 * margin) / bmp.width, (ph - 2 * margin) / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                val left = (pw - dw) / 2f
                val top = (ph - dh) / 2f

                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                page.canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), paint)
                doc.finishPage(page)
                onProgress((i + 1f) / items.size)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
