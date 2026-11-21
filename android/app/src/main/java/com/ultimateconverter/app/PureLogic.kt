package com.ultimateconverter.app

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/*
 * Everything in this file is plain Kotlin with no Android classes, so it can be
 * unit-tested on a normal computer.
 */

private val chunkRegex = Regex("\\d+|\\D+")

/** Orders "scan2" before "scan10" and ignores upper/lower case. */
fun naturalCompare(a: String, b: String): Int {
    val ca = chunkRegex.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
    val cb = chunkRegex.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
    val n = minOf(ca.size, cb.size)
    for (i in 0 until n) {
        val x = ca[i]
        val y = cb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val xv = x.trimStart('0')
            val yv = y.trimStart('0')
            if (xv.length != yv.length) xv.length.compareTo(yv.length) else xv.compareTo(yv)
        } else {
            x.compareTo(y)
        }
        if (c != 0) return c
    }
    return ca.size.compareTo(cb.size)
}

private val badNameChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
private val reservedNames = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
)

/** Turns whatever was typed into a safe file name without extension. */
fun safeFileName(raw: String, default: String = "combined"): String {
    var name = badNameChars.replace(raw, "").trim()
    if (name.lowercase(Locale.ROOT).endsWith(".pdf")) name = name.dropLast(4)
    name = name.trim().trim('.').trim()
    if (name.uppercase(Locale.ROOT) in reservedNames) name += "_"
    if (name.length > 120) name = name.take(120)
    return name.ifEmpty { default }
}

/** "photo.final.jpg" -> "photo.final"; "noext" -> "noext"; ".hidden" -> ".hidden". */
fun stemOf(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0) fileName.substring(0, dot) else fileName
}

fun humanSize(bytes: Long): String {
    if (bytes < 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (i == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", v, units[i])
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** Name for page [index] (0-based) of [count] pages: "doc_page01.png". One page -> "doc.png". */
fun pageFileName(stem: String, ext: String, index: Int, count: Int): String {
    if (count <= 1) return "$stem.$ext"
    val width = maxOf(2, count.toString().length)
    return "${stem}_page${(index + 1).toString().padStart(width, '0')}.$ext"
}

/**
 * Writes an uncompressed 24-bit BMP. [fillRow] must fill `row` (width ints, 0xAARRGGBB)
 * with the pixels of row [y] counted from the TOP of the picture.
 */
fun writeBmp24(width: Int, height: Int, out: OutputStream, fillRow: (y: Int, row: IntArray) -> Unit) {
    val rowSize = (width * 3 + 3) and 3.inv()
    val dataSize = rowSize * height
    val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
    header.put('B'.code.toByte())
    header.put('M'.code.toByte())
    header.putInt(54 + dataSize)
    header.putInt(0)
    header.putInt(54)
    header.putInt(40)
    header.putInt(width)
    header.putInt(height)
    header.putShort(1)
    header.putShort(24)
    header.putInt(0)
    header.putInt(dataSize)
    header.putInt(2835)
    header.putInt(2835)
    header.putInt(0)
    header.putInt(0)
    out.write(header.array())

    val pixels = IntArray(width)
    val row = ByteArray(rowSize) // the padding bytes at the end stay 0
    for (y in height - 1 downTo 0) { // BMP stores the bottom row first
        fillRow(y, pixels)
        for (x in 0 until width) {
            val p = pixels[x]
            row[x * 3] = (p and 0xFF).toByte()
            row[x * 3 + 1] = ((p shr 8) and 0xFF).toByte()
            row[x * 3 + 2] = ((p shr 16) and 0xFF).toByte()
        }
        out.write(row)
    }
}

/** Bitrate (bits/second) for a re-encoded video. quality 1..100. */
fun videoBitrate(width: Int, height: Int, fps: Float, quality: Int, highest: Boolean): Int {
    val bitsPerPixel = if (highest) 0.25 else 0.03 + 0.17 * (quality.coerceIn(1, 100) / 100.0)
    val raw = width.toDouble() * height * fps.coerceIn(1f, 120f) * bitsPerPixel
    return raw.toLong().coerceIn(500_000L, 40_000_000L).toInt()
}
