package com.example.ui.screens

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.data.model.BrushStroke
import com.example.data.model.ProcessingProgressState
import com.example.data.model.SelectionMode
import com.example.data.model.VideoMetadataInfo
import com.example.data.model.WatermarkBox
import com.example.ui.ClearMarkUiState
import com.example.ui.components.WatermarkOverlayCanvas
import kotlinx.coroutines.delay
import java.util.Locale

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen(
    uiState: ClearMarkUiState,
    onVideoPicked: (Uri) -> Unit,
    onSelectMode: (SelectionMode) -> Unit,
    onSelectBox: (String?) -> Unit,
    onUpdateBox: (WatermarkBox) -> Unit,
    onAddBox: (xmin: Float, ymin: Float, xmax: Float, ymax: Float) -> Unit,
    onRemoveBox: (String) -> Unit,
    onAddBrushStroke: (BrushStroke) -> Unit,
    onUndoBrushStroke: () -> Unit,
    onBrushWidthChange: (Float) -> Unit,
    onClearAllSelections: () -> Unit,
    onStartRemoval: () -> Unit,
    onCancelProcessing: () -> Unit,
    onOpenPreview: () -> Unit,
    onDismissError: () -> Unit,
    onDismissStatus: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onVideoPicked(uri)
        }
    }

    val launchVideoPicker = {
        videoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    LaunchedEffect(uiState.errorMessage) {
        val msg = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onDismissError()
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onDismissStatus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary,
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LayersClear,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    if (uiState.cleanedVideoFile != null && !uiState.isProcessing) {
                        TextButton(
                            onClick = onOpenPreview,
                            modifier = Modifier.testTag("open_result_preview_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Visibility,
                                contentDescription = "View Before/After Result",
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Result")
                        }
                    }
                    if (uiState.videoMetadata != null && !uiState.isProcessing) {
                        TextButton(
                            onClick = launchVideoPicker,
                            modifier = Modifier.testTag("change_video_button"),
                        ) {
                            Text(stringResource(R.string.change_video))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val metadata = uiState.videoMetadata
        if (metadata == null) {
            EmptyImportHero(
                onImportClick = launchVideoPicker,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
            )
        } else {
            ActiveVideoEditorContent(
                metadata = metadata,
                uiState = uiState,
                onSelectMode = onSelectMode,
                onSelectBox = onSelectBox,
                onUpdateBox = onUpdateBox,
                onAddBox = onAddBox,
                onRemoveBox = onRemoveBox,
                onAddBrushStroke = onAddBrushStroke,
                onUndoBrushStroke = onUndoBrushStroke,
                onBrushWidthChange = onBrushWidthChange,
                onClearAllSelections = onClearAllSelections,
                onStartRemoval = onStartRemoval,
                onCancelProcessing = onCancelProcessing,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun EmptyImportHero(
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 460.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.VideoFile,
                        contentDescription = "Import video",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(38.dp),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Offline Watermark Remover",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "100% on-device video watermark removal. Mark logos with a box or brush and clean frames locally while keeping original audio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpecBadge("100% Offline")
                    SpecBadge("MP4 / MOV")
                    SpecBadge("Max 90s • 1080p")
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = onImportClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("import_video_button"),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.VideoFile,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.import_video),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ActiveVideoEditorContent(
    metadata: VideoMetadataInfo,
    uiState: ClearMarkUiState,
    onSelectMode: (SelectionMode) -> Unit,
    onSelectBox: (String?) -> Unit,
    onUpdateBox: (WatermarkBox) -> Unit,
    onAddBox: (Float, Float, Float, Float) -> Unit,
    onRemoveBox: (String) -> Unit,
    onAddBrushStroke: (BrushStroke) -> Unit,
    onUndoBrushStroke: () -> Unit,
    onBrushWidthChange: (Float) -> Unit,
    onClearAllSelections: () -> Unit,
    onStartRemoval: () -> Unit,
    onCancelProcessing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(metadata.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(metadata.uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = false
        }
    }

    var isPlaying by remember(metadata.uri) { mutableStateOf(false) }
    var currentPosMs by remember(metadata.uri) { mutableLongStateOf(0L) }
    val durationMs = metadata.durationMs.coerceAtLeast(1L)

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        while (true) {
            currentPosMs = exoPlayer.currentPosition.coerceIn(0L, durationMs)
            delay(if (isPlaying) 80L else 250L)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val isWideLayout = maxWidth >= 720.dp

        if (isWideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                VideoStageSection(
                    metadata = metadata,
                    exoPlayer = exoPlayer,
                    uiState = uiState,
                    isPlaying = isPlaying,
                    currentPosMs = currentPosMs,
                    durationMs = durationMs,
                    onTogglePlayPause = {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    },
                    onSeek = { targetMs ->
                        exoPlayer.seekTo(targetMs)
                        currentPosMs = targetMs
                    },
                    onSelectBox = onSelectBox,
                    onUpdateBox = onUpdateBox,
                    onAddBox = onAddBox,
                    onAddBrushStroke = onAddBrushStroke,
                    modifier = Modifier
                        .weight(1.35f)
                        .fillMaxHeight(),
                )

                ControlsDeckSection(
                    metadata = metadata,
                    uiState = uiState,
                    onSelectMode = onSelectMode,
                    onSelectBox = onSelectBox,
                    onAddBox = { onAddBox(0.35f, 0.35f, 0.65f, 0.55f) },
                    onRemoveBox = onRemoveBox,
                    onUndoBrushStroke = onUndoBrushStroke,
                    onBrushWidthChange = onBrushWidthChange,
                    onClearAllSelections = onClearAllSelections,
                    onStartRemoval = {
                        exoPlayer.pause()
                        onStartRemoval()
                    },
                    onCancelProcessing = onCancelProcessing,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VideoStageSection(
                    metadata = metadata,
                    exoPlayer = exoPlayer,
                    uiState = uiState,
                    isPlaying = isPlaying,
                    currentPosMs = currentPosMs,
                    durationMs = durationMs,
                    onTogglePlayPause = {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    },
                    onSeek = { targetMs ->
                        exoPlayer.seekTo(targetMs)
                        currentPosMs = targetMs
                    },
                    onSelectBox = onSelectBox,
                    onUpdateBox = onUpdateBox,
                    onAddBox = onAddBox,
                    onAddBrushStroke = onAddBrushStroke,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )

                ControlsDeckSection(
                    metadata = metadata,
                    uiState = uiState,
                    onSelectMode = onSelectMode,
                    onSelectBox = onSelectBox,
                    onAddBox = { onAddBox(0.35f, 0.35f, 0.65f, 0.55f) },
                    onRemoveBox = onRemoveBox,
                    onUndoBrushStroke = onUndoBrushStroke,
                    onBrushWidthChange = onBrushWidthChange,
                    onClearAllSelections = onClearAllSelections,
                    onStartRemoval = {
                        exoPlayer.pause()
                        onStartRemoval()
                    },
                    onCancelProcessing = onCancelProcessing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoStageSection(
    metadata: VideoMetadataInfo,
    exoPlayer: ExoPlayer,
    uiState: ClearMarkUiState,
    isPlaying: Boolean,
    currentPosMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectBox: (String?) -> Unit,
    onUpdateBox: (WatermarkBox) -> Unit,
    onAddBox: (Float, Float, Float, Float) -> Unit,
    onAddBrushStroke: (BrushStroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { playerView ->
                    if (playerView.player !== exoPlayer) {
                        playerView.player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            WatermarkOverlayCanvas(
                videoWidth = metadata.width,
                videoHeight = metadata.height,
                selectionMode = uiState.selectionMode,
                boxes = uiState.boxes,
                selectedBoxId = uiState.selectedBoxId,
                brushStrokes = uiState.brushStrokes,
                brushWidthNorm = uiState.brushWidthNorm,
                enabled = !uiState.isProcessing,
                onSelectBox = onSelectBox,
                onUpdateBox = onUpdateBox,
                onCreateBox = onAddBox,
                onAddBrushStroke = onAddBrushStroke,
                modifier = Modifier.fillMaxSize(),
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.65f),
            ) {
                Text(
                    text = "${metadata.width}x${metadata.height} • ${formatTimeMs(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("play_pause_button"),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause video" else "Play video",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                Text(
                    text = formatTimeMs(currentPosMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Slider(
                    value = (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                    onValueChange = { fraction ->
                        onSeek((fraction * durationMs).toLong())
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .testTag("video_seek_slider"),
                )

                Text(
                    text = formatTimeMs(durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ControlsDeckSection(
    metadata: VideoMetadataInfo,
    uiState: ClearMarkUiState,
    onSelectMode: (SelectionMode) -> Unit,
    onSelectBox: (String?) -> Unit,
    onAddBox: () -> Unit,
    onRemoveBox: (String) -> Unit,
    onUndoBrushStroke: () -> Unit,
    onBrushWidthChange: (Float) -> Unit,
    onClearAllSelections: () -> Unit,
    onStartRemoval: () -> Unit,
    onCancelProcessing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AnimatedVisibility(visible = uiState.isProcessing) {
            ProcessingProgressCard(
                processingState = uiState.processingState,
                onCancel = onCancelProcessing,
            )
        }

        if (!uiState.isProcessing) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("selection_mode_segmented"),
            ) {
                val modes = listOf(
                    Triple(SelectionMode.BOX, stringResource(R.string.manual_box), Icons.Filled.CropFree),
                    Triple(SelectionMode.BRUSH, stringResource(R.string.manual_brush), Icons.Filled.Brush),
                )
                modes.forEachIndexed { index, (mode, label, icon) ->
                    SegmentedButton(
                        selected = uiState.selectionMode == mode,
                        onClick = { onSelectMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        modifier = Modifier.testTag("mode_${mode.name.lowercase()}"),
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (uiState.selectionMode) {
                        SelectionMode.BOX -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(
                                    onClick = onAddBox,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("add_box_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AddBox,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Add Box Region")
                                }
                                if (uiState.hasRegionsSelected) {
                                    OutlinedButton(
                                        onClick = onClearAllSelections,
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("clear_regions_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteSweep,
                                            contentDescription = "Clear all regions",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "Drag directly on the video to draw a rectangle, or drag corners to resize.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        SelectionMode.BRUSH -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Brush",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Slider(
                                    value = uiState.brushWidthNorm,
                                    onValueChange = onBrushWidthChange,
                                    valueRange = 0.015f..0.12f,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("brush_size_slider"),
                                )
                                FilledTonalIconButton(
                                    onClick = onUndoBrushStroke,
                                    enabled = uiState.brushStrokes.isNotEmpty(),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("undo_brush_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Undo,
                                        contentDescription = "Undo last brush stroke",
                                    )
                                }
                                IconButton(
                                    onClick = onClearAllSelections,
                                    enabled = uiState.hasRegionsSelected,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("clear_brush_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteSweep,
                                        contentDescription = "Clear all selections",
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.boxes.isNotEmpty() || uiState.brushStrokes.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 2.dp),
                        ) {
                            itemsIndexed(uiState.boxes, key = { _, b -> b.id }) { index, box ->
                                val selected = box.id == uiState.selectedBoxId
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectBox(box.id) },
                                    label = {
                                        Text(
                                            text = "Box ${index + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { onRemoveBox(box.id) },
                                            modifier = Modifier.size(20.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Remove Box ${index + 1}",
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                )
                            }
                            if (uiState.brushStrokes.isNotEmpty()) {
                                item(key = "brush_count_chip") {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Text(
                                            text = "${uiState.brushStrokes.size} brush stroke(s)",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onStartRemoval,
                enabled = uiState.hasRegionsSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("remove_watermark_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.LayersClear,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${stringResource(R.string.remove_watermark)} (${metadata.displayName})",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ProcessingProgressCard(
    processingState: ProcessingProgressState,
    onCancel: () -> Unit,
) {
    val state = processingState as? ProcessingProgressState.Processing
    val pct = state?.progressPercent ?: 0
    val message = state?.message ?: "Processing..."

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("processing_progress_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Processing Offline ($pct%)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("cancel_processing_button"),
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                }
            }

            LinearProgressIndicator(
                progress = { (pct / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
    }
}

private fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
