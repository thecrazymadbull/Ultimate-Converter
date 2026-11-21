package com.ultimateconverter.app

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Pure-Kotlin file writers (no Android classes) so they can be tested on a normal JVM:
 *   TIFF, GIF (still + animated), ICO and the WAV header.
 * Pixels are always 0xAARRGGBB ints, the same as Bitmap.getPixels().
 */

// ---------------------------------------------------------------------------------------------
// TIFF (uncompressed 8-bit RGBA, readable by every picture program)
// ---------------------------------------------------------------------------------------------

/** [fillRow] must fill `row` with the pixels of row [y] counted from the TOP. */
fun writeTiff(width: Int, height: Int, out: OutputStream, fillRow: (y: Int, row: IntArray) -> Unit) {
    val rowsPerStrip = 16
    val strips = (height + rowsPerStrip - 1) / rowsPerStrip
    val entries = 10
    val ifdOffset = 8
    val ifdSize = 2 + entries * 12 + 4
    var extra = ifdOffset + ifdSize // where the out-of-line data starts
    val bitsOffset = extra
    extra += 8
    val offsetsArray = if (strips > 1) extra else 0
    if (strips > 1) extra += 4 * strips
    val countsArray = if (strips > 1) extra else 0
    if (strips > 1) extra += 4 * strips
    val dataStart = extra

    fun stripBytes(i: Int): Int {
        val rows = minOf(rowsPerStrip, height - i * rowsPerStrip)
        return rows * width * 4
    }

    val bb = ByteBuffer.allocate(dataStart).order(ByteOrder.LITTLE_ENDIAN)
    bb.put('I'.code.toByte()); bb.put('I'.code.toByte())
    bb.putShort(42)
    bb.putInt(ifdOffset)
    bb.putShort(entries.toShort())
    fun entry(tag: Int, type: Int, count: Int, value: Int) {
        bb.putShort(tag.toShort()); bb.putShort(type.toShort()); bb.putInt(count)
        if (type == 3 && count == 1) { bb.putShort(value.toShort()); bb.putShort(0) } else bb.putInt(value)
    }
    entry(256, 4, 1, width)
    entry(257, 4, 1, height)
    entry(258, 3, 4, bitsOffset)
    entry(259, 3, 1, 1) // no compression
    entry(262, 3, 1, 2) // RGB
    entry(273, 4, strips, if (strips > 1) offsetsArray else dataStart)
    entry(277, 3, 1, 4)
    entry(278, 4, 1, rowsPerStrip)
    entry(279, 4, strips, if (strips > 1) countsArray else stripBytes(0))
    entry(338, 3, 1, 2) // extra sample = unassociated alpha
    bb.putInt(0) // no further IFD
    for (i in 0 until 4) bb.putShort(8)
    if (strips > 1) {
        var off = dataStart
        for (i in 0 until strips) { bb.putInt(off); off += stripBytes(i) }
        for (i in 0 until strips) bb.putInt(stripBytes(i))
    }
    out.write(bb.array())

    val pixels = IntArray(width)
    val row = ByteArray(width * 4)
    for (y in 0 until height) {
        fillRow(y, pixels)
        var j = 0
        for (x in 0 until width) {
            val p = pixels[x]
            row[j++] = ((p shr 16) and 0xFF).toByte()
            row[j++] = ((p shr 8) and 0xFF).toByte()
            row[j++] = (p and 0xFF).toByte()
            row[j++] = ((p ushr 24) and 0xFF).toByte()
        }
        out.write(row)
    }
}

// ---------------------------------------------------------------------------------------------
// ICO (each size stored as an embedded PNG, which Windows Vista and newer all understand)
// ---------------------------------------------------------------------------------------------

/** [pngs] are (size in pixels, PNG file bytes) for square pictures of 1..256 pixels. */
fun writeIco(pngs: List<Pair<Int, ByteArray>>, out: OutputStream) {
    require(pngs.isNotEmpty()) { "no icon sizes" }
    val bb = ByteBuffer.allocate(6 + 16 * pngs.size).order(ByteOrder.LITTLE_ENDIAN)
    bb.putShort(0); bb.putShort(1); bb.putShort(pngs.size.toShort())
    var offset = 6 + 16 * pngs.size
    for ((size, data) in pngs) {
        val s = if (size >= 256) 0 else size
        bb.put(s.toByte()); bb.put(s.toByte())
        bb.put(0); bb.put(0)
        bb.putShort(1); bb.putShort(32)
        bb.putInt(data.size)
        bb.putInt(offset)
        offset += data.size
    }
    out.write(bb.array())
    for ((_, data) in pngs) out.write(data)
}

