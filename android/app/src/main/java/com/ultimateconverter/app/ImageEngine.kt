package com.ultimateconverter.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Build
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
            else -> throw IllegalArgumentException("${target.label} is not a picture format")
        }
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
