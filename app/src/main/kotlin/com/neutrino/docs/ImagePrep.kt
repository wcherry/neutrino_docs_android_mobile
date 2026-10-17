package com.neutrino.docs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.neutrino.docs.core.editing.EditorImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A picture as it will be stored in Drive. The original goes up as it arrived — Drive holds the
 * picture somebody has, not this app's idea of a reasonable one — with one exception: HEIC, which
 * most browsers cannot display, is converted to a full-resolution JPEG so the picture renders
 * everywhere the document does. Memory is saved at the other end, by decoding at a bounded size.
 */
object ImagePrep {

    class Unreadable : Exception("That file isn't an image this app can read.")

    data class Prepared(val data: ByteArray, val mimeType: String, val fileExtension: String)

    fun prepared(data: ByteArray): Prepared {
        val (mime, ext) = EditorImage.sniff(data) ?: throw Unreadable()
        if (mime != "image/heic") return Prepared(data, mime, ext)
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw Unreadable()
        val out = ByteArrayOutputStream()
        applyExifOrientation(bitmap, data).compress(Bitmap.CompressFormat.JPEG, 90, out)
        return Prepared(out.toByteArray(), "image/jpeg", "jpg")
    }

    /** Decodes no larger than `maxPixels` on the long edge: a 12-megapixel photo is ~48 MB of bitmap, none needed to draw a column. */
    fun decodeBounded(data: ByteArray, maxPixels: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        applyExifOrientation(bitmap, data)
    }.getOrNull()

    /** A photo taken in portrait is stored sideways with an EXIF tag saying so; a decoded bitmap ignores it. */
    fun applyExifOrientation(bitmap: Bitmap, data: ByteArray): Bitmap {
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