// ---------------------------------------------------------------------------------------------
// WAV
// ---------------------------------------------------------------------------------------------

/** 44-byte header for 16-bit PCM audio with [dataBytes] bytes of samples after it. */
fun wavHeader(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
    require(dataBytes in 0..0xFFFFFFD0L) { "WAV files can't be bigger than 4 GB" }
    val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    bb.put("RIFF".toByteArray(Charsets.US_ASCII))
    bb.putInt((dataBytes + 36).toInt())
    bb.put("WAVE".toByteArray(Charsets.US_ASCII))
    bb.put("fmt ".toByteArray(Charsets.US_ASCII))
    bb.putInt(16)
    bb.putShort(1) // PCM
    bb.putShort(channels.toShort())
    bb.putInt(sampleRate)
    bb.putInt(sampleRate * channels * 2)
    bb.putShort((channels * 2).toShort())
    bb.putShort(16)
    bb.put("data".toByteArray(Charsets.US_ASCII))
    bb.putInt(dataBytes.toInt())
    return bb.array()
}

// ---------------------------------------------------------------------------------------------
// GIF
// ---------------------------------------------------------------------------------------------

/** Result of reducing a picture to at most 256 colours. */
class Quantized(
    val palette: ByteArray, // r,g,b triples, size = 3 * (1 shl bits)
    val bits: Int, // 2..8 : palette has 1 shl bits entries
    val indices: ByteArray, // width * height palette indices
    val transparentIndex: Int, // -1 when the picture has no see-through pixels
)

private fun binOf(r: Int, g: Int, b: Int): Int = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)

private fun expand5(v: Int): Int = (v shl 3) or (v shr 2)

/**
 * Median-cut colour reduction with optional Floyd-Steinberg dithering.
 * Pixels with alpha < 128 become the transparent index.
 */
