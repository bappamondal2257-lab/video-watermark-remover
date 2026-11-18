package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.media.VideoInpainter
import com.example.data.model.BrushStroke
import com.example.data.model.ProcessingProgressState
import com.example.data.model.SelectionMode
import com.example.data.model.VideoMetadataInfo
import com.example.data.model.WatermarkBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ClearMarkUiState(
    val videoMetadata: VideoMetadataInfo? = null,
    val selectionMode: SelectionMode = SelectionMode.BOX,
    val boxes: List<WatermarkBox> = emptyList(),
    val selectedBoxId: String? = null,
    val brushStrokes: List<BrushStroke> = emptyList(),
    val brushWidthNorm: Float = 0.045f,
    val processingState: ProcessingProgressState = ProcessingProgressState.Idle,
    val cleanedVideoFile: File? = null,
    val showBeforeAfterPreview: Boolean = false,
    val isExporting: Boolean = false,
    val exportedGalleryUri: Uri? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val hasRegionsSelected: Boolean
        get() = boxes.isNotEmpty() || brushStrokes.isNotEmpty()

    val isProcessing: Boolean
        get() = processingState is ProcessingProgressState.Processing
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val videoInpainter = VideoInpainter(application.applicationContext)

    private val _uiState = MutableStateFlow(ClearMarkUiState())
    val uiState: StateFlow<ClearMarkUiState> = _uiState.asStateFlow()

    private var activeProcessingJob: Job? = null

    fun onVideoPicked(uri: Uri) {
        activeProcessingJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    statusMessage = null,
                    processingState = ProcessingProgressState.Idle,
                )
            }
            videoInpainter.validateVideoUri(uri)
                .onSuccess { metadata ->
                    _uiState.update {
                        ClearMarkUiState(
                            videoMetadata = metadata,
                            selectionMode = SelectionMode.BOX,
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            errorMessage = err.localizedMessage ?: "Unable to load selected video."
                        )
                    }
                }
        }
    }

    fun setSelectionMode(mode: SelectionMode) {
        _uiState.update { it.copy(selectionMode = mode, errorMessage = null) }
    }

    fun setBrushWidthNorm(widthNorm: Float) {
        _uiState.update { it.copy(brushWidthNorm = widthNorm.coerceIn(0.015f, 0.14f)) }
    }

    fun selectBox(boxId: String?) {
        _uiState.update { it.copy(selectedBoxId = boxId) }
    }

    fun addManualBox(
        xmin: Float = 0.34f,
        ymin: Float = 0.38f,
        xmax: Float = 0.66f,
        ymax: Float = 0.56f,
    ) {
        val newBox = WatermarkBox(
            ymin = ymin,
            xmin = xmin,
            ymax = ymax,
            xmax = xmax,
        ).clamped()

        _uiState.update {
            it.copy(
                boxes = it.boxes + newBox,
                selectedBoxId = newBox.id,
                errorMessage = null,
            )
        }
    }

    fun updateBox(updated: WatermarkBox) {
        val clamped = updated.clamped()
        _uiState.update { state ->
            state.copy(
                boxes = state.boxes.map { if (it.id == clamped.id) clamped else it }
            )
        }
    }

    fun removeBox(boxId: String) {
        _uiState.update { state ->
            val remaining = state.boxes.filterNot { it.id == boxId }
            val nextSelected = if (state.selectedBoxId == boxId) remaining.lastOrNull()?.id else state.selectedBoxId
            state.copy(
                boxes = remaining,
                selectedBoxId = nextSelected,
            )
        }
    }

    fun addBrushStroke(stroke: BrushStroke) {
        if (stroke.points.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                brushStrokes = state.brushStrokes + stroke,
                errorMessage = null,
            )
        }
    }

    fun undoLastBrushStroke() {
        _uiState.update { state ->
            if (state.brushStrokes.isEmpty()) state
            else state.copy(brushStrokes = state.brushStrokes.dropLast(1))
        }
    }

    fun clearAllSelections() {
        _uiState.update {
            it.copy(
                boxes = emptyList(),
                selectedBoxId = null,
                brushStrokes = emptyList(),
                statusMessage = null,
            )
        }
    }

    fun startWatermarkRemoval() {
        val state = _uiState.value
        val metadata = state.videoMetadata ?: return
        if (!state.hasRegionsSelected) {
            _uiState.update { it.copy(errorMessage = "Draw a box or brush over the watermark first.") }
            return
        }

        activeProcessingJob?.cancel()
        activeProcessingJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    cleanedVideoFile = null,
                    exportedGalleryUri = null,
                    errorMessage = null,
                    statusMessage = null,
                    processingState = ProcessingProgressState.Processing(0, 0, "Preparing on-device processor..."),
                )
            }

            videoInpainter.processVideo(
                metadata = metadata,
                boxes = state.boxes,
                brushStrokes = state.brushStrokes,
            ).collect { progress ->
                when (progress) {
                    is ProcessingProgressState.Completed -> {
                        _uiState.update {
                            it.copy(
                                processingState = progress,
                                cleanedVideoFile = progress.cleanedVideoFile,
                                showBeforeAfterPreview = true,
                            )
                        }
                    }
                    is ProcessingProgressState.Failed -> {
                        _uiState.update {
                            it.copy(
                                processingState = progress,
                                errorMessage = progress.message,
                            )
                        }
                    }
                    else -> {
                        _uiState.update { it.copy(processingState = progress) }
                    }
                }
            }
        }
    }

    fun cancelProcessing() {
        activeProcessingJob?.cancel()
        activeProcessingJob = null
        _uiState.update {
            it.copy(
                processingState = ProcessingProgressState.Idle,
                statusMessage = "Processing canceled.",
            )
        }
    }

    fun openBeforeAfterPreview() {
        if (_uiState.value.cleanedVideoFile != null) {
            _uiState.update { it.copy(showBeforeAfterPreview = true) }
        }
    }

    fun closeBeforeAfterPreview() {
        _uiState.update { it.copy(showBeforeAfterPreview = false) }
    }

    fun exportToGallery() {
        val state = _uiState.value
        val cleanedFile = state.cleanedVideoFile ?: return
        val metadata = state.videoMetadata ?: return
        if (state.isExporting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, errorMessage = null) }
            videoInpainter.saveVideoToGallery(cleanedFile, metadata.displayName)
                .onSuccess { savedUri ->
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportedGalleryUri = savedUri,
                            statusMessage = "Saved to Gallery (Movies/ClearMark).",
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            errorMessage = "Export failed: ${err.localizedMessage ?: "Unknown error"}",
                        )
                    }
                }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
