package com.ooto.faceapidemo.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.FileOutputStream

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import android.util.Rational
import android.view.Surface
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.compose.foundation.Canvas
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
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

    // Пороговые условия "голова прямо / по центру"
    val thresholds = remember {
        DetectionThresholds(
            maxYaw = 20f,     // поворот влево/вправо
            maxPitch = 20f,   // кивок вверх/вниз
            maxRoll = 20f,    // наклон
            maxCenterOffset = 0.20f,  // расстояние от центра (нормированное, было 0.12f)
            minFaceArea = 0.08f,      // доля площади кадра
            maxFaceArea = 0.35f,
            stableMs = 500L
        )
    }

    // Контролы CameraX
    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context)
    }

    // Храним use cases, чтобы отвязать при переворотах и т.д.
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
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

        imageCapture = ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .enableTracking() // стабильность ID между кадрами
                .build()
        )

        val bestShot = BestShotGate(thresholds)

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
                            val ok = evaluateFacesOnImage(
                                faces = faces,
                                imageWidth = imageProxy.width,
                                imageHeight = imageProxy.height,
                                lensFacing = lensFacing,
                                thresholds = thresholds
                            )

                            val now = System.currentTimeMillis()
                            if (bestShot.update(ok, now)) {
                                takeAndReturnPhoto(
                                    imageCapture = imageCapture ?: return@addOnSuccessListener,
                                    onResult = onResult,
                                    contextAuthority = "${context.packageName}.fileprovider",
                                    activity = context as ComponentActivity,
                                    thresholds = thresholds
                                )
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
            .addUseCase(imageCapture!!)
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

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Рамка зоны, где должно быть лицо (по допуску maxCenterOffset от центра)
            val insetX = size.width * thresholds.maxCenterOffset
            val insetY = size.height * thresholds.maxCenterOffset
            val topLeft = Offset(insetX, insetY)
            val rectSize = Size(size.width - 2f * insetX, size.height - 2f * insetY)

            drawRoundRect(
                color = Color.White,
                topLeft = topLeft,
                size = rectSize,
                cornerRadius = CornerRadius(20f, 20f),
                style = Stroke(width = 4f)
            )
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
    val stableMs: Long
)

/**
 * Проверка: одно лицо, по центру, голова прямо, разумный размер.
 * Используются размеры кадра из ImageProxy (оригинальная система координат изображения).
 */
private fun evaluateFacesOnImage(
    faces: List<Face>,
    imageWidth: Int,
    imageHeight: Int,
    lensFacing: Int,
    thresholds: DetectionThresholds
): Boolean {
    if (faces.size != 1) return false
    val f = faces[0]

    Log.w("TEST!", faces.toString())

    val yaw = kotlin.math.abs(f.headEulerAngleY)
    val roll = kotlin.math.abs(f.headEulerAngleZ)
    val pitch = kotlin.math.abs(f.headEulerAngleX)
    if (yaw > thresholds.maxYaw || roll > thresholds.maxRoll || pitch > thresholds.maxPitch) return false
    Log.w("TEST2", faces.toString())
    val w = imageWidth.toFloat().coerceAtLeast(1f)
    val h = imageHeight.toFloat().coerceAtLeast(1f)

    val cx = f.boundingBox.centerX().toFloat() / w
    val cy = f.boundingBox.centerY().toFloat() / h

    // Для фронтальной камеры зеркалим X, чтобы "центр" соответствовал визуальному ощущению пользователя
    val normCx = if (lensFacing == CameraSelector.LENS_FACING_FRONT) 1f - cx else cx

    val dx = kotlin.math.abs(normCx - 0.5f)
    val dy = kotlin.math.abs(cy - 0.5f)
    val centerOk = kotlin.math.max(dx, dy) <= thresholds.maxCenterOffset
    if (!centerOk) return false
    Log.w("TEST3", faces.toString())
    val area = (f.boundingBox.width().toFloat() / w) * (f.boundingBox.height().toFloat() / h)
    if (area < thresholds.minFaceArea || area > thresholds.maxFaceArea) return false
    Log.w("TEST4", faces.toString())
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

private fun takeAndReturnPhoto(
    imageCapture: ImageCapture,
    onResult: (Uri) -> Unit,
    contextAuthority: String,
    activity: ComponentActivity,
    thresholds: DetectionThresholds
) {
    val imagesDir = File(activity.cacheDir, "images").apply { mkdirs() }
    val file = File(imagesDir, "face_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg")

    val output = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        output,
        ContextCompat.getMainExecutor(activity),
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                activity.setResult(Activity.RESULT_CANCELED)
                activity.finish()
            }
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // 1) Декодируем фото с ограничением по размеру и выравниваем по EXIF
                val upright = decodeUprightLimited(file, maxSide = 3000)

                // 2) Кадрируем центральную область по рамке maxCenterOffset (та же логика, что в overlay)
                val cropped = cropCenterByOffset(upright, thresholds.maxCenterOffset)

                // 3) Сохраняем в новый файл и возвращаем Uri
                val croppedFile = File(imagesDir, "face_crop_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg")
                FileOutputStream(croppedFile).use { fos ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    fos.flush()
                }

                // По желанию можно удалить исходный полный кадр
                try { file.delete() } catch (_: Exception) {}

                val uri = FileProvider.getUriForFile(activity, contextAuthority, croppedFile)

                // Освобождаем память
                upright.recycle()
                cropped.recycle()

                onResult(uri)
            }
        }
    )
}

/**
 * Декодирует JPEG из файла с ограничением на максимальную сторону и поворачивает по EXIF.
 */
private fun decodeUprightLimited(file: File, maxSide: Int = 3000): Bitmap {
    // Подготовка масштаба
    val opts1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts1)
    val largest = max(opts1.outWidth, opts1.outHeight).coerceAtLeast(1)
    val sample = max(1, largest / maxSide)

    val opts2 = BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        inSampleSize = sample
    }
    var bmp = BitmapFactory.decodeFile(file.absolutePath, opts2)
        ?: throw IllegalArgumentException("Failed to decode image: ${file.absolutePath}")

    // Поворот по EXIF (если есть)
    val exif = ExifInterface(file.absolutePath)
    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
    if (degrees != 0) {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        bmp = rotated
    }
    return bmp
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