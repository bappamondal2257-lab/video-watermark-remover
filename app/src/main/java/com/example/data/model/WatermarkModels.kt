package com.example.data.model

import android.net.Uri
import java.io.File
import java.util.UUID

enum class SelectionMode {
    BOX,
    BRUSH,
}

data class WatermarkBox(
    val id: String = UUID.randomUUID().toString(),
    val ymin: Float,
    val xmin: Float,
    val ymax: Float,
    val xmax: Float,
) {
    fun clamped(): WatermarkBox {
        val minSpan = 0.03f
        val safeXMin = xmin.coerceIn(0f, 1f - minSpan)
        val safeYMin = ymin.coerceIn(0f, 1f - minSpan)
        val safeXMax = xmax.coerceIn(safeXMin + minSpan, 1f)
        val safeYMax = ymax.coerceIn(safeYMin + minSpan, 1f)
        return copy(
            ymin = safeYMin,
            xmin = safeXMin,
            ymax = safeYMax,
            xmax = safeXMax,
        )
    }
}

data class BrushPoint(
    val x: Float,
    val y: Float,
)

data class BrushStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<BrushPoint>,
    val strokeWidthNorm: Float = 0.045f,
)

data class VideoMetadataInfo(
    val uri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val mimeType: String,
    val sizeBytes: Long,
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 16f / 9f
}

sealed interface ProcessingProgressState {
    data object Idle : ProcessingProgressState

    data class Processing(
        val progressPercent: Int,
        val framesProcessed: Int,
        val message: String,
    ) : ProcessingProgressState

    data class Completed(
        val cleanedVideoFile: File,
    ) : ProcessingProgressState

    data class Failed(
        val message: String,
    ) : ProcessingProgressState
}
