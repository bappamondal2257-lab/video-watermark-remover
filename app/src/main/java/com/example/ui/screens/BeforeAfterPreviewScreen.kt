package com.example.ui.screens

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
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
import com.example.ui.theme.EmeraldSuccess
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

private enum class ComparisonLayoutMode {
    TOGGLE,
    SIDE_BY_SIDE,
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
fun BeforeAfterPreviewScreen(
    originalUri: Uri,
    cleanedFile: File,
    durationMs: Long,
    isExporting: Boolean,
    exportedGalleryUri: Uri?,
    statusMessage: String?,
    errorMessage: String?,
    onBackToEditor: () -> Unit,
    onExportToGallery: () -> Unit,
    onDismissStatus: () -> Unit,
    onDismissError: () -> Unit,
) {
    BackHandler(onBack = onBackToEditor)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    var comparisonMode by remember { mutableStateOf(ComparisonLayoutMode.TOGGLE) }
    var showAfter by remember { mutableStateOf(true) }

    val cleanedUri = remember(cleanedFile.absolutePath) { Uri.fromFile(cleanedFile) }

    // Keep original muted so only one synchronized audio track plays
    val originalPlayer = remember(originalUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(originalUri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }

    val cleanedPlayer = remember(cleanedUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(cleanedUri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 1f
            prepare()
            playWhenReady = true
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    val safeDurationMs = durationMs.coerceAtLeast(1L)

    DisposableEffect(originalPlayer, cleanedPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        cleanedPlayer.addListener(listener)
        onDispose {
            cleanedPlayer.removeListener(listener)
            originalPlayer.release()
            cleanedPlayer.release()
        }
    }

    DisposableEffect(lifecycleOwner, originalPlayer, cleanedPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                originalPlayer.pause()
                cleanedPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(cleanedPlayer, originalPlayer, isPlaying) {
        while (true) {
            val cleanedPos = cleanedPlayer.currentPosition.coerceIn(0L, safeDurationMs)
            currentPosMs = cleanedPos
            if (kotlin.math.abs(originalPlayer.currentPosition - cleanedPos) > 180L) {
                originalPlayer.seekTo(cleanedPos)
            }
            delay(100L)
        }
    }

    LaunchedEffect(errorMessage) {
        val msg = errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onDismissError()
    }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onDismissStatus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Before / After",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackToEditor,
                        modifier = Modifier.testTag("back_to_editor_icon_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to editor",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Comparison Layout Mode Toggle (Toggle vs Side-by-Side)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("comparison_mode_segmented"),
            ) {
                SegmentedButton(
                    selected = comparisonMode == ComparisonLayoutMode.TOGGLE,
                    onClick = { comparisonMode = ComparisonLayoutMode.TOGGLE },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Compare,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                ) {
                    Text("Toggle View")
                }
                SegmentedButton(
                    selected = comparisonMode == ComparisonLayoutMode.SIDE_BY_SIDE,
                    onClick = { comparisonMode = ComparisonLayoutMode.SIDE_BY_SIDE },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.ViewColumn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                ) {
                    Text("Side-by-Side")
                }
            }

            // Video Preview Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (comparisonMode == ComparisonLayoutMode.SIDE_BY_SIDE) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LabeledPlayerPane(
                            label = "BEFORE",
                            badgeColor = MaterialTheme.colorScheme.surfaceVariant,
                            player = originalPlayer,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        LabeledPlayerPane(
                            label = "AFTER",
                            badgeColor = EmeraldSuccess,
                            player = cleanedPlayer,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black),
                    ) {
                        SyncedPlayerSurface(
                            player = originalPlayer,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (showAfter) 0f else 1f)
                                .zIndex(if (showAfter) 0f else 1f),
                        )
                        SyncedPlayerSurface(
                            player = cleanedPlayer,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (showAfter) 1f else 0f)
                                .zIndex(if (showAfter) 1f else 0f),
                        )

                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                                .zIndex(2f),
                            shape = RoundedCornerShape(8.dp),
                            color = if (showAfter) EmeraldSuccess else Color.Black.copy(alpha = 0.72f),
                        ) {
                            Text(
                                text = if (showAfter) "AFTER (CLEANED)" else "BEFORE (ORIGINAL)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (showAfter) Color.Black else Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            // Before / After quick switch pill when in Toggle Mode
            if (comparisonMode == ComparisonLayoutMode.TOGGLE) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("before_after_toggle"),
                ) {
                    SegmentedButton(
                        selected = !showAfter,
                        onClick = { showAfter = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text("Before (Original)")
                    }
                    SegmentedButton(
                        selected = showAfter,
                        onClick = { showAfter = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text("After (Cleaned)")
                    }
                }
            }

            // Synchronized Playback Controls
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
                        onClick = {
                            if (cleanedPlayer.isPlaying) {
                                originalPlayer.pause()
                                cleanedPlayer.pause()
                            } else {
                                val syncPos = cleanedPlayer.currentPosition
                                originalPlayer.seekTo(syncPos)
                                originalPlayer.play()
                                cleanedPlayer.play()
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("preview_play_pause_button"),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause preview" else "Play preview",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Text(
                        text = formatPreviewTimeMs(currentPosMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Slider(
                        value = (currentPosMs.toFloat() / safeDurationMs.toFloat()).coerceIn(0f, 1f),
                        onValueChange = { fraction ->
                            val targetMs = (fraction * safeDurationMs).toLong()
                            originalPlayer.seekTo(targetMs)
                            cleanedPlayer.seekTo(targetMs)
                            currentPosMs = targetMs
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )

                    Text(
                        text = formatPreviewTimeMs(safeDurationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            // Export Confirmation Banner (if saved)
            if (exportedGalleryUri != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = EmeraldSuccess.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.5f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldSuccess,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Exported to Gallery (Movies/ClearMark)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // Bottom Action Row: Back to Edit | Save to Gallery
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onBackToEditor,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("back_to_edit_button"),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.back_to_editor))
                }

                Button(
                    onClick = onExportToGallery,
                    enabled = !isExporting,
                    colors = if (exportedGalleryUri != null) {
                        ButtonDefaults.buttonColors(
                            containerColor = EmeraldSuccess,
                            contentColor = Color.Black,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier
                        .weight(1.4f)
                        .height(52.dp)
                        .testTag("save_gallery_button"),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Saving...")
                    } else {
                        Icon(
                            imageVector = if (exportedGalleryUri != null) Icons.Filled.CheckCircle else Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (exportedGalleryUri != null) "Saved!" else stringResource(R.string.save_to_gallery),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LabeledPlayerPane(
    label: String,
    badgeColor: Color,
    player: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black),
    ) {
        SyncedPlayerSurface(
            player = player,
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            shape = RoundedCornerShape(6.dp),
            color = badgeColor,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (badgeColor == EmeraldSuccess) Color.Black else Color.White,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun SyncedPlayerSurface(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
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
            if (playerView.player !== player) {
                playerView.player = player
            }
        },
        modifier = modifier,
    )
}

private fun formatPreviewTimeMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
