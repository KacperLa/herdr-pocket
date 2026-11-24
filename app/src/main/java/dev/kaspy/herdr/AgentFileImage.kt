package dev.kaspy.herdr

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import java.nio.ByteBuffer
import kotlin.math.sqrt

/** Untrusted dimensions are read before allocation. Decode to a single software bitmap. */
internal fun imagePreviewSize(width: Int, height: Int): Pair<Int, Int>? {
    if (width !in 1..32768 || height !in 1..32768) return null
    if (width.toLong() * height > 24_000_000) return null
    val ratio = minOf(1.0, 4096.0 / maxOf(width, height), sqrt(4_000_000.0 / (width.toLong() * height)))
    return maxOf(1, (width * ratio).toInt()) to maxOf(1, (height * ratio).toInt())
}

internal fun decodeAgentFileImage(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return null
    return try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val (width, height) = imagePreviewSize(info.size.width, info.size.height)
                ?: throw IllegalArgumentException("Image dimensions exceed preview limits")
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSize(width, height)
        }.takeIf { it.width in 1..4096 && it.height in 1..4096 && it.width.toLong() * it.height <= 4_000_000 }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}
