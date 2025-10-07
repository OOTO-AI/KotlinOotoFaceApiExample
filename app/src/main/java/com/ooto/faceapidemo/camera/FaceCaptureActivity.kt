package com.ooto.faceapidemo.camera

import kotlinx.coroutines.delay
import java.io.File
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
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color

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

    private val EXTRA_RESULT_REASON = "result_reason"
    private val RESULT_REASON_TIMEOUT = "timeout"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun cleanupOldCacheImages(context: android.content.Context, olderThanMs: Long = 24 * 60 * 60 * 1000L) {
        val dir = File(context.cacheDir, "images")
        val now = System.currentTimeMillis()
        if (!dir.exists()) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".jpg", ignoreCase = true) || f.name.endsWith(".jpeg", ignoreCase = true))) {
                val age = now - f.lastModified()
                if (age > olderThanMs) {
                    runCatching { f.delete() }
                }
            }
        }
    }

    private fun startUI() {
        // Очистим старые кэши изображений (старше суток) — п.18
        cleanupOldCacheImages(this, olderThanMs = 24 * 60 * 60 * 1000L)
        setContent {
            MaterialTheme {
                FaceCameraScreen(
                    onResult = { uri ->
                        val intent = Intent()
                            .setDataAndType(uri, "image/jpeg")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        setResult(Activity.RESULT_OK, intent)
                        finish()
                    },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onTimeout = {
                        val intent = Intent().putExtra(EXTRA_RESULT_REASON, RESULT_REASON_TIMEOUT)
                        setResult(Activity.RESULT_CANCELED, intent)
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
    onCancel: () -> Unit,
    onTimeout: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner

    var previewView: PreviewView? by remember { mutableStateOf(null) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }

    var pvSize by remember { mutableStateOf(IntSize.Zero) }

    // Подсказки пользователю (обновляются из анализатора через main executor)
    var hintLines by remember { mutableStateOf<List<String>>(listOf("Поместите лицо в кадр")) }

    // Пороговые условия: углы головы + стабильность (без центрирования)
    val thresholds = remember {
        DetectionThresholds(
            maxYaw = 10f,
            maxPitch = 20f,
            maxRoll = 10f,
            maxCenterOffset = 0.20f,  // оставлено для совместимости; центрирование не используем
            minFaceArea = 0.08f,      // сейчас не используется
            maxFaceArea = 0.35f,
            stableMs = 500L,
            frameMarginPx = 20,
            minSharpness = 120.0      // дисперсия Лапласиана на ROI; откалибруйте при необходимости
        )
    }

    val bestShot = remember(thresholds) { BestShotGate(thresholds) }
    val motionGate = remember { HeadMotionGate(velThresholdDegPerSec = 50f, stableMs = 250L, alpha = 0.35f) }

    // Контролы CameraX
    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context)
    }
    // Отдельный executor для анализатора (п.7)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }
    // Флаг, чтобы не запускать новый инференс, пока предыдущий не завершился (п.8)
    val inFlight = remember { AtomicBoolean(false) }

    // Таймаут флоу: 15 секунд безуспешной фиксации — отменяем с причиной (п.16)
    LaunchedEffect(Unit) {
        val timeoutMs = 15_000L
        delay(timeoutMs)
        onTimeout()
    }

    // Храним use cases, чтобы отвязать при переворотах и т.д.
    var analysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    var faceDetector by remember { mutableStateOf<FaceDetector?>(null) }

    LaunchedEffect(cameraProviderFuture, lensFacing, pvSize) {
        val provider = cameraProviderFuture.get()
        // Cleanup previous detector before unbinding
        faceDetector?.close()
        faceDetector = null
        provider.unbindAll()
        val pv = previewView ?: return@LaunchedEffect
        if (pvSize.width == 0 || pvSize.height == 0) return@LaunchedEffect
        val rotation = pv.display?.rotation ?: Surface.ROTATION_0

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }

        analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build().apply {
                setAnalyzer(
                    analysisExecutor
                ) { imageProxy ->
                    // п.8: защита от одновременных инференсов
                    if (!inFlight.compareAndSet(false, true)) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        inFlight.set(false)
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    // Lazy initialization of face detector with minFaceSize
                    val rotationDeg = imageProxy.imageInfo.rotationDegrees
                    val effW = if (rotationDeg % 180 == 0) imageProxy.width else imageProxy.height
                    val minFaceRatio = (150f / effW.toFloat()).coerceIn(0.0f, 1.0f)

                    val detector = faceDetector ?: run {
                        val d = FaceDetection.getClient(
                            FaceDetectorOptions.Builder()
                                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                                .enableTracking() // стабильность ID между кадрами
                                .setMinFaceSize(minFaceRatio) // минимум ~150 px по эффективной ширине кадра
                                .build()
                        )
                        faceDetector = d
                        d
                    }

                    detector.process(input)
                        .addOnSuccessListener(analysisExecutor) { faces ->
                            // Считаем подсказки (на analysisExecutor), публикуем в UI (Main)
                            val now = System.currentTimeMillis()
                            val face = faces.firstOrNull()
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            val stillOk = if (face != null) {
                                val trackId = face.trackingId ?: -1
                                val yaw = face.headEulerAngleY
                                val pitch = face.headEulerAngleX
                                val roll = face.headEulerAngleZ
                                motionGate.update(trackId, yaw, pitch, roll, now)
                            } else {
                                false
                            }
                            val newHints = computeHints(
                                faces = faces,
                                imageWidth = imageProxy.width,
                                imageHeight = imageProxy.height,
                                rotationDegrees = rotationDegrees,
                                thresholds = thresholds,
                                stillOk = stillOk
                            )
                            ContextCompat.getMainExecutor(context).execute {
                                hintLines = newHints
                            }

                            val okStatic = evaluateFacesOnImage(
                                faces = faces,
                                imageWidth = imageProxy.width,
                                imageHeight = imageProxy.height,
                                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                                thresholds = thresholds
                            )

                            val okAll = okStatic && stillOk
                            if (bestShot.update(okAll, now) && face != null) {
                                val uri = saveFaceCropFromYuvIfSharp(
                                    imageProxy = imageProxy,
                                    faceRectUpright = face.boundingBox,
                                    paddingFraction = 0.20f, // +20% к ширине/высоте бокса
                                    minSharpness = thresholds.minSharpness,
                                    mirrorHorizontally = (lensFacing == CameraSelector.LENS_FACING_FRONT),
                                    context = context,
                                    contextAuthority = "${context.packageName}.fileprovider"
                                )
                                if (uri != null) {
                                    // Вернёмся на главный поток только для UI-действий
                                    ContextCompat.getMainExecutor(context).execute {
                                        hintLines = emptyList()
                                        onResult(uri)
                                        this.clearAnalyzer()
                                    }
                                } else {
                                    bestShot.reset()
                                }
                            }
                        }
                        .addOnFailureListener(analysisExecutor) {
                            // ignore; just continue
                        }
                        .addOnCompleteListener(analysisExecutor) {
                            inFlight.set(false)
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

        // Панель подсказок
        if (hintLines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color(0x66000000), shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                hintLines.take(3).forEach { line ->
                    Text(text = line, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

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
    val frameMarginPx: Int,
    val minSharpness: Double
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

    // Минимальный размер 150 px
    val bboxWpx = f.boundingBox.width()
    val bboxHpx = f.boundingBox.height()
    if (bboxWpx < 150 || bboxHpx < 150) return false

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
    fun reset() { startOk = null }
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
    yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
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
        cropped.compress(Bitmap.CompressFormat.JPEG, 100, fos)
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
        cropped.compress(Bitmap.CompressFormat.JPEG, 100, fos)
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
/**
 * Оценивает резкость на ROI через дисперсию Лапласиана.
 * Bitmap должен быть "upright". Для ускорения ROI даунскейлится до maxSide=256.
 * Возвращает значение дисперсии; больше — резче.
 */
private fun laplacianVarianceY(roi: Bitmap, maxSide: Int = 256): Double {
    // Опциональный даунскейл
    val w0 = roi.width
    val h0 = roi.height
    val scale = kotlin.math.min(1.0, maxSide.toDouble() / kotlin.math.max(w0, h0).toDouble())
    val bmp = if (scale < 1.0) {
        Bitmap.createScaledBitmap(roi, (w0 * scale).toInt().coerceAtLeast(1), (h0 * scale).toInt().coerceAtLeast(1), true)
    } else {
        roi
    }

    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)

    // Преобразуем в "Y" (яркость) по формуле Rec.601
    val y = FloatArray(w * h)
    var i = 0
    while (i < pixels.size) {
        val p = pixels[i]
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        // 0.299R + 0.587G + 0.114B
        y[i] = (0.299f * r + 0.587f * g + 0.114f * b)
        i++
    }

    // Лапласиан 4-связный: [0,1,0; 1,-4,1; 0,1,0]
    var sum = 0.0
    var sumSq = 0.0
    var cnt = 0
    var yy = 1
    while (yy < h - 1) {
        var xx = 1
        val row = yy * w
        while (xx < w - 1) {
            val c = y[row + xx]
            val lap = (-4f) * c +
                    y[row + xx - 1] + y[row + xx + 1] +
                    y[row - w + xx] + y[row + w + xx]
            val v = lap.toDouble()
            sum += v
            sumSq += v * v
            cnt++
            xx++
        }
        yy++
    }

    if (bmp !== roi) bmp.recycle()

    if (cnt == 0) return 0.0
    val mean = sum / cnt
    val variance = (sumSq / cnt) - mean * mean
    return if (variance.isNaN() || variance < 0.0) 0.0 else variance
}

/**
 * Сохраняет ROI по bbox только если резкость (дисперсия Лапласиана) ≥ minSharpness.
 * Возвращает Uri или null, если кадр посчитан размытым.
 */
private fun saveFaceCropIfSharp(
    imageProxy: ImageProxy,
    faceRect: Rect,
    paddingFraction: Float,
    minSharpness: Double,
    activity: ComponentActivity,
    contextAuthority: String
): Uri? {
    val imagesDir = File(activity.cacheDir, "images").apply { mkdirs() }

    // 1) "upright" Bitmap
    val bmp = imageProxyToUprightBitmap(imageProxy)
    // 2) ROI по bbox (+padding)
    val roi = cropByRectWithPadding(bmp, faceRect, paddingFraction)

    // 3) Оценка резкости на ROI
    val sharp = laplacianVarianceY(roi, maxSide = 256)
    if (BuildConfig.DEBUG) {
        Log.d("Sharpness", "lapVar=${sharp} (threshold=${minSharpness}), roi=${roi.width}x${roi.height}")
    }

    if (sharp < minSharpness) {
        // размыто — ничего не сохраняем
        bmp.recycle()
        roi.recycle()
        return null
    }

    // 4) Пишем результат в файл
    val file = File(
        imagesDir,
        "face_bbox_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg"
    )
    file.outputStream().use { fos ->
        roi.compress(Bitmap.CompressFormat.JPEG, 100, fos)
        fos.flush()
    }

    // 5) Освобождаем память
    bmp.recycle()
    roi.recycle()

    // 6) Возвращаем Uri
    return FileProvider.getUriForFile(activity, contextAuthority, file)
}
/**
 * Расширяет прямоугольник в координатах "upright" кадра на paddingFraction и клампит в пределах effW x effH.
 */
private fun expandAndClampUprightRect(rect: Rect, paddingFraction: Float, effW: Int, effH: Int): Rect {
    val padFrac = paddingFraction.coerceAtLeast(0f)
    val cx = rect.centerX().toFloat()
    val cy = rect.centerY().toFloat()
    val bw = rect.width().toFloat().coerceAtLeast(1f)
    val bh = rect.height().toFloat().coerceAtLeast(1f)

    val newW = (bw * (1f + padFrac)).coerceAtMost(effW.toFloat())
    val newH = (bh * (1f + padFrac)).coerceAtMost(effH.toFloat())

    var left = (cx - newW / 2f)
    var top  = (cy - newH / 2f)
    var right = (cx + newW / 2f)
    var bottom = (cy + newH / 2f)

    if (left < 0f) { right -= left; left = 0f }
    if (top  < 0f) { bottom -= top; top = 0f }
    if (right > effW) { left -= (right - effW); right = effW.toFloat() }
    if (bottom > effH) { top -= (bottom - effH); bottom = effH.toFloat() }

    val il = left.roundToInt().coerceIn(0, effW - 1)
    val it = top.roundToInt().coerceIn(0, effH - 1)
    val ir = right.roundToInt().coerceIn(il + 1, effW)
    val ib = bottom.roundToInt().coerceIn(it + 1, effH)
    return Rect(il, it, ir, ib)
}

/**
 * Преобразует прямоугольник из координат "upright"(effW x effH) в координаты сырого кадра (imageWidth x imageHeight)
 * согласно rotationDegrees (0/90/180/270).
 */
private fun mapUprightRectToRaw(rectU: Rect, imageWidth: Int, imageHeight: Int, rotationDegrees: Int): Rect {
    val effW = if (rotationDegrees % 180 == 0) imageWidth else imageHeight
    val effH = if (rotationDegrees % 180 == 0) imageHeight else imageWidth

    fun mapPoint(xu: Int, yu: Int): Pair<Int, Int> {
        return when (rotationDegrees % 360) {
            0 -> Pair(xu, yu)
            90 -> {
                // upright = raw rotated +90 (CW)
                // raw x = yu, raw y = (imageHeight - 1) - xu
                Pair(yu, (imageHeight - 1) - xu)
            }
            180 -> {
                Pair((imageWidth - 1) - xu, (imageHeight - 1) - yu)
            }
            270 -> {
                // upright = raw rotated +270 (i.e., -90)
                // raw x = (imageWidth - 1) - yu, raw y = xu
                Pair((imageWidth - 1) - yu, xu)
            }
            else -> Pair(xu, yu)
        }
    }

    val (x0, y0) = mapPoint(rectU.left, rectU.top)
    val (x1, y1) = mapPoint(rectU.right, rectU.bottom)
    val left = min(x0, x1).coerceIn(0, imageWidth - 1)
    val top = min(y0, y1).coerceIn(0, imageHeight - 1)
    val right = max(x0, x1).coerceIn(left + 1, imageWidth)
    val bottom = max(y0, y1).coerceIn(top + 1, imageHeight)
    return Rect(left, top, right, bottom)
}

/**
 * Быстрое сохранение ROI: берём NV21 из ImageProxy, жмём сразу ROI в JPEG, проверяем резкость,
 * при необходимости поворачиваем маленький ROI в upright и сохраняем. Возвращает Uri или null, если размыт.
 */
private fun saveFaceCropFromYuvIfSharp(
    imageProxy: ImageProxy,
    faceRectUpright: Rect,
    paddingFraction: Float,
    minSharpness: Double,
    mirrorHorizontally: Boolean,
    context: android.content.Context,
    contextAuthority: String
): Uri? {
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
    val imageWidth = imageProxy.width
    val imageHeight = imageProxy.height

    // 1) Рассчитываем upright ROI с паддингом и клампим по eff-габаритам
    val effW = if (rotationDegrees % 180 == 0) imageWidth else imageHeight
    val effH = if (rotationDegrees % 180 == 0) imageHeight else imageWidth
    val roiU = expandAndClampUprightRect(faceRectUpright, paddingFraction, effW, effH)

    // 2) Переводим в координаты сырого кадра
    val roiRaw = mapUprightRectToRaw(roiU, imageWidth, imageHeight, rotationDegrees)

    // 3) Формируем NV21 и жмём только ROI в JPEG
    val nv21 = yuv420888ToNv21(imageProxy)
    val yuv = YuvImage(nv21, ImageFormat.NV21, imageWidth, imageHeight, null)
    val jpegRoiStream = ByteArrayOutputStream()
    yuv.compressToJpeg(roiRaw, 100, jpegRoiStream)
    val jpegBytes = jpegRoiStream.toByteArray()

    // 4) Оценка резкости на ROI (по JPEG ROI, это небольшой Bitmap)
    val roiBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
    val sharp = laplacianVarianceY(roiBitmap, maxSide = 256)
    if (BuildConfig.DEBUG) {
        Log.d("Sharpness", "lapVar=$sharp (threshold=$minSharpness), roi=${roiBitmap.width}x${roiBitmap.height}, rot=$rotationDegrees")
    }
    if (sharp < minSharpness) {
        roiBitmap.recycle()
        return null
    }

    // 5) Поворот ROI в upright и опциональное зеркалирование
    val uprightBitmap = if (rotationDegrees != 0) {
        val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(roiBitmap, 0, 0, roiBitmap.width, roiBitmap.height, m, true)
        if (rotated != roiBitmap) roiBitmap.recycle()
        rotated
    } else {
        roiBitmap
    }

    val finalBitmap = if (mirrorHorizontally) {
        val mx = Matrix().apply { postScale(-1f, 1f, uprightBitmap.width / 2f, uprightBitmap.height / 2f) }
        val mirrored = Bitmap.createBitmap(uprightBitmap, 0, 0, uprightBitmap.width, uprightBitmap.height, mx, true)
        if (mirrored != uprightBitmap) uprightBitmap.recycle()
        mirrored
    } else {
        uprightBitmap
    }

    // 6) Запись в файл
    val imagesDir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File(
        imagesDir,
        "face_bbox_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg"
    )
    file.outputStream().use { fos ->
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
        fos.flush()
    }
    finalBitmap.recycle()

    // 7) Возвращаем Uri
    return FileProvider.getUriForFile(context, contextAuthority, file)
}
/**
 * Формирует список подсказок пользователю по текущему состоянию кадра.
 * Никакой центрировки не требуем; проверяем: одно лицо, отступы, размер, углы, неподвижность.
 */
private fun computeHints(
    faces: List<Face>,
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
    thresholds: DetectionThresholds,
    stillOk: Boolean
): List<String> {
    val hints = mutableListOf<String>()

    if (faces.isEmpty()) return listOf("Покажите лицо в камеру")
    if (faces.size > 1) return listOf("В кадре должно быть одно лицо")

    val f = faces[0]

    // Эффективные размеры после поворота (совпадают с системой координат bbox)
    val effW = if (rotationDegrees % 180 == 0) imageWidth else imageHeight
    val effH = if (rotationDegrees % 180 == 0) imageHeight else imageWidth
    val m = thresholds.frameMarginPx

    // Отступы от краёв (20 px)
    val bx0 = f.boundingBox.left
    val by0 = f.boundingBox.top
    val bx1 = f.boundingBox.right
    val by1 = f.boundingBox.bottom
    if (bx0 < m) hints += "Сместитесь правее"
    if (bx1 > effW - m) hints += "Сместитесь левее"
    if (by0 < m) hints += "Опустите камеру или сместитесь ниже"
    if (by1 > effH - m) hints += "Поднимите камеру или сместитесь выше"

    // Размер: минимум 150 px по обеим осям
    val w = f.boundingBox.width()
    val h = f.boundingBox.height()
    if (w < 150 || h < 150) hints += "Подойдите ближе"

    // Слишком крупно: используем maxFaceArea (доля площади кадра)
    val areaFrac = (w.toFloat() * h.toFloat()) / (effW.toFloat() * effH.toFloat())
    if (areaFrac > thresholds.maxFaceArea) hints += "Отойдите чуть дальше"

    // Углы
    val yaw = kotlin.math.abs(f.headEulerAngleY)
    val pitch = kotlin.math.abs(f.headEulerAngleX)
    val roll = kotlin.math.abs(f.headEulerAngleZ)
    if (yaw > thresholds.maxYaw) hints += "Поверните голову к камере"
    if (pitch > thresholds.maxPitch) hints += "Выравните наклон по вертикали"
    if (roll > thresholds.maxRoll) hints += "Выравните наклон по горизонтали"

    // Неподвижность
    if (!stillOk) hints += "Задержитесь на месте"

    // Если всё хорошо — пустые подсказки не возвращаем, но можем показать подтверждение
    if (hints.isEmpty()) return listOf("Отлично, удерживайте положение")
    return hints
}