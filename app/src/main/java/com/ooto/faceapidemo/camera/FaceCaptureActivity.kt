package com.ooto.faceapidemo.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.camera.view.PreviewView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import android.util.Rational
import android.view.Surface
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import com.ooto.faceapidemo.BuildConfig
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FaceCaptureActivity : ComponentActivity() {
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            setResult(Activity.RESULT_CANCELED)
            finish()
        } else {
            startUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startUI() {
        setContent {
            MaterialTheme {
                FaceCameraScreen(
                    onResult = { uri ->
                        setResult(Activity.RESULT_OK, Intent().setData(uri))
                        finish()
                    },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun FaceCameraScreen(
    onResult: (Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner

    var previewView: PreviewView? by remember { mutableStateOf(null) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }

    var pvSize by remember { mutableStateOf(IntSize.Zero) }



    // Пороговые условия: углы головы + стабильность (без центрирования)
    val thresholds = remember {
        DetectionThresholds(
            maxYaw = 20f,
            maxPitch = 20f,
            maxRoll = 20f,
            maxCenterOffset = 0.20f,  // оставлено для совместимости; центрирование не используем
            minFaceArea = 0.08f,      // сейчас не используется
            maxFaceArea = 0.35f,
            stableMs = 500L,
            frameMarginPx = 20
        )
    }

    val bestShot = remember(thresholds) { BestShotGate(thresholds) }
    val motionGate = remember { HeadMotionGate(velThresholdDegPerSec = 50f, stableMs = 250L, alpha = 0.35f) }

    // Контролы CameraX
    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context)
    }

    // Храним use cases, чтобы отвязать при переворотах и т.д.
    var analysis by remember { mutableStateOf<ImageAnalysis?>(null) }

    LaunchedEffect(cameraProviderFuture, lensFacing, pvSize) {
        val provider = cameraProviderFuture.get()
        provider.unbindAll()
        val pv = previewView ?: return@LaunchedEffect
        if (pvSize.width == 0 || pvSize.height == 0) return@LaunchedEffect
        val rotation = pv.display?.rotation ?: Surface.ROTATION_0

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }

        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .enableTracking() // стабильность ID между кадрами
                .build()
        )

        analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build().apply {
                setAnalyzer(
                    ContextCompat.getMainExecutor(context)
                ) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                    faceDetector.process(input)
                        .addOnSuccessListener(ContextCompat.getMainExecutor(context)) { faces ->
                            val okStatic = evaluateFacesOnImage(
                                faces = faces,
                                imageWidth = imageProxy.width,
                                imageHeight = imageProxy.height,
                                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                                thresholds = thresholds
                            )

                            val now = System.currentTimeMillis()
                            val face = faces.firstOrNull()
                            val stillOk = if (face != null) {
                                val trackId = face.trackingId ?: -1
                                val yaw = face.headEulerAngleY
                                val pitch = face.headEulerAngleX
                                val roll = face.headEulerAngleZ
                                motionGate.update(trackId, yaw, pitch, roll, now)
                            } else {
                                false
                            }

                            val okAll = okStatic && stillOk
                            if (bestShot.update(okAll, now) && face != null) {
                                val uri = saveFaceCropFromProxy(
                                    imageProxy = imageProxy,
                                    faceRect = face.boundingBox,
                                    paddingFraction = 0.20f, // +20% к ширине/высоте бокса
                                    activity = context as ComponentActivity,
                                    contextAuthority = "${context.packageName}.fileprovider"
                                )
                                onResult(uri)
                                // Остановим анализ, чтобы не стрелять повторно
                                this.clearAnalyzer()
                            }
                        }
                        .addOnFailureListener {
                            // ignore; just continue
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                }
            }

        val viewPort = ViewPort.Builder(Rational(pvSize.width, pvSize.height), rotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(analysis!!)
            .setViewPort(viewPort)
            .build()

        provider.bindToLifecycle(
            lifecycleOwner,
            selector,
            group
        )
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().onSizeChanged { pvSize = it },
            factory = { ctx ->
                PreviewView(ctx).also { pv -> previewView = pv }
            }
        )


        // Кнопка отмены
        TextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) { Text("Отмена") }
    }
}

private data class DetectionThresholds(
    val maxYaw: Float,
    val maxPitch: Float,
    val maxRoll: Float,
    val maxCenterOffset: Float,
    val minFaceArea: Float,
    val maxFaceArea: Float,
    val stableMs: Long,
    val frameMarginPx: Int
)

/**
 * Проверка: одно лицо, по центру, голова прямо, разумный размер.
 * Используются размеры кадра из ImageProxy (оригинальная система координат изображения).
 */
private fun evaluateFacesOnImage(
    faces: List<Face>,
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
    thresholds: DetectionThresholds
): Boolean {
    if (faces.size != 1) return false
    val f = faces[0]

    // Минимальный размер 200 px
    val bboxWpx = f.boundingBox.width()
    val bboxHpx = f.boundingBox.height()
    if (bboxWpx < 200 || bboxHpx < 200) return false

    // Координатная система bbox соответствует ориентации InputImage (ML Kit учитывает rotationDegrees).
    // Поэтому используем "эффективные" размеры кадра после поворота.
    val effW = if (rotationDegrees % 180 == 0) imageWidth else imageHeight
    val effH = if (rotationDegrees % 180 == 0) imageHeight else imageWidth

    // Весь бокс внутри кадра с отступом frameMarginPx
    val m = thresholds.frameMarginPx
    val bx0 = f.boundingBox.left
    val by0 = f.boundingBox.top
    val bx1 = f.boundingBox.right
    val by1 = f.boundingBox.bottom
    if (bx0 < m || by0 < m || bx1 > (effW - m) || by1 > (effH - m)) return false

    // Углы
    val yaw = kotlin.math.abs(f.headEulerAngleY)
    val roll = kotlin.math.abs(f.headEulerAngleZ)
    val pitch = kotlin.math.abs(f.headEulerAngleX)
    if (yaw > thresholds.maxYaw || roll > thresholds.maxRoll || pitch > thresholds.maxPitch) return false

    if (BuildConfig.DEBUG) {
        android.util.Log.d("FaceEval", "OK: bbox=${f.boundingBox} eff=${effW}x${effH} yaw=$yaw pitch=$pitch roll=$roll")
    }
    return true
}

private class BestShotGate(private val th: DetectionThresholds) {
    private var startOk: Long? = null
    fun update(okNow: Boolean, now: Long): Boolean {
        if (!okNow) { startOk = null; return false }
        val s = startOk ?: run { startOk = now; now }
        return (now - s) >= th.stableMs
    }
}

/**
 * Отслеживает "перестал мотать головой": сглаживает угловую скорость (EMA) и
 * требует, чтобы она оставалась ниже порога velThresholdDegPerSec заданное время (stableMs).
 */
private class HeadMotionGate(
    private val velThresholdDegPerSec: Float = 25f,
    private val stableMs: Long = 400L,
    private val alpha: Float = 0.5f
) {
    private var lastTrackId: Int? = null
    private var lastYaw: Float? = null
    private var lastPitch: Float? = null
    private var lastRoll: Float? = null
    private var lastTs: Long? = null
    private var emaVel: Float = 0f
    private var stillStart: Long? = null

    fun reset() {
        lastTrackId = null
        lastYaw = null
        lastPitch = null
        lastRoll = null
        lastTs = null
        emaVel = 0f
        stillStart = null
    }

    fun update(trackId: Int, yaw: Float, pitch: Float, roll: Float, nowMs: Long): Boolean {
        val lt = lastTs
        val sameTrack = (lastTrackId == trackId && lt != null)
        if (!sameTrack) {
            lastTrackId = trackId
            lastYaw = yaw
            lastPitch = pitch
            lastRoll = roll
            lastTs = nowMs
            emaVel = velThresholdDegPerSec // мягкий старт вместо 999
            stillStart = null
            return false
        }

        val dtSec = ((nowMs - lt!!).coerceAtLeast(1)).toFloat() / 1000f
        val dy = kotlin.math.abs(yaw - (lastYaw ?: yaw))
        val dp = kotlin.math.abs(pitch - (lastPitch ?: pitch))
        val dr = kotlin.math.abs(roll - (lastRoll ?: roll))
        // Вместо суммы берём максимум компонент, чтобы мелкая тряска по всем осям не складывалась
        val velRaw = kotlin.math.max(dy, kotlin.math.max(dp, dr)) / dtSec
        // Дедбанд для отсечки микродвижений
        val deadBand = 8f // deg/s
        val vel = if (velRaw <= deadBand) 0f else (velRaw - deadBand)

        // Болеe плавное EMA
        emaVel = alpha * vel + (1f - alpha) * emaVel

        lastYaw = yaw
        lastPitch = pitch
        lastRoll = roll
        lastTs = nowMs

        val still = emaVel <= velThresholdDegPerSec
        if (still) {
            if (stillStart == null) stillStart = nowMs
        } else {
            stillStart = null
        }
        return still && (nowMs - (stillStart ?: nowMs)) >= stableMs
    }
}

/**
 * Центр‑кроп по доле смещения от краёв (maxCenterOffset). Например, при 0.2 остаётся 60% ширины/высоты.
 */
private fun cropCenterByOffset(source: Bitmap, maxCenterOffset: Float): Bitmap {
    val frac = (1f - 2f * maxCenterOffset).coerceIn(0.1f, 1f) // защита от вырожденного прямоугольника
    val cropW = (source.width * frac).toInt().coerceAtLeast(1)
    val cropH = (source.height * frac).toInt().coerceAtLeast(1)
    val left = ((source.width - cropW) / 2).coerceAtLeast(0)
    val top  = ((source.height - cropH) / 2).coerceAtLeast(0)
    return Bitmap.createBitmap(source, left, top, cropW, cropH)
}

/**
 * Конвертирует ImageProxy (YUV_420_888) в NV21 byte[], учитывая stride/pixelStride.
 */
private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val width = image.width
    val height = image.height

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val ySize = width * height
    val uvSize = width * height / 2
    val out = ByteArray(ySize + uvSize)

    // Copy Y
    var pos = 0
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    for (row in 0 until height) {
        var col = 0
        while (col < width) {
            out[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)
            col++
        }
    }

    // Copy interleaved VU (NV21 expects V then U)
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    val vBuffer = vPlane.buffer
    val uBuffer = uPlane.buffer
    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride
    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    for (row in 0 until chromaHeight) {
        var col = 0
        while (col < chromaWidth) {
            val v = vBuffer.get(row * vRowStride + col * vPixelStride)
            val u = uBuffer.get(row * uRowStride + col * uPixelStride)
            out[pos++] = v
            out[pos++] = u
            col++
        }
    }
    return out
}

