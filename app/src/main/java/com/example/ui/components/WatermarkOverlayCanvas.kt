package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.BrushPoint
import com.example.data.model.BrushStroke
import com.example.data.model.SelectionMode
import com.example.data.model.WatermarkBox
import com.example.ui.theme.ElectricCyan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class VideoViewportRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun contains(offset: Offset): Boolean =
        width > 0f && height > 0f && offset.x in left..right && offset.y in top..bottom

    fun screenToNormalized(offset: Offset): BrushPoint {
        val nx = ((offset.x - left) / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val ny = ((offset.y - top) / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return BrushPoint(x = nx, y = ny)
    }

    fun normalizedToScreen(nx: Float, ny: Float): Offset = Offset(
        x = left + nx * width,
        y = top + ny * height,
    )
}

fun computeVideoViewportRect(
    containerWidth: Float,
    containerHeight: Float,
    videoWidth: Int,
    videoHeight: Int,
): VideoViewportRect {
    if (containerWidth <= 0f || containerHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) {
        return VideoViewportRect(0f, 0f, containerWidth.coerceAtLeast(1f), containerHeight.coerceAtLeast(1f))
    }
    val containerAspect = containerWidth / containerHeight
    val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
    return if (videoAspect > containerAspect) {
        val renderedW = containerWidth
        val renderedH = containerWidth / videoAspect
        VideoViewportRect(
            left = 0f,
            top = (containerHeight - renderedH) / 2f,
            width = renderedW,
            height = renderedH,
        )
    } else {
        val renderedH = containerHeight
        val renderedW = containerHeight * videoAspect
        VideoViewportRect(
            left = (containerWidth - renderedW) / 2f,
            top = 0f,
            width = renderedW,
            height = renderedH,
        )
    }
}

private enum class ResizeCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

private sealed interface ActiveDragState {
    data class MoveBox(val boxId: String) : ActiveDragState
    data class ResizeBox(val boxId: String, val corner: ResizeCorner) : ActiveDragState
    data class DrawNewBox(val startNorm: BrushPoint, val currentNorm: BrushPoint) : ActiveDragState
    data class DrawBrush(val points: List<BrushPoint>) : ActiveDragState
}

@Composable
fun WatermarkOverlayCanvas(
    videoWidth: Int,
    videoHeight: Int,
    selectionMode: SelectionMode,
    boxes: List<WatermarkBox>,
    selectedBoxId: String?,
    brushStrokes: List<BrushStroke>,
    brushWidthNorm: Float,
    enabled: Boolean,
    onSelectBox: (String?) -> Unit,
    onUpdateBox: (WatermarkBox) -> Unit,
    onCreateBox: (xmin: Float, ymin: Float, xmax: Float, ymax: Float) -> Unit,
    onAddBrushStroke: (BrushStroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handleHitRadiusPx = with(density) { 26.dp.toPx() }
    val handleDrawRadiusPx = with(density) { 7.dp.toPx() }
    val borderStrokePx = with(density) { 2.5.dp.toPx() }

    val currentMode by rememberUpdatedState(selectionMode)
    val currentBoxes by rememberUpdatedState(boxes)
    val currentSelectedId by rememberUpdatedState(selectedBoxId)
    val currentBrushWidth by rememberUpdatedState(brushWidthNorm)
    val currentEnabled by rememberUpdatedState(enabled)

    val onSelectBoxState by rememberUpdatedState(onSelectBox)
    val onUpdateBoxState by rememberUpdatedState(onUpdateBox)
    val onCreateBoxState by rememberUpdatedState(onCreateBox)
    val onAddBrushStrokeState by rememberUpdatedState(onAddBrushStroke)

    var activeDrag by remember { mutableStateOf<ActiveDragState?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("watermark_overlay_canvas")
            .pointerInput(videoWidth, videoHeight) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEnabled) return@awaitEachGesture

                    val viewport = computeVideoViewportRect(
                        containerWidth = size.width.toFloat(),
                        containerHeight = size.height.toFloat(),
                        videoWidth = videoWidth,
                        videoHeight = videoHeight,
                    )

                    if (currentMode == SelectionMode.BRUSH) {
                        if (!viewport.contains(down.position)) return@awaitEachGesture
                        down.consume()
                        val startPt = viewport.screenToNormalized(down.position)
                        val points = mutableListOf(startPt)
                        activeDrag = ActiveDragState.DrawBrush(points.toList())

                        drag(down.id) { change ->
                            change.consume()
                            val pt = viewport.screenToNormalized(change.position)
                            val last = points.last()
                            val dist = hypot(pt.x - last.x, pt.y - last.y)
                            if (dist >= 0.004f) {
                                points.add(pt)
                                activeDrag = ActiveDragState.DrawBrush(points.toList())
                            }
                        }

                        if (points.isNotEmpty()) {
                            onAddBrushStrokeState(
                                BrushStroke(
                                    points = points.toList(),
                                    strokeWidthNorm = currentBrushWidth,
                                )
                            )
                        }
                        activeDrag = null
                        return@awaitEachGesture
                    }

                    // BOX mode: 1) Check selected box corner resize handles
                    val selectedBox = currentBoxes.find { it.id == currentSelectedId }
                    if (selectedBox != null) {
                        val hitCorner = findHitCorner(selectedBox, viewport, down.position, handleHitRadiusPx)
                        if (hitCorner != null) {
                            down.consume()
                            var workingBox: WatermarkBox = selectedBox
                            activeDrag = ActiveDragState.ResizeBox(workingBox.id, hitCorner)

                            drag(down.id) { change ->
                                change.consume()
                                val delta = change.positionChange()
                                val dx = delta.x / viewport.width.coerceAtLeast(1f)
                                val dy = delta.y / viewport.height.coerceAtLeast(1f)
                                workingBox = applyCornerResize(workingBox, hitCorner, dx, dy)
                                onUpdateBoxState(workingBox)
                            }
                            activeDrag = null
                            return@awaitEachGesture
                        }
                    }

                    // 2) Check touch inside any existing bounding box (selected box prioritized)
                    val orderedBoxes = if (selectedBox != null) {
                        listOf(selectedBox) + currentBoxes.filter { it.id != selectedBox.id }.reversed()
                    } else {
                        currentBoxes.reversed()
                    }
                    val hitBox = orderedBoxes.firstOrNull { box ->
                        val tl = viewport.normalizedToScreen(box.xmin, box.ymin)
                        val br = viewport.normalizedToScreen(box.xmax, box.ymax)
                        down.position.x in (tl.x - handleHitRadiusPx * 0.4f)..(br.x + handleHitRadiusPx * 0.4f) &&
                            down.position.y in (tl.y - handleHitRadiusPx * 0.4f)..(br.y + handleHitRadiusPx * 0.4f)
                    }

                    if (hitBox != null) {
                        down.consume()
                        onSelectBoxState(hitBox.id)
                        var workingBox: WatermarkBox = hitBox
                        activeDrag = ActiveDragState.MoveBox(workingBox.id)

                        drag(down.id) { change ->
                            change.consume()
                            val delta = change.positionChange()
                            val dx = delta.x / viewport.width.coerceAtLeast(1f)
                            val dy = delta.y / viewport.height.coerceAtLeast(1f)
                            workingBox = moveBoxPreservingSize(workingBox, dx, dy)
                            onUpdateBoxState(workingBox)
                        }
                        activeDrag = null
                        return@awaitEachGesture
                    }

                    // 3) Drag on empty video area in BOX mode to draw a new bounding box
                    if (viewport.contains(down.position)) {
                        down.consume()
                        onSelectBoxState(null)
                        val startNorm = viewport.screenToNormalized(down.position)
                        var currentNorm = startNorm
                        activeDrag = ActiveDragState.DrawNewBox(startNorm, currentNorm)

                        drag(down.id) { change ->
                            change.consume()
                            currentNorm = viewport.screenToNormalized(change.position)
                            activeDrag = ActiveDragState.DrawNewBox(startNorm, currentNorm)
                        }

                        val xmin = min(startNorm.x, currentNorm.x)
                        val xmax = max(startNorm.x, currentNorm.x)
                        val ymin = min(startNorm.y, currentNorm.y)
                        val ymax = max(startNorm.y, currentNorm.y)
                        if ((xmax - xmin) >= 0.03f && (ymax - ymin) >= 0.03f) {
                            onCreateBoxState(xmin, ymin, xmax, ymax)
                        }
                        activeDrag = null
                    }
                }
            }
    ) {
        val viewport = computeVideoViewportRect(
            containerWidth = size.width,
            containerHeight = size.height,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
        )
        val minVideoDim = min(viewport.width, viewport.height).coerceAtLeast(1f)

        for (stroke in brushStrokes) {
            drawBrushStroke(
                stroke = stroke,
                viewport = viewport,
                minVideoDim = minVideoDim,
                color = Color(0x99F43F5E),
            )
        }

        val currentDrag = activeDrag
        if (currentDrag is ActiveDragState.DrawBrush) {
            drawBrushStroke(
                stroke = BrushStroke(points = currentDrag.points, strokeWidthNorm = brushWidthNorm),
                viewport = viewport,
                minVideoDim = minVideoDim,
                color = Color(0xCCF43F5E),
            )
        }

        for (box in boxes) {
            val isSelected = box.id == selectedBoxId
            val topLeft = viewport.normalizedToScreen(box.xmin, box.ymin)
            val bottomRight = viewport.normalizedToScreen(box.xmax, box.ymax)
            val boxSize = Size(
                width = (bottomRight.x - topLeft.x).coerceAtLeast(2f),
                height = (bottomRight.y - topLeft.y).coerceAtLeast(2f),
            )

            drawRect(
                color = ElectricCyan.copy(alpha = if (isSelected) 0.24f else 0.14f),
                topLeft = topLeft,
                size = boxSize,
            )

            drawRect(
                color = ElectricCyan,
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(width = if (isSelected) borderStrokePx * 1.25f else borderStrokePx),
            )

            if (isSelected) {
                val corners = listOf(
                    topLeft,
                    Offset(bottomRight.x, topLeft.y),
                    Offset(topLeft.x, bottomRight.y),
                    bottomRight,
                )
                for (corner in corners) {
                    drawCircle(
                        color = Color.White,
                        radius = handleDrawRadiusPx,
                        center = corner,
                    )
                    drawCircle(
                        color = ElectricCyan,
                        radius = handleDrawRadiusPx * 0.72f,
                        center = corner,
                    )
                }
            }
        }

        if (currentDrag is ActiveDragState.DrawNewBox) {
            val xmin = min(currentDrag.startNorm.x, currentDrag.currentNorm.x)
            val xmax = max(currentDrag.startNorm.x, currentDrag.currentNorm.x)
            val ymin = min(currentDrag.startNorm.y, currentDrag.currentNorm.y)
            val ymax = max(currentDrag.startNorm.y, currentDrag.currentNorm.y)
            val topLeft = viewport.normalizedToScreen(xmin, ymin)
            val bottomRight = viewport.normalizedToScreen(xmax, ymax)
            val boxSize = Size(
                width = (bottomRight.x - topLeft.x).coerceAtLeast(1f),
                height = (bottomRight.y - topLeft.y).coerceAtLeast(1f),
            )
            drawRoundRect(
                color = ElectricCyan.copy(alpha = 0.22f),
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = CornerRadius(4f, 4f),
            )
            drawRoundRect(
                color = ElectricCyan,
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = CornerRadius(4f, 4f),
                style = Stroke(width = borderStrokePx),
            )
        }
    }
}

