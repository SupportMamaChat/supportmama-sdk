package dev.supportmama.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Screenshots go up as a JPEG no larger than [MAX_EDGE] on its long side:
 * readable in Telegram or Slack, a few hundred KB on a phone connection.
 */
internal object Images {
    private const val MAX_EDGE = 1600

    /** Decodes [uri] into a JPEG in the cache, or null if it isn't a readable image. */
    fun stage(context: Context, uri: Uri): File? = runCatching {
        val bitmap = decode(context, uri) ?: return null
        val out = File(File(context.cacheDir, "supportmama").apply { mkdirs() }, UUID.randomUUID().toString() + ".jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        out
    }.getOrNull()

    fun discard(path: String) {
        runCatching { File(path).delete() }
    }

    /** A thumbnail-sized decode of a file we already hold. */
    fun thumbnail(path: String, edge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies EXIF rotation, so a photo arrives upright.
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = max(info.size.width, info.size.height)
                if (long > MAX_EDGE) {
                    val scale = MAX_EDGE.toFloat() / long
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val long = max(decoded.width, decoded.height)
        if (long <= MAX_EDGE) return decoded
        val scale = MAX_EDGE.toFloat() / long
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
            .also { if (it !== decoded) decoded.recycle() }
    }
}
