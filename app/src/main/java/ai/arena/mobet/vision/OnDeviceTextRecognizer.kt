package ai.arena.mobet.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class VisualTextMatch(
    val text: String,
    val bounds: Rect,
    val confidence: Int
) {
    fun centerXPercent(width: Int) = bounds.centerX().toDouble() / width.coerceAtLeast(1)
    fun centerYPercent(height: Int) = bounds.centerY().toDouble() / height.coerceAtLeast(1)
}

/** Bundled ML Kit Latin OCR. Processing stays on-device. */
object OnDeviceTextRecognizer {
    fun recognize(bitmap: Bitmap, callback: (Result<List<VisualTextMatch>>) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val matches = result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val bounds = line.boundingBox ?: return@mapNotNull null
                        VisualTextMatch(line.text, bounds, estimateConfidence(line.text, bounds, bitmap))
                    }
                }
                recognizer.close()
                callback(Result.success(matches))
            }
            .addOnFailureListener { error ->
                recognizer.close()
                callback(Result.failure(error))
            }
    }

    fun bestMatch(items: List<VisualTextMatch>, query: String): VisualTextMatch? {
        val target = normalize(query)
        return items.filter { normalize(it.text).contains(target) }
            .maxByOrNull { item ->
                val normalized = normalize(item.text)
                item.confidence + if (normalized == target) 30 else 0
            }
    }

    private fun normalize(value: String) = value.lowercase().trim().replace(Regex("\\s+"), " ")

    private fun estimateConfidence(text: String, bounds: Rect, bitmap: Bitmap): Int {
        var score = 55
        if (text.length >= 3) score += 12
        if (text.any(Char::isLetter)) score += 8
        if (bounds.width() > 8 && bounds.height() > 8) score += 8
        if (bounds.left >= 0 && bounds.top >= 0 && bounds.right <= bitmap.width && bounds.bottom <= bitmap.height) score += 7
        return score.coerceIn(20, 95)
    }
}