/**
 * Преобразует ImageProxy в "upright" Bitmap: NV21 -> JPEG -> Bitmap + поворот по rotationDegrees.
 */
private fun imageProxyToUprightBitmap(imageProxy: ImageProxy): Bitmap {
    val nv21 = yuv420888ToNv21(imageProxy)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 95, out)
    val bytes = out.toByteArray()
    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalArgumentException("Failed to decode NV21 frame")

    val deg = imageProxy.imageInfo.rotationDegrees
    if (deg != 0) {
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        bmp = rotated
    }
    return bmp
}

/**
 * Сохраняет центр-кроп из текущего кадра (ImageProxy) во временный JPEG и возвращает Uri (FileProvider).
 */
private fun saveCenterCropFromProxy(
    imageProxy: ImageProxy,
    maxCenterOffset: Float,
    activity: ComponentActivity,
    contextAuthority: String
): Uri {
    val imagesDir = File(activity.cacheDir, "images").apply { mkdirs() }

    // 1) Получаем "upright" Bitmap из кадра анализатора
    val bmp = imageProxyToUprightBitmap(imageProxy)

    // 2) Центр‑кроп по рамке
    val cropped = cropCenterByOffset(bmp, maxCenterOffset)

    // 3) Пишем результат в файл
    val file = File(
        imagesDir,
        "face_crop_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg"
    )
    file.outputStream().use { fos ->
        cropped.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        fos.flush()
    }

    // Освобождаем память
    bmp.recycle()
    cropped.recycle()

    // 4) Возвращаем Uri на файл
    return FileProvider.getUriForFile(activity, contextAuthority, file)
}

