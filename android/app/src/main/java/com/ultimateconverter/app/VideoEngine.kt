@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ultimateconverter.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Video -> MP4 (H.264) or audio-only, using the phone's own hardware codecs. */
object VideoEngine {

    fun pickBitrate(ctx: Context, src: Uri, quality: Int, highest: Boolean): Int {
        val f = MediaIO.videoFacts(ctx, src)
        return videoBitrate(f.width, f.height, f.fps, quality, highest)
    }

    /** Converts [src] into [dst]. Progress is 0..1. Cancelling the coroutine stops the export. */
    suspend fun convert(
        ctx: Context,
        src: Uri,
        dst: File,
        audioOnly: Boolean,
        bitrate: Int?,
        onProgress: (Float) -> Unit,
    ) {
        if (dst.exists()) dst.delete()
        // Transformer must be created and used on one thread with a Looper: the main thread.
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()

                val builder = Transformer.Builder(ctx)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                if (!audioOnly) {
                    builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                    if (bitrate != null) {
                        builder.setEncoderFactory(
                            DefaultEncoderFactory.Builder(ctx)
                                .setRequestedVideoEncoderSettings(
                                    VideoEncoderSettings.Builder().setBitrate(bitrate).build(),
                                )
                                .build(),
                        )
                    }
                }

                lateinit var poll: Runnable
                val transformer = builder
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                poll = Runnable {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    if (cont.isActive) handler.postDelayed(poll, 300)
                }

                val item = EditedMediaItem.Builder(MediaItem.fromUri(src))
                    .setRemoveVideo(audioOnly)
                    .build()
                transformer.start(item, dst.absolutePath)
                handler.post(poll)

                cont.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacks(poll)
                        transformer.cancel()
                    }
                }
            }
        }
    }
}
