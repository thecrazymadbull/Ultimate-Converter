package com.ultimateconverter.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Any sound (or the sound of a video) -> uncompressed WAV, decoded with the phone's own codecs. */
object AudioEngine {

    suspend fun toWav(ctx: Context, src: Uri, dst: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var raf: RandomAccessFile? = null
            try {
                extractor.setDataSource(ctx, src, null)
                var track = -1
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) { track = i; break }
                }
                if (track < 0) throw IOException("This file has no sound")
                extractor.selectTrack(track)
                val inFormat = extractor.getTrackFormat(track)
                val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: throw IOException("Unknown sound format")

                var sampleRate = if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                var channels = if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

                val dec = MediaCodec.createDecoderByType(mime)
                codec = dec
                dec.configure(inFormat, null, null, 0)
                dec.start()

                if (dst.exists()) dst.delete()
                val file = RandomAccessFile(dst, "rw")
                raf = file
                file.write(ByteArray(44)) // header goes in at the end, when the length is known

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var dataBytes = 0L
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val ii = dec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val buf = dec.getInputBuffer(ii) ?: throw IOException("Audio decoder problem")
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val oi = dec.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else AudioFormat.ENCODING_PCM_16BIT
                    } else if (oi >= 0) {
                        val ob = dec.getOutputBuffer(oi)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            dataBytes += writePcm(ob, encoding, file)
                        }
                        dec.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                }
                if (dataBytes == 0L) throw IOException("No sound could be read from this file")
                file.seek(0)
                file.write(wavHeader(dataBytes, sampleRate, channels))
            } finally {
                try { codec?.stop() } catch (ignored: Exception) {}
                try { codec?.release() } catch (ignored: Exception) {}
                try { extractor.release() } catch (ignored: Exception) {}
                try { raf?.close() } catch (ignored: Exception) {}
            }
        }
    }

    /** Writes the samples as 16-bit and returns how many bytes were written. */
    private fun writePcm(buf: ByteBuffer, encoding: Int, file: RandomAccessFile): Long {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buf.asFloatBuffer()
                val n = floats.remaining()
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = (floats.get().coerceIn(-1f, 1f) * 32767f).roundToInt()
                    bytes[2 * i] = (v and 0xFF).toByte()
                    bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                }
                file.write(bytes)
                return bytes.size.toLong()
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val n = buf.remaining()
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = ((buf.get().toInt() and 0xFF) - 128) shl 8
                    bytes[2 * i] = (v and 0xFF).toByte()
                    bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                }
                file.write(bytes)
                return bytes.size.toLong()
            }
            else -> {
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                file.write(bytes)
                return bytes.size.toLong()
            }
        }
    }
}
