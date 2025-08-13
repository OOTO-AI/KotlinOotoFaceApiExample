package com.ooto.faceapidemo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import com.ooto.faceapidemo.ui.MainViewModel

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

    // Launchers
    val takePreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        viewModel.onPhotoCaptured(bmp)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) takePreviewLauncher.launch(null)
        else viewModel.setStatus("Camera permission denied")
    }

    fun ensureCameraThenCapture() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) takePreviewLauncher.launch(null)
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
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
            Button(
                onClick = { ensureCameraThenCapture() },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Take photo") }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = { viewModel.enroll() },
                enabled = !state.loading && state.bitmap != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enroll") }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = { viewModel.identify() },
                enabled = !state.loading && state.bitmap != null,
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