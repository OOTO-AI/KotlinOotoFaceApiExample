package com.ooto.faceapidemo

import android.graphics.BitmapFactory
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import com.ooto.faceapidemo.camera.FaceCaptureActivity
import com.ooto.faceapidemo.ui.MainViewModel
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val launcherWithIdentifier = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val photoUri = res.data?.data // это Uri файла в cache
            // Покажите превью (декодируйте с inSampleSize) или отправьте на сервер

            if (photoUri != null) {
                val rotatedBitmap = decodeScaledOriented(photoUri, context)
                viewModel.onPhotoCaptured(rotatedBitmap)
                viewModel.identify()
            } else {
                viewModel.setStatus("Failed to decode image")
            }
        }
    }

    val launcherWithEnroll = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val photoUri = res.data?.data // это Uri файла в cache
            // Покажите превью (декодируйте с inSampleSize) или отправьте на сервер

            if (photoUri != null) {
                val rotatedBitmap = decodeScaledOriented(photoUri, context)
                viewModel.onPhotoCaptured(rotatedBitmap)
                viewModel.enroll()
            } else {
                viewModel.setStatus("Failed to decode image")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val bmp = state.bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "preview",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("No preview")
            }
        }

        if (state.loading) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        }

        Text(
            text = state.status,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            style = MaterialTheme.typography.bodyMedium
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
//            Button(
//                onClick = {
//                    launcher.launch(Intent(context, FaceCaptureActivity::class.java))
//                },
//                enabled = !state.loading,
//                modifier = Modifier.fillMaxWidth()
//            ) { Text("Take photo") }
//
//            Spacer(Modifier.width(8.dp))

            Button(
                onClick = {
                    launcherWithEnroll.launch(Intent(context, FaceCaptureActivity::class.java))
                },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enroll") }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = {
                    launcherWithIdentifier.launch(Intent(context, FaceCaptureActivity::class.java))
                },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Search") }
        }

        if (state.currentTemplateId != null) {
            Button(
                onClick = { viewModel.deleteTemplate() },
                enabled = !state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) { Text("Delete") }
        }
    }
}

fun decodeScaledOriented(uri: Uri, ctx: Context, maxSide: Int = 1920): Bitmap {
    val fd = ctx.contentResolver.openFileDescriptor(uri, "r")!!.fileDescriptor

    // 1) узнать размеры
    val opts1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFileDescriptor(fd, null, opts1)
    val w = opts1.outWidth; val h = opts1.outHeight
    val scale = max(1, min(w, h) / maxSide)

    // 2) декодировать с sampleSize
    val opts2 = BitmapFactory.Options().apply { inSampleSize = scale }
    var bmp = BitmapFactory.decodeFileDescriptor(fd, null, opts2)!!

    // 3) повернуть по EXIF
    val input = ctx.contentResolver.openInputStream(uri)!!
    val exif = androidx.exifinterface.media.ExifInterface(input)
    val deg = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
    if (deg != 0) {
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
    return bmp
}