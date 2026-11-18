package com.example.data.media

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.data.model.BrushStroke
import com.example.data.model.ProcessingProgressState
import com.example.data.model.VideoMetadataInfo
import com.example.data.model.WatermarkBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 100% offline on-device video watermark remover powered by:
 * - Android MediaExtractor + MediaCodec (YUV_420_888 ByteBuffer decode & H.264 encode)
 * - OpenCV Photo.inpaint(..., Photo.INPAINT_TELEA) over padded watermark ROIs
 * - Android MediaMuxer preserving the original audio track untouched
 */
class VideoInpainter(private val context: Context) {

    @Volatile
    private var openCvInitialized = false

    private fun ensureOpenCvLoaded() {
        if (!openCvInitialized) {
            synchronized(this) {
                if (!openCvInitialized) {
                    val loaded = OpenCVLoader.initLocal()
                    if (!loaded) {
                        throw IllegalStateException("Failed to initialize OpenCV native library.")
                    }
                    openCvInitialized = true
                }
            }
        }
    }

    suspend fun validateVideoUri(uri: Uri): Result<VideoMetadataInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: "video/mp4"
            if (mimeType != "video/mp4" && mimeType != "video/quicktime" && !mimeType.startsWith("video/")) {
                throw IllegalArgumentException("Unsupported file type. Please select an MP4 or MOV video.")
            }