fun quantize(argb: IntArray, width: Int, height: Int, dither: Boolean): Quantized {
    val n = width * height
    val hist = IntArray(32768)
    var hasTransparent = false
    for (i in 0 until n) {
        val p = argb[i]
        if ((p ushr 24) < 128) { hasTransparent = true; continue }
        hist[binOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)]++
    }
    val maxColors = if (hasTransparent) 255 else 256

    // the used bins, in a plain array we can sort ranges of
    var used = 0
    for (c in hist) if (c > 0) used++
    val bins = IntArray(used)
    run {
        var k = 0
        for (b in 0 until 32768) if (hist[b] > 0) bins[k++] = b
    }

    fun chan(bin: Int, ch: Int): Int = when (ch) { 0 -> bin shr 10; 1 -> (bin shr 5) and 31; else -> bin and 31 }

    // boxes are [start, end) ranges of `bins`
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    if (used > 0) { starts.add(0); ends.add(used) }

    fun population(s: Int, e: Int): Long { var t = 0L; for (i in s until e) t += hist[bins[i]]; return t }
    fun widest(s: Int, e: Int): Pair<Int, Int> { // (channel, range)
        var bestCh = 0; var bestRange = -1
        for (ch in 0..2) {
            var lo = 31; var hi = 0
            for (i in s until e) { val v = chan(bins[i], ch); if (v < lo) lo = v; if (v > hi) hi = v }
            if (hi - lo > bestRange) { bestRange = hi - lo; bestCh = ch }
        }
        return Pair(bestCh, bestRange)
    }

    while (starts.size < maxColors) {
        // pick the splittable box with the most pixels
        var pick = -1; var pickPop = -1L
        for (i in starts.indices) {
            if (ends[i] - starts[i] < 2) continue
            val pop = population(starts[i], ends[i])
            if (pop > pickPop) { pickPop = pop; pick = i }
        }
        if (pick < 0) break
        val s = starts[pick]; val e = ends[pick]
        val (ch, _) = widest(s, e)
        val sorted = bins.copyOfRange(s, e).sortedBy { chan(it, ch) }
        for (i in sorted.indices) bins[s + i] = sorted[i]
        // split at the pixel-count median, keeping at least one bin on each side
        val half = pickPop / 2
        var acc = 0L
        var cut = s + 1
        for (i in s until e - 1) {
            acc += hist[bins[i]]
            cut = i + 1
            if (acc >= half) break
        }
        ends[pick] = cut
        starts.add(cut); ends.add(e)
    }

    val colorCount = starts.size
    val totalEntries = colorCount + if (hasTransparent) 1 else 0
    var bits = 2
    while ((1 shl bits) < totalEntries) bits++
    val palette = ByteArray(3 * (1 shl bits))
    val palR = IntArray(colorCount); val palG = IntArray(colorCount); val palB = IntArray(colorCount)
    for (k in 0 until colorCount) {
        var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0L
        for (i in starts[k] until ends[k]) {
            val b = bins[i]; val c = hist[b].toLong()
            sr += expand5(b shr 10) * c; sg += expand5((b shr 5) and 31) * c; sb += expand5(b and 31) * c; cnt += c
        }
        val r = (sr / cnt).toInt(); val g = (sg / cnt).toInt(); val b = (sb / cnt).toInt()
        palR[k] = r; palG[k] = g; palB[k] = b
        palette[3 * k] = r.toByte(); palette[3 * k + 1] = g.toByte(); palette[3 * k + 2] = b.toByte()
    }
    val transIdx = if (hasTransparent) colorCount else -1

    // nearest palette entry for each 15-bit colour, filled in lazily
    val lookup = IntArray(32768) { -1 }
    fun nearest(r: Int, g: Int, b: Int): Int {
        val bin = binOf(r, g, b)
        val cached = lookup[bin]
        if (cached >= 0) return cached
        val rr = expand5(bin shr 10); val gg = expand5((bin shr 5) and 31); val bb = expand5(bin and 31)
        var best = 0; var bestD = Int.MAX_VALUE
        for (k in 0 until colorCount) {
            val dr = palR[k] - rr; val dg = palG[k] - gg; val db = palB[k] - bb
            val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
            if (d < bestD) { bestD = d; best = k }
        }
        lookup[bin] = best
        return best
    }

    val indices = ByteArray(n)
    if (colorCount == 0) { // completely transparent picture
        if (transIdx >= 0) java.util.Arrays.fill(indices, transIdx.toByte())
        return Quantized(palette, bits, indices, transIdx)
    }
    if (!dither) {
        for (i in 0 until n) {
            val p = argb[i]
            indices[i] = if ((p ushr 24) < 128) transIdx.toByte()
            else nearest((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toByte()
        }
    } else {
        var cur = IntArray((width + 2) * 3)
        var nxt = IntArray((width + 2) * 3)
        for (y in 0 until height) {
            java.util.Arrays.fill(nxt, 0)
            for (x in 0 until width) {
                val i = y * width + x
                val p = argb[i]
                if ((p ushr 24) < 128) { indices[i] = transIdx.toByte(); continue }
                val e = (x + 1) * 3
                val r = (((p shr 16) and 0xFF) + cur[e] / 16).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + cur[e + 1] / 16).coerceIn(0, 255)
                val b = ((p and 0xFF) + cur[e + 2] / 16).coerceIn(0, 255)
                val k = nearest(r, g, b)
                indices[i] = k.toByte()
                val er = r - palR[k]; val eg = g - palG[k]; val eb = b - palB[k]
                // 7/16 right, 3/16 down-left, 5/16 down, 1/16 down-right
                cur[e + 3] += er * 7; cur[e + 4] += eg * 7; cur[e + 5] += eb * 7
                nxt[e - 3] += er * 3; nxt[e - 2] += eg * 3; nxt[e - 1] += eb * 3
                nxt[e] += er * 5; nxt[e + 1] += eg * 5; nxt[e + 2] += eb * 5
                nxt[e + 3] += er; nxt[e + 4] += eg; nxt[e + 5] += eb
            }
            val t = cur; cur = nxt; nxt = t
        }
    }
    return Quantized(palette, bits, indices, transIdx)
}

