package com.demo.bandbridge.image

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LoadedImage(
    val label: String,
    val mimeType: String,
    val bytes: ByteArray
)

class ImageRepository(
    private val context: Context
) {
    suspend fun load(uri: Uri): LoadedImage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val originalMimeType = resolver.getType(uri).orEmpty().ifBlank { "image/jpeg" }
        val label = resolveDisplayName(resolver, uri) ?: (uri.lastPathSegment ?: "未命名图片")
        val rawBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取图片数据。")

        val optimized = optimizeImage(rawBytes, originalMimeType)
        LoadedImage(
            label = label,
            mimeType = optimized.first,
            bytes = optimized.second
        )
    }

    suspend fun loadAll(uris: List<Uri>): List<LoadedImage> = withContext(Dispatchers.IO) {
        uris.map { load(it) }
    }

    private fun optimizeImage(rawBytes: ByteArray, originalMimeType: String): Pair<String, ByteArray> {
        if (!originalMimeType.startsWith("image/")) {
            return originalMimeType to rawBytes
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return originalMimeType to rawBytes
        }

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
            ?: return originalMimeType to rawBytes

        val scaledBitmap = scaleBitmapIfNeeded(bitmap)
        if (scaledBitmap !== bitmap) {
            bitmap.recycle()
        }

        val output = ByteArrayOutputStream()
        var quality = 85
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)

        while (output.size() > MAX_UPLOAD_BYTES && quality > 45) {
            output.reset()
            quality -= 10
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        }

        scaledBitmap.recycle()
        return "image/jpeg" to output.toByteArray()
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val longestSide = max(bitmap.width, bitmap.height)
        if (longestSide <= MAX_DIMENSION) {
            return bitmap
        }

        val scale = MAX_DIMENSION.toFloat() / longestSide.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        var currentWidth = width
        var currentHeight = height

        while (currentWidth > maxDimension * 2 || currentHeight > maxDimension * 2) {
            currentWidth /= 2
            currentHeight /= 2
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    companion object {
        private const val MAX_DIMENSION = 1600
        private const val MAX_UPLOAD_BYTES = 1_500_000
    }
}