            var displayName = "video.mp4"
            var sizeBytes = 0L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) {
                            displayName = cursor.getString(nameIdx) ?: displayName
                        }
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            sizeBytes = cursor.getLong(sizeIdx)
                        }
                    }
                }

            val lowerName = displayName.lowercase()
            if (!lowerName.endsWith(".mp4") &&
                !lowerName.endsWith(".mov") &&
                !lowerName.endsWith(".m4v") &&
                mimeType != "video/mp4" &&
                mimeType != "video/quicktime"
            ) {
                throw IllegalArgumentException("Only MP4 and MOV videos are supported.")
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0

                if (durationMs <= 0L || rawWidth <= 0 || rawHeight <= 0) {
                    throw IllegalArgumentException("Could not read video dimensions or duration.")
                }
                if (durationMs > MAX_DURATION_MS) {
                    throw IllegalArgumentException("Video exceeds the 90-second maximum duration.")
                }

                val (displayWidth, displayHeight) = if (rotation == 90 || rotation == 270) {
                    rawHeight to rawWidth
                } else {
                    rawWidth to rawHeight
                }

                val shortSide = min(displayWidth, displayHeight)
                val longSide = max(displayWidth, displayHeight)
                if (shortSide > MAX_SHORT_SIDE || longSide > MAX_LONG_SIDE) {
                    throw IllegalArgumentException(
                        "Video resolution (${displayWidth}x${displayHeight}) exceeds 1080p maximum."
                    )
                }

                VideoMetadataInfo(
                    uri = uri,
                    displayName = displayName,
                    width = displayWidth,
                    height = displayHeight,
                    durationMs = durationMs,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                )
            } finally {
                retriever.release()
            }
        }
    }

    fun processVideo(
        metadata: VideoMetadataInfo,
        boxes: List<WatermarkBox>,
        brushStrokes: List<BrushStroke>,
    ): Flow<ProcessingProgressState> = flow {
        if (boxes.isEmpty() && brushStrokes.isEmpty()) {
            emit(ProcessingProgressState.Failed("Select at least one watermark region first."))
            return@flow
        }

        val outputFile = File(context.cacheDir, "clearmark_offline_${System.currentTimeMillis()}.mp4")
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        // Reusable OpenCV Mats released in finally
        var displayMask: Mat? = null
        var codedMask: Mat? = null
        var yuvI420Mat: Mat? = null
        var bgrMat: Mat? = null
        var roiMaskMat: Mat? = null
        var roiSrcMat: Mat? = null
        var roiDstMat: Mat? = null

        try {
            emit(ProcessingProgressState.Processing(0, 0, "Initializing OpenCV engine..."))
            ensureOpenCvLoaded()

            videoExtractor = MediaExtractor().apply {
                setDataSource(context, metadata.uri, null)
            }

            val videoTrackIdx = findTrackIndex(videoExtractor, "video/")
            if (videoTrackIdx < 0) {
                throw IllegalStateException("No video track found in selected file.")
            }
            videoExtractor.selectTrack(videoTrackIdx)
            val inputVideoFormat = videoExtractor.getTrackFormat(videoTrackIdx)
            val inputMime = inputVideoFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Missing video MIME type.")

            val codedWidth = inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val codedHeight = inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = readVideoRotation(metadata.uri, inputVideoFormat)
            val durationUs = if (inputVideoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputVideoFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            } else {
                (metadata.durationMs * 1000L).coerceAtLeast(1L)
            }
            val frameRate = if (inputVideoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                inputVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 120)
            } else {
                30
            }

            val (displayW, displayH) = if (rotation == 90 || rotation == 270) {
                codedHeight to codedWidth
            } else {
                codedWidth to codedHeight
            }

            // Build binary mask in display orientation, then rotate once into coded orientation
            displayMask = buildDisplayMaskMat(displayW, displayH, boxes, brushStrokes)
            codedMask = rotateMaskToCodedOrientation(displayMask, rotation)

            val roiRect = computePaddedMaskBoundingRect(codedMask, paddingPx = 24)
                ?: throw IllegalArgumentException("Selected mask region is empty.")

            // Check optional audio track
            audioExtractor = MediaExtractor().apply {
                setDataSource(context, metadata.uri, null)
            }
            val audioTrackIdx = findTrackIndex(audioExtractor, "audio/")
            val audioFormat: MediaFormat? = if (audioTrackIdx >= 0) {
                audioExtractor.selectTrack(audioTrackIdx)
                audioExtractor.getTrackFormat(audioTrackIdx)
            } else {
                null
            }

            // Configure Decoder (YUV_420_888 ByteBuffer output)
            inputVideoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            decoder = MediaCodec.createDecoderByType(inputMime).apply {
                configure(inputVideoFormat, null, null, 0)
                start()
            }

            // Configure H.264 Encoder matching coded dimensions
            val encoderWidth = codedWidth - (codedWidth % 2)
            val encoderHeight = codedHeight - (codedHeight % 2)
            val targetBitrate = estimateTargetBitrate(encoderWidth, encoderHeight, frameRate)
            val (selectedEncoderName, negotiatedColorFormat) = selectAvcEncoderAndColorFormat()

            val encoderFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                encoderWidth,
                encoderHeight,
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, negotiatedColorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = if (selectedEncoderName != null) {
                MediaCodec.createByCodecName(selectedEncoderName)
            } else {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }.apply {
                configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(rotation)
            }

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1

            // Preallocate reusable Mats
            yuvI420Mat = Mat(encoderHeight + encoderHeight / 2, encoderWidth, CvType.CV_8UC1)
            bgrMat = Mat(encoderHeight, encoderWidth, CvType.CV_8UC3)
            roiMaskMat = codedMask.submat(
                Rect(
                    roiRect.x.coerceIn(0, encoderWidth - 1),
                    roiRect.y.coerceIn(0, encoderHeight - 1),
                    min(roiRect.width, encoderWidth - roiRect.x).coerceAtLeast(1),
                    min(roiRect.height, encoderHeight - roiRect.y).coerceAtLeast(1),
                )
            )
            val safeRoi = Rect(
                roiRect.x.coerceIn(0, encoderWidth - 1),
                roiRect.y.coerceIn(0, encoderHeight - 1),
                min(roiRect.width, encoderWidth - roiRect.x).coerceAtLeast(1),
                min(roiRect.height, encoderHeight - roiRect.y).coerceAtLeast(1),
            )
            roiSrcMat = Mat()
            roiDstMat = Mat()

            val decoderBufferInfo = MediaCodec.BufferInfo()
            val encoderBufferInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderInputDone = false
            var encoderDone = false
            var framesProcessed = 0
            var lastReportedPct = -1
            var lastVideoPtsUs = 0L

            val yuvScratchBuffer = ByteArray(encoderWidth * encoderHeight * 3 / 2)

            fun drainEncoderOnce(): Boolean {
                var drainedAny = false
                while (true) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encoderBufferInfo, CODEC_TIMEOUT_US)
                    when {
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) {
                                throw IllegalStateException("Encoder output format changed twice.")
                            }
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            if (audioFormat != null) {
                                muxerAudioTrack = try {
                                    muxer.addTrack(audioFormat)
                                } catch (_: Throwable) {
                                    -1
                                }
                            }
                            muxer.start()
                            muxerStarted = true
                            drainedAny = true
                        }
                        encOutIdx >= 0 -> {
                            val encodedBuf = encoder.getOutputBuffer(encOutIdx)
                            if (encodedBuf != null &&
                                encoderBufferInfo.size > 0 &&
                                (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                muxerStarted
                            ) {
                                encodedBuf.position(encoderBufferInfo.offset)
                                encodedBuf.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                                muxer.writeSampleData(muxerVideoTrack, encodedBuf, encoderBufferInfo)
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            drainedAny = true
                            if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderDone = true
                                break
                            }
                        }
                    }
                }
                return drainedAny
            }

            emit(ProcessingProgressState.Processing(1, 0, "Decoding & inpainting frames..."))

            while (!encoderDone) {
                currentCoroutineContext().ensureActive()

                // 1. Feed compressed packets into Decoder
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = videoExtractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                extractorDone = true
                            } else {
                                val ptsUs = videoExtractor.sampleTime
                                val flags = videoExtractor.sampleFlags
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, flags)
                                videoExtractor.advance()
                            }
                        }
                    }
                }

                // 2. Pull decoded YUV_420_888 Image -> OpenCV Inpaint -> Queue into Encoder
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decoderBufferInfo, CODEC_TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        outIdx >= 0 -> {
                            val isEos = (decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            val doRender = decoderBufferInfo.size > 0 &&
                                (decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0

                            if (doRender) {
                                val decodedImage = decoder.getOutputImage(outIdx)
                                if (decodedImage != null) {
                                    decodedImage.use { img ->
                                        imageToI420Mat(img, encoderWidth, encoderHeight, yuvI420Mat, yuvScratchBuffer)
                                    }
                                    Imgproc.cvtColor(yuvI420Mat, bgrMat, Imgproc.COLOR_YUV2BGR_I420)

                                    // Fast padded-ROI Telea inpainting
                                    val subFrame = bgrMat.submat(safeRoi)
                                    subFrame.copyTo(roiSrcMat)
                                    Photo.inpaint(roiSrcMat, roiMaskMat, roiDstMat, INPAINT_RADIUS_PX, Photo.INPAINT_TELEA)
                                    roiDstMat.copyTo(subFrame)
                                    subFrame.release()

                                    Imgproc.cvtColor(bgrMat, yuvI420Mat, Imgproc.COLOR_BGR2YUV_I420)
                                    yuvI420Mat.get(0, 0, yuvScratchBuffer)

                                    lastVideoPtsUs = decoderBufferInfo.presentationTimeUs
                                    queueI420FrameToEncoder(
                                        encoder = encoder,
                                        i420Bytes = yuvScratchBuffer,
                                        width = encoderWidth,
                                        height = encoderHeight,
                                        colorFormat = negotiatedColorFormat,
                                        ptsUs = lastVideoPtsUs,
                                        onNeedDrain = { drainEncoderOnce() },
                                    )

                                    framesProcessed++
                                    val pct = ((lastVideoPtsUs * 92L) / durationUs)
                                        .toInt()
                                        .coerceIn(1, 92)
                                    if (pct != lastReportedPct) {
                                        lastReportedPct = pct
                                        emit(
                                            ProcessingProgressState.Processing(
                                                progressPercent = pct,
                                                framesProcessed = framesProcessed,
                                                message = "Inpainting frame $framesProcessed ($pct%)",
                                            )
                                        )
                                    }
                                }
                            }

                            decoder.releaseOutputBuffer(outIdx, false)

                            if (isEos && !encoderInputDone) {
                                signalEncoderEndOfStream(
                                    encoder = encoder,
                                    lastPtsUs = lastVideoPtsUs,
                                    onNeedDrain = { drainEncoderOnce() },
                                )
                                encoderInputDone = true
                                decoderDone = true
                            }
                        }
                    }
                }

                // 3. Drain encoded H.264 packets into MediaMuxer
                drainEncoderOnce()
            }

            // 4. Mux untouched original audio track (if present and supported)
            if (muxerStarted && muxerAudioTrack >= 0 && audioExtractor != null) {
                emit(ProcessingProgressState.Processing(95, framesProcessed, "Preserving original audio track..."))
                copyAudioSamples(audioExtractor, muxer, muxerAudioTrack)
            }

            if (muxerStarted) {
                muxer.stop()
                muxerStarted = false
            }

            emit(ProcessingProgressState.Processing(100, framesProcessed, "Finalizing MP4..."))
            emit(ProcessingProgressState.Completed(outputFile))
        } catch (ce: CancellationException) {
            outputFile.delete()
            throw ce
        } catch (t: Throwable) {
            outputFile.delete()
            emit(
                ProcessingProgressState.Failed(
                    t.localizedMessage ?: "On-device video processing failed."
                )
            )
        } finally {
            displayMask?.release()
            codedMask?.release()
            yuvI420Mat?.release()
            bgrMat?.release()
            roiMaskMat?.release()
            roiSrcMat?.release()
            roiDstMat?.release()

            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }.flowOn(Dispatchers.Default)

    suspend fun saveVideoToGallery(sourceFile: File, originalFileName: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!sourceFile.exists() || sourceFile.length() == 0L) {
                    throw IllegalStateException("Cleaned video file is missing or empty.")
                }

                val baseName = originalFileName.substringBeforeLast(".").ifBlank { "video" }
                val exportName = "${baseName}_clean_${System.currentTimeMillis()}.mp4"
                val resolver = context.contentResolver

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, exportName)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(
                            MediaStore.Video.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_MOVIES}/ClearMark"
                        )
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val targetUri = resolver.insert(collection, values)
                        ?: throw IOException("Failed to create MediaStore video entry.")

                    try {
                        resolver.openOutputStream(targetUri)?.use { out ->
                            sourceFile.inputStream().use { input ->
                                input.copyTo(out, bufferSize = 64 * 1024)
                            }
                        } ?: throw IOException("Unable to open output stream for MediaStore URI.")

                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        resolver.update(targetUri, values, null, null)
                        targetUri
                    } catch (e: Throwable) {
                        resolver.delete(targetUri, null, null)
                        throw e
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    val appDir = File(moviesDir, "ClearMark").apply { mkdirs() }
                    val destFile = File(appDir, exportName)
                    sourceFile.copyTo(destFile, overwrite = true)

                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, exportName)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        @Suppress("DEPRECATION")
                        put(MediaStore.Video.Media.DATA, destFile.absolutePath)
                    }
                    resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: Uri.fromFile(destFile)
                }
            }
        }

    // ---------------------------------------------------------------------------
    // Mask Construction & Rotation Alignment
    // ---------------------------------------------------------------------------

    private fun buildDisplayMaskMat(
        width: Int,
        height: Int,
        boxes: List<WatermarkBox>,
        brushStrokes: List<BrushStroke>,
    ): Mat {
        val mask = Mat.zeros(height, width, CvType.CV_8UC1)
        val white = Scalar(255.0)

        for (rawBox in boxes) {
            val box = rawBox.clamped()
            val x1 = max(0, min(width - 1, floor(box.xmin * width).toInt()))
            val y1 = max(0, min(height - 1, floor(box.ymin * height).toInt()))
            val x2 = max(0, min(width - 1, ceil(box.xmax * width).toInt()))
            val y2 = max(0, min(height - 1, ceil(box.ymax * height).toInt()))
            if (x2 > x1 && y2 > y1) {
                Imgproc.rectangle(mask, Point(x1.toDouble(), y1.toDouble()), Point(x2.toDouble(), y2.toDouble()), white, -1)
            }
        }

        val minDim = min(width, height).toFloat()
        for (stroke in brushStrokes) {
            if (stroke.points.isEmpty()) continue
            val radiusPx = max(2, ((stroke.strokeWidthNorm * minDim) / 2f).roundToInt())
            val diameterPx = radiusPx * 2
            val pts = stroke.points.map { pt ->
                Point(
                    max(0, min(width - 1, (pt.x * width).roundToInt())).toDouble(),
                    max(0, min(height - 1, (pt.y * height).roundToInt())).toDouble(),
                )
            }
            if (pts.size == 1) {
                Imgproc.circle(mask, pts[0], radiusPx, white, -1)
            } else {
                for (i in 0 until pts.size - 1) {
                    Imgproc.line(mask, pts[i], pts[i + 1], white, diameterPx, Imgproc.LINE_AA, 0)
                    Imgproc.circle(mask, pts[i], radiusPx, white, -1)
                }
                Imgproc.circle(mask, pts.last(), radiusPx, white, -1)
            }
        }

        // Mild dilation ensures clean edge blending with INPAINT_TELEA
        val kernelSize = max(5.0, ((minDim * 0.008f).roundToInt() or 1).toDouble())
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kernelSize, kernelSize))
        Imgproc.dilate(mask, mask, kernel)
        kernel.release()

        Imgproc.threshold(mask, mask, 127.0, 255.0, Imgproc.THRESH_BINARY)
        return mask
    }

    private fun rotateMaskToCodedOrientation(displayMask: Mat, rotationDeg: Int): Mat {
        val rotated = Mat()
        when ((rotationDeg % 360 + 360) % 360) {
            90 -> Core.rotate(displayMask, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            180 -> Core.rotate(displayMask, rotated, Core.ROTATE_180)
            270 -> Core.rotate(displayMask, rotated, Core.ROTATE_90_CLOCKWISE)
            else -> displayMask.copyTo(rotated)
        }
        return rotated
    }

    private fun computePaddedMaskBoundingRect(mask: Mat, paddingPx: Int): Rect? {
        val nonZero = Mat()
        try {
            Core.findNonZero(mask, nonZero)
            if (nonZero.empty()) return null
            val bounding = Imgproc.boundingRect(nonZero)
            val x1 = (bounding.x - paddingPx).coerceAtLeast(0)
            val y1 = (bounding.y - paddingPx).coerceAtLeast(0)
            val x2 = (bounding.x + bounding.width + paddingPx).coerceAtMost(mask.cols())
            val y2 = (bounding.y + bounding.height + paddingPx).coerceAtMost(mask.rows())
            return Rect(x1, y1, (x2 - x1).coerceAtLeast(1), (y2 - y1).coerceAtLeast(1))
        } finally {
            nonZero.release()
        }
    }

    // ---------------------------------------------------------------------------
    // YUV_420_888 <-> I420 <-> Encoder Helpers
    // ---------------------------------------------------------------------------

    private fun imageToI420Mat(
        image: Image,
        targetWidth: Int,
        targetHeight: Int,
        dstI420Mat: Mat,
        scratchBuffer: ByteArray,
    ) {
        if (image.format != ImageFormat.YUV_420_888) {
            throw IllegalStateException("Unexpected decoded image format: ${image.format}")
        }
        val planes = image.planes
        val cropW = min(targetWidth, image.width)
        val cropH = min(targetHeight, image.height)

        var offset = 0
        // Plane 0: Y
        offset = copyPlaneToTightBuffer(
            plane = planes[0],
            width = cropW,
            height = cropH,
            dst = scratchBuffer,
            dstOffset = offset,
        )
        // Plane 1: U (Cb)
        offset = copyPlaneToTightBuffer(
            plane = planes[1],
            width = cropW / 2,
            height = cropH / 2,
            dst = scratchBuffer,
            dstOffset = offset,
        )
        // Plane 2: V (Cr)
        copyPlaneToTightBuffer(
            plane = planes[2],
            width = cropW / 2,
            height = cropH / 2,
            dst = scratchBuffer,
            dstOffset = offset,
        )

        dstI420Mat.put(0, 0, scratchBuffer)
    }

    private fun copyPlaneToTightBuffer(
        plane: Image.Plane,
        width: Int,
        height: Int,
        dst: ByteArray,
        dstOffset: Int,
    ): Int {
        val srcBuf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var outPos = dstOffset

        if (pixelStride == 1 && rowStride == width) {
            srcBuf.position(0)
            val total = width * height
            srcBuf.get(dst, outPos, total)
            return outPos + total
        }

        val rowBytes = ByteArray(rowStride)
        for (row in 0 until height) {
            val rowStart = row * rowStride
            srcBuf.position(rowStart)
            val toRead = min(rowStride, srcBuf.remaining())
            srcBuf.get(rowBytes, 0, toRead)
            if (pixelStride == 1) {
                System.arraycopy(rowBytes, 0, dst, outPos, width)
                outPos += width
            } else {
                var colSrc = 0
                for (col in 0 until width) {
                    dst[outPos++] = rowBytes[colSrc]
                    colSrc += pixelStride
                }
            }
        }
        return outPos
    }

    private fun queueI420FrameToEncoder(
        encoder: MediaCodec,
        i420Bytes: ByteArray,
        width: Int,
        height: Int,
        colorFormat: Int,
        ptsUs: Long,
        onNeedDrain: () -> Unit,
    ) {
        var inIdx = -1
        for (attempt in 0 until 60) {
            inIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inIdx >= 0) break
            onNeedDrain()
        }
        if (inIdx < 0) {
            throw IllegalStateException("Video encoder input buffer timed out.")
        }

        val inputImage = runCatching { encoder.getInputImage(inIdx) }.getOrNull()
        if (inputImage != null) {
            inputImage.use { img ->
                writeI420ToInputImage(i420Bytes, width, height, img)
            }
            val frameSize = width * height * 3 / 2
            encoder.queueInputBuffer(inIdx, 0, frameSize, ptsUs, 0)
            return
        }

        val inBuf = encoder.getInputBuffer(inIdx)
            ?: run {
                encoder.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                return
            }
        inBuf.clear()
        val ySize = width * height
        val uvSize = ySize / 4

        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            inBuf.put(i420Bytes, 0, ySize)
            val uStart = ySize
            val vStart = ySize + uvSize
            for (i in 0 until uvSize) {
                inBuf.put(i420Bytes[uStart + i])
                inBuf.put(i420Bytes[vStart + i])
            }
        } else {
            inBuf.put(i420Bytes, 0, ySize + uvSize * 2)
        }

        encoder.queueInputBuffer(inIdx, 0, ySize + uvSize * 2, ptsUs, 0)
    }

    private fun writeI420ToInputImage(
        i420Bytes: ByteArray,
        width: Int,
        height: Int,
        image: Image,
    ) {
        val planes = image.planes
        val ySize = width * height
        val uvSize = ySize / 4

        writeTightPlaneToImagePlane(i420Bytes, 0, width, height, planes[0])
        writeTightPlaneToImagePlane(i420Bytes, ySize, width / 2, height / 2, planes[1])
        writeTightPlaneToImagePlane(i420Bytes, ySize + uvSize, width / 2, height / 2, planes[2])
    }

    private fun writeTightPlaneToImagePlane(
        src: ByteArray,
        srcOffset: Int,
        width: Int,
        height: Int,
        dstPlane: Image.Plane,
    ) {
        val dstBuf = dstPlane.buffer
        val rowStride = dstPlane.rowStride
        val pixelStride = dstPlane.pixelStride
        dstBuf.position(0)

        if (pixelStride == 1 && rowStride == width) {
            dstBuf.put(src, srcOffset, width * height)
            return
        }

        var inPos = srcOffset
        for (row in 0 until height) {
            val rowBase = row * rowStride
            if (pixelStride == 1) {
                dstBuf.position(rowBase)
                dstBuf.put(src, inPos, width)
                inPos += width
            } else {
                for (col in 0 until width) {
                    dstBuf.put(rowBase + col * pixelStride, src[inPos++])
                }
            }
        }
    }

    private fun signalEncoderEndOfStream(
        encoder: MediaCodec,
        lastPtsUs: Long,
        onNeedDrain: () -> Unit,
    ) {
        for (attempt in 0 until 60) {
            val inIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US * 2)
            if (inIdx >= 0) {
                encoder.queueInputBuffer(
                    inIdx,
                    0,
                    0,
                    lastPtsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                return
            }
            onNeedDrain()
        }
    }

    private fun copyAudioSamples(
        audioExtractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerAudioTrack: Int,
    ) {
        val buffer = ByteBuffer.allocateDirect(256 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val sampleSize = audioExtractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = audioExtractor.sampleTime
            bufferInfo.flags = audioExtractor.sampleFlags
            muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
            audioExtractor.advance()
        }
    }

    private fun selectAvcEncoderAndColorFormat(): Pair<String?, Int> {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) {
                continue
            }
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull()
                ?: continue
            val formats = caps.colorFormats
            if (formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
                return info.name to MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }
            if (formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
                return info.name to MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            }
            if (formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
                return info.name to MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            }
        }
        return null to MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    }

    private fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) {
                return i
            }
        }
        return -1
    }

    private fun readVideoRotation(uri: Uri, format: MediaFormat): Int {
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            return format.getInteger(MediaFormat.KEY_ROTATION)
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            0
        } finally {
            retriever.release()
        }
    }

    private fun estimateTargetBitrate(width: Int, height: Int, fps: Int): Int {
        val pixels = width.toLong() * height.toLong()
        val bps = (pixels * fps * 0.14).toInt()
        return bps.coerceIn(1_500_000, 12_000_000)
    }

    private companion object {
        const val MAX_DURATION_MS = 90_500L
        const val MAX_SHORT_SIDE = 1080
        const val MAX_LONG_SIDE = 1920
        const val CODEC_TIMEOUT_US = 10_000L
        const val INPAINT_RADIUS_PX = 5.0
    }
}