/** GIF's flavour of LZW. Returns the raw code stream (not yet split into 255-byte blocks). */
fun gifLzw(pix: ByteArray, count: Int, minBits: Int): ByteArray {
    val clear = 1 shl minBits
    val eoi = clear + 1
    val bos = ByteArrayOutputStream(count / 2 + 64)
    var acc = 0
    var accBits = 0
    var nBits = minBits + 1
    var maxCode = (1 shl nBits) - 1
    var free = eoi + 1
    val hsize = 16384
    val keys = IntArray(hsize) { -1 }
    val vals = IntArray(hsize)

    fun put(code: Int) {
        acc = acc or (code shl accBits)
        accBits += nBits
        while (accBits >= 8) {
            bos.write(acc and 0xFF)
            acc = acc ushr 8
            accBits -= 8
        }
    }

    fun output(code: Int, resetting: Boolean) {
        put(code)
        if (resetting) {
            nBits = minBits + 1
            maxCode = (1 shl nBits) - 1
        } else if (free > maxCode) {
            nBits++
            maxCode = if (nBits == 12) 4096 else (1 shl nBits) - 1
        }
    }

    // first code is a clear code, written with the starting code size
    put(clear)
    if (count > 0) {
        var prefix = pix[0].toInt() and 0xFF
        for (i in 1 until count) {
            val c = pix[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var h = (key * -1640531535) ushr 18 // 14 bits
            var found = -1
            while (true) {
                val k = keys[h]
                if (k == -1) break
                if (k == key) { found = vals[h]; break }
                h = (h + 1) and (hsize - 1)
            }
            if (found >= 0) {
                prefix = found
            } else {
                output(prefix, false)
                if (free < 4096) {
                    keys[h] = key
                    vals[h] = free
                    free++
                } else {
                    // table full: start over (free was reset inside resetTable)
                    java.util.Arrays.fill(keys, -1)
                    free = eoi + 1
                    output(clear, true)
                }
                prefix = c
            }
        }
        output(prefix, false)
    }
    output(eoi, false)
    if (accBits > 0) bos.write(acc and 0xFF)
    return bos.toByteArray()
}

/**
 * Streams a GIF to [out]: add frames one at a time, then call [finish].
 * One frame = a still picture; several frames = an animation that loops forever.
 */
class GifWriter(private val out: OutputStream, private val width: Int, private val height: Int, private val loop: Boolean) {
    private var started = false

    private fun header() {
        val bb = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("GIF89a".toByteArray(Charsets.US_ASCII))
        bb.putShort(width.toShort())
        bb.putShort(height.toShort())
        bb.put(0) // no global colour table: every frame brings its own
        bb.put(0)
        bb.put(0)
        out.write(bb.array())
        if (loop) {
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf(3, 1, 0, 0, 0)) // loop count 0 = forever
        }
        started = true
    }

    fun addFrame(argb: IntArray, delayMs: Int, dither: Boolean) {
        if (!started) header()
        val q = quantize(argb, width, height, dither)
        val delay = (delayMs / 10).coerceIn(2, 65535)
        val packed = (if (q.transparentIndex >= 0) (2 shl 2) or 1 else (1 shl 2))
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, packed.toByte(), (delay and 0xFF).toByte(), (delay shr 8).toByte(),
            (if (q.transparentIndex >= 0) q.transparentIndex else 0).toByte(), 0))

        val d = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        d.put(0x2C)
        d.putShort(0); d.putShort(0)
        d.putShort(width.toShort()); d.putShort(height.toShort())
        d.put((0x80 or (q.bits - 1)).toByte()) // local colour table, size 2^bits
        out.write(d.array())
        out.write(q.palette)

        out.write(q.bits)
        val data = gifLzw(q.indices, width * height, q.bits)
        var pos = 0
        while (pos < data.size) {
            val len = minOf(255, data.size - pos)
            out.write(len)
            out.write(data, pos, len)
            pos += len
        }
        out.write(0)
    }

    fun finish() {
        if (!started) throw IllegalStateException("A GIF needs at least one picture")
        out.write(0x3B)
    }
}