private fun DrawScope.drawBrushStroke(
    stroke: BrushStroke,
    viewport: VideoViewportRect,
    minVideoDim: Float,
    color: Color,
) {
    val pts = stroke.points
    if (pts.isEmpty()) return
    val strokePx = (stroke.strokeWidthNorm * minVideoDim).coerceAtLeast(4f)
    if (pts.size == 1) {
        drawCircle(
            color = color,
            radius = strokePx / 2f,
            center = viewport.normalizedToScreen(pts[0].x, pts[0].y),
        )
        return
    }
    val path = Path().apply {
        val first = viewport.normalizedToScreen(pts[0].x, pts[0].y)
        moveTo(first.x, first.y)
        for (i in 1 until pts.size) {
            val pt = viewport.normalizedToScreen(pts[i].x, pts[i].y)
            lineTo(pt.x, pt.y)
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = strokePx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun findHitCorner(
    box: WatermarkBox,
    viewport: VideoViewportRect,
    touchPos: Offset,
    hitRadiusPx: Float,
): ResizeCorner? {
    val tl = viewport.normalizedToScreen(box.xmin, box.ymin)
    val tr = viewport.normalizedToScreen(box.xmax, box.ymin)
    val bl = viewport.normalizedToScreen(box.xmin, box.ymax)
    val br = viewport.normalizedToScreen(box.xmax, box.ymax)

    val candidates = listOf(
        ResizeCorner.TOP_LEFT to hypot(touchPos.x - tl.x, touchPos.y - tl.y),
        ResizeCorner.TOP_RIGHT to hypot(touchPos.x - tr.x, touchPos.y - tr.y),
        ResizeCorner.BOTTOM_LEFT to hypot(touchPos.x - bl.x, touchPos.y - bl.y),
        ResizeCorner.BOTTOM_RIGHT to hypot(touchPos.x - br.x, touchPos.y - br.y),
    )
    val closest = candidates.minByOrNull { it.second } ?: return null
    return if (closest.second <= hitRadiusPx) closest.first else null
}

private fun applyCornerResize(
    box: WatermarkBox,
    corner: ResizeCorner,
    dxNorm: Float,
    dyNorm: Float,
): WatermarkBox {
    val minSpan = 0.04f
    var xmin = box.xmin
    var ymin = box.ymin
    var xmax = box.xmax
    var ymax = box.ymax

    when (corner) {
        ResizeCorner.TOP_LEFT -> {
            xmin = (xmin + dxNorm).coerceIn(0f, xmax - minSpan)
            ymin = (ymin + dyNorm).coerceIn(0f, ymax - minSpan)
        }
        ResizeCorner.TOP_RIGHT -> {
            xmax = (xmax + dxNorm).coerceIn(xmin + minSpan, 1f)
            ymin = (ymin + dyNorm).coerceIn(0f, ymax - minSpan)
        }
        ResizeCorner.BOTTOM_LEFT -> {
            xmin = (xmin + dxNorm).coerceIn(0f, xmax - minSpan)
            ymax = (ymax + dyNorm).coerceIn(ymin + minSpan, 1f)
        }
        ResizeCorner.BOTTOM_RIGHT -> {
            xmax = (xmax + dxNorm).coerceIn(xmin + minSpan, 1f)
            ymax = (ymax + dyNorm).coerceIn(ymin + minSpan, 1f)
        }
    }
    return box.copy(xmin = xmin, ymin = ymin, xmax = xmax, ymax = ymax)
}

private fun moveBoxPreservingSize(
    box: WatermarkBox,
    dxNorm: Float,
    dyNorm: Float,
): WatermarkBox {
    val boxW = box.xmax - box.xmin
    val boxH = box.ymax - box.ymin
    val newXMin = (box.xmin + dxNorm).coerceIn(0f, (1f - boxW).coerceAtLeast(0f))
    val newYMin = (box.ymin + dyNorm).coerceIn(0f, (1f - boxH).coerceAtLeast(0f))
    return box.copy(
        xmin = newXMin,
        ymin = newYMin,
        xmax = (newXMin + boxW).coerceAtMost(1f),
        ymax = (newYMin + boxH).coerceAtMost(1f),
    )
}
