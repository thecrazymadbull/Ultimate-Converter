package com.ultimateconverter.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.IOException
import java.io.OutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Video -> animated GIF: 10 pictures per second, at most 30 seconds. */
object GifVideo {
    private const val FPS = 10
    private const val MAX_FRAMES = 300

    /** Returns a short note for the user when the video had to be cut, else null. */
    suspend fun write(ctx: Context, src: Uri, out: OutputStream, maxSide: Int, onProgress: (Float) -> Unit): String? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, src)
            val rawW = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawH = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (rawW <= 0 || rawH <= 0 || durationMs <= 0) throw IOException("Couldn't read this video")

            val sideways = rotation == 90 || rotation == 270
            val shownW = if (sideways) rawH else rawW
            val shownH = if (sideways) rawW else rawH
            val scale = minOf(1f, maxSide.toFloat() / maxOf(shownW, shownH))
            val w = maxOf(2, (shownW * scale).roundToInt())
            val h = maxOf(2, (shownH * scale).roundToInt())

            val wanted = maxOf(1, (durationMs * FPS / 1000).toInt())
            val frames = minOf(wanted, MAX_FRAMES)
            val gif = GifWriter(out, w, h, true)
            val pixels = IntArray(w * h)
            var written = 0
            for (i in 0 until frames) {
                currentCoroutineContext().ensureActive()
                val timeUs = i * 1_000_000L / FPS
                val frame: Bitmap = r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                    ?: continue
                val fitted = if (frame.width == w && frame.height == h) frame
                else Bitmap.createScaledBitmap(frame, w, h, true)
                fitted.getPixels(pixels, 0, w, 0, 0, w, h)
                if (fitted !== frame) fitted.recycle()
                frame.recycle()
                gif.addFrame(pixels, 1000 / FPS, false)
                written++
                onProgress((i + 1f) / frames)
            }
            if (written == 0) throw IOException("Couldn't read any pictures from this video")
            gif.finish()
            return if (wanted > frames) "used the first ${MAX_FRAMES / FPS} seconds" else null
        } finally {
            try { r.release() } catch (ignored: Exception) {}
        }
    }
}
