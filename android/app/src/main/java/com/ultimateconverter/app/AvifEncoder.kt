package com.ultimateconverter.app

import android.graphics.Bitmap
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * AVIF writing through the avif-coder library (io.github.awxkee:avif-coder).
 * The library is called by name so the rest of the app builds and works even if
 * a future version of it changes its API - AVIF alone would then report an error.
 */
object AvifEncoder {
    private const val CODER_CLASS = "com.radzivon.bartoshyk.avif.coder.HeifCoder"

    fun encode(bitmap: Bitmap): ByteArray {
        try {
            val cls = Class.forName(CODER_CLASS)
            val coder = cls.getDeclaredConstructor().newInstance()

            // 1) a one-argument encodeAvif(Bitmap)
            val single = cls.methods.firstOrNull {
                it.name == "encodeAvif" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Bitmap::class.java
            }
            if (single != null) return single.invoke(coder, bitmap) as ByteArray

            // 2) Kotlin's "all the other settings at their defaults" bridge
            val bridge = cls.declaredMethods.firstOrNull { it.name == "encodeAvif\$default" }
                ?: throw IOException("AVIF isn't available in this build")
            val types = bridge.parameterTypes // (coder, bitmap, other settings..., mask, marker)
            val args = arrayOfNulls<Any>(types.size)
            args[0] = coder
            args[1] = bitmap
            for (i in 2 until types.size - 2) args[i] = zeroOf(types[i])
            val settings = types.size - 4
            args[types.size - 2] = ((1 shl settings) - 1) and 1.inv() // every setting except the bitmap = default
            return bridge.invoke(null, *args) as ByteArray
        } catch (e: InvocationTargetException) {
            throw IOException(e.targetException?.message ?: "AVIF encoding failed")
        } catch (e: ClassNotFoundException) {
            throw IOException("AVIF isn't available in this build")
        }
    }

    private fun zeroOf(c: Class<*>): Any? = when (c) {
        java.lang.Integer.TYPE -> 0
        java.lang.Boolean.TYPE -> false
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }
}