private fun saveFaceCropFromProxy(
    imageProxy: ImageProxy,
    faceRect: Rect,
    paddingFraction: Float,
    activity: ComponentActivity,
    contextAuthority: String
): Uri {
    val imagesDir = File(activity.cacheDir, "images").apply { mkdirs() }
    val bmp = imageProxyToUprightBitmap(imageProxy)
    val cropped = cropByRectWithPadding(bmp, faceRect, paddingFraction)
    val file = File(
        imagesDir,
        "face_bbox_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg"
    )
    file.outputStream().use { fos ->
        cropped.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        fos.flush()
    }
    bmp.recycle()
    cropped.recycle()
    return FileProvider.getUriForFile(activity, contextAuthority, file)
}

/**
 * Кроп по заданному bbox с расширением на paddingFraction (0.20 => +20% к ширине/высоте).
 * Рамка центрируется относительно исходного bbox и обрезается по границам изображения.
 */
private fun cropByRectWithPadding(source: Bitmap, rect: Rect, paddingFraction: Float): Bitmap {
    val padFrac = paddingFraction.coerceAtLeast(0f)
    val w = source.width
    val h = source.height

    val cx = rect.centerX().toFloat()
    val cy = rect.centerY().toFloat()
    val bw = rect.width().toFloat().coerceAtLeast(1f)
    val bh = rect.height().toFloat().coerceAtLeast(1f)

    val newW = (bw * (1f + padFrac)).coerceAtMost(w.toFloat())
    val newH = (bh * (1f + padFrac)).coerceAtMost(h.toFloat())

    var left = (cx - newW / 2f)
    var top  = (cy - newH / 2f)
    var right = (cx + newW / 2f)
    var bottom = (cy + newH / 2f)

    // Кламп по границам
    if (left < 0f) { right -= left; left = 0f }
    if (top  < 0f) { bottom -= top; top = 0f }
    if (right > w) { left -= (right - w); right = w.toFloat() }
    if (bottom > h) { top -= (bottom - h); bottom = h.toFloat() }

    val il = left.roundToInt().coerceIn(0, w - 1)
    val it = top.roundToInt().coerceIn(0, h - 1)
    val ir = right.roundToInt().coerceIn(il + 1, w)
    val ib = bottom.roundToInt().coerceIn(it + 1, h)

    val cw = (ir - il).coerceAtLeast(1)
    val ch = (ib - it).coerceAtLeast(1)

    return Bitmap.createBitmap(source, il, it, cw, ch)
}