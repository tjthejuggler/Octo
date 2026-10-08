package com.example.octo.llm

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Extracts evenly-spaced still frames from a downloaded video for the vision
 * muscle analysis. Pure Android APIs (MediaMetadataRetriever) — no extra
 * dependency; runs on whatever thread the caller uses (the pipeline calls it
 * on Dispatchers.IO).
 *
 * Frames are downscaled and JPEG-compressed before base64 so a ~10-frame
 * request stays in the low hundreds of KB total.
 */
object VideoFrameExtractor {

    private const val TARGET_LONG_EDGE = 768
    private const val JPEG_QUALITY = 70

    /**
     * Samples [frameCount] frames evenly across [videoFile]'s duration
     * (skipping the first/last 5% — intros/outros), each base64-encoded.
     * Returns fewer/empty on decode failure — never throws; the pipeline
     * treats "no frames" as "text-only muscle analysis".
     */
    fun extractBase64Frames(videoFile: File, frameCount: Int = 10): List<String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return emptyList()
            if (durationMs <= 0) return emptyList()

            val lead = durationMs / 20 // 5% intro skip
            val tail = durationMs - lead
            val usable = tail - lead
            if (usable <= 0) return emptyList()

            (0 until frameCount).mapNotNull { index ->
                val timestampUs = (lead + usable * index / frameCount.coerceAtLeast(1)) * 1000
                val bitmap = retriever.getFrameAtTime(
                    timestampUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: return@mapNotNull null
                scaleAndEncode(bitmap)
            }
        } catch (_: Exception) {
            emptyList() // unreadable/DRM/corrupt file — degrade gracefully
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleAndEncode(bitmap: Bitmap): String? {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longEdge > TARGET_LONG_EDGE) {
            val factor = TARGET_LONG_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * factor).toInt().coerceAtLeast(1),
                (bitmap.height * factor).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return try {
            val bytes = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }
}
