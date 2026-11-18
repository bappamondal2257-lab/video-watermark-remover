package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainViewModel
import com.example.ui.screens.BeforeAfterPreviewScreen
import com.example.ui.screens.VideoEditorScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: MainViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                val cleanedFile = uiState.cleanedVideoFile
                val videoMeta = uiState.videoMetadata

                if (uiState.showBeforeAfterPreview && cleanedFile != null && videoMeta != null) {
                    BeforeAfterPreviewScreen(
                        originalUri = videoMeta.uri,
                        cleanedFile = cleanedFile,
                        durationMs = videoMeta.durationMs,
                        isExporting = uiState.isExporting,
                        exportedGalleryUri = uiState.exportedGalleryUri,
                        statusMessage = uiState.statusMessage,
                        errorMessage = uiState.errorMessage,
                        onBackToEditor = viewModel::closeBeforeAfterPreview,
                        onExportToGallery = viewModel::exportToGallery,
                        onDismissStatus = viewModel::dismissStatusMessage,
                        onDismissError = viewModel::dismissError,
                    )
                } else {
                    VideoEditorScreen(
                        uiState = uiState,
                        onVideoPicked = viewModel::onVideoPicked,
                        onSelectMode = viewModel::setSelectionMode,
                        onSelectBox = viewModel::selectBox,
                        onUpdateBox = viewModel::updateBox,
                        onAddBox = { xmin, ymin, xmax, ymax ->
                            viewModel.addManualBox(xmin = xmin, ymin = ymin, xmax = xmax, ymax = ymax)
                        },
                        onRemoveBox = viewModel::removeBox,
                        onAddBrushStroke = viewModel::addBrushStroke,
                        onUndoBrushStroke = viewModel::undoLastBrushStroke,
                        onBrushWidthChange = viewModel::setBrushWidthNorm,
                        onClearAllSelections = viewModel::clearAllSelections,
                        onStartRemoval = viewModel::startWatermarkRemoval,
                        onCancelProcessing = viewModel::cancelProcessing,
                        onOpenPreview = viewModel::openBeforeAfterPreview,
                        onDismissError = viewModel::dismissError,
                        onDismissStatus = viewModel::dismissStatusMessage,
                    )
                }
            }
        }
    }
}
