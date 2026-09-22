package me.fss.orbal.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class CompressedImage(
    val file: File,
    val localPath: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val mimeType: String = "image/jpeg"
)

object ImageCompressor {
    const val MAX_SIDE = 1024
    const val JPEG_QUALITY = 80
    const val MAX_IMAGES_PER_MESSAGE = 4
    const val MAX_INPUT_BYTES = 20 * 1024 * 1024L // 20MB raw guard
    const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024L // 2MB after compression (base64 ~2.8M)

    suspend fun compressForVision(context: Context, uri: Uri): CompressedImage = withContext(Dispatchers.IO) {
        // Guard raw size
        val rawSize = try {
            context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
        } catch (_: Exception) { 0L }
        // available() is unreliable for content URIs; also check via query size
        val queried = querySize(context, uri)
        val effectiveSize = maxOf(rawSize, queried ?: 0L)
        if (effectiveSize > MAX_INPUT_BYTES) {
            throw IllegalArgumentException("Image too large (${effectiveSize / 1024 / 1024}MB) — max 20MB")
        }

        val inputBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read image")

        // Decode bounds first
        val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, optsBounds)
        if (optsBounds.outWidth <= 0 || optsBounds.outHeight <= 0) {
            throw IllegalArgumentException("Invalid image")
        }

        // Compute inSampleSize
        var sample = 1
        val maxDim = maxOf(optsBounds.outWidth, optsBounds.outHeight)
        if (maxDim > MAX_SIDE * 2) {
            var dim = maxDim
            while (dim / sample > MAX_SIDE * 2) sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, opts)
            ?: throw IllegalArgumentException("Failed to decode image")

        // EXIF orientation
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val exif = ExifInterface(ins)
                val orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix()
                when (orient) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                }
                if (!matrix.isIdentity) {
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    if (rotated != bmp) { bmp.recycle(); bmp = rotated }
                }
            }
        } catch (_: Exception) {}

        // Downscale to MAX_SIDE preserving aspect
        val w = bmp.width
        val h = bmp.height
        val scale = if (maxOf(w, h) > MAX_SIDE) MAX_SIDE.toFloat() / maxOf(w, h) else 1f
        if (scale < 1f) {
            val newW = (w * scale).toInt().coerceAtLeast(1)
            val newH = (h * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)
            if (scaled != bmp) { bmp.recycle(); bmp = scaled }
        }

        val imagesDir = File(context.filesDir, "images").apply { mkdirs() }
        val outFile = File(imagesDir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(outFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        bmp.recycle()

        if (outFile.length() > MAX_OUTPUT_BYTES) {
            // One more attempt at 70% quality
            val bmp2 = BitmapFactory.decodeFile(outFile.absolutePath)
            if (bmp2 != null) {
                FileOutputStream(outFile).use { out ->
                    bmp2.compress(Bitmap.CompressFormat.JPEG, 70, out)
                }
                bmp2.recycle()
            }
            if (outFile.length() > MAX_OUTPUT_BYTES) {
                outFile.delete()
                throw IllegalArgumentException("Compressed image still >2MB — try a smaller image")
            }
        }

        val finalBmpOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(outFile.absolutePath, finalBmpOpts)

        CompressedImage(
            file = outFile,
            localPath = outFile.absolutePath,
            width = finalBmpOpts.outWidth.takeIf { it > 0 } ?: bmp.width,
            height = finalBmpOpts.outHeight.takeIf { it > 0 } ?: bmp.height,
            sizeBytes = outFile.length()
        )
    }

    suspend fun toBase64DataUrl(file: File): String = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        "data:image/jpeg;base64,$b64"
    }

    suspend fun uriToBase64DataUrl(context: Context, localPath: String): String = withContext(Dispatchers.IO) {
        val file = File(localPath)
        if (!file.exists()) throw IllegalArgumentException("Image file missing: $localPath")
        toBase64DataUrl(file)
    }

    private fun querySize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
            }
        } catch (_: Exception) { null }
    }
}
