package de.ph1l1pp5.scanbird

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vollbild-Scanner: CameraX-Vorschau, Blitz (Taschenlampe) standardmäßig an,
 * automatische Auslösung, wenn das Bild kurz ruhig bleibt, plus Knopf zum manuellen Scannen.
 */
@Composable
fun ScanScreen(
    onClose: () -> Unit,
    onCapture: (File) -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasPermission) {
            CameraContent(onCapture = onCapture)
        } else {
            Text(
                stringResource(R.string.scan_no_permission),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.close),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun CameraContent(onCapture: (File) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    var torchOn by remember { mutableStateOf(true) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraError by remember { mutableStateOf(false) }
    val capturing = remember { AtomicBoolean(false) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    fun takeShot() {
        if (!capturing.compareAndSet(false, true)) return
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "scan_" + System.currentTimeMillis() + ".jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    capturing.set(false)
                    onCapture(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing.set(false)
                    cameraError = true
                }
            }
        )
    }

    LaunchedEffect(torchOn) {
        val cam = camera
        if (cam != null && cam.cameraInfo.hasFlashUnit()) {
            cam.cameraControl.enableTorch(torchOn)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(
                            analysisExecutor,
                            SteadyAnalyzer { mainExecutor.execute { takeShot() } }
                        )
                        provider.unbindAll()
                        val boundCamera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                            imageCapture
                        )
                        imageCapture.targetRotation =
                            previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                        camera = boundCamera
                        if (boundCamera.cameraInfo.hasFlashUnit()) {
                            boundCamera.cameraControl.enableTorch(true)
                        }
                    } catch (e: Exception) {
                        cameraError = true
                    }
                }, mainExecutor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val frameWidth = size.width * 0.82f
            var frameHeight = frameWidth * (1754f / 1240f)
            if (frameHeight > size.height * 0.84f) {
                frameHeight = size.height * 0.84f
            }
            val left = (size.width - frameWidth) / 2f
            val top = (size.height - frameHeight) / 2f
            drawRoundRect(
                color = Color(0xB3FFFFFF),
                topLeft = Offset(left, top),
                size = Size(frameWidth, frameHeight),
                cornerRadius = CornerRadius(24f, 24f),
                style = Stroke(width = 4f)
            )
        }

        Text(
            stringResource(R.string.scan_hint),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
        )

        TextButton(
            onClick = { torchOn = !torchOn },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            Text(
                if (torchOn) stringResource(R.string.scan_torch_on) else stringResource(R.string.scan_torch_off),
                color = Color.White
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .size(72.dp)
                .border(3.dp, Color.White, CircleShape)
                .background(Color(0x33FFFFFF), CircleShape)
                .clickable { takeShot() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }

    if (cameraError) {
        AlertDialog(
            onDismissRequest = { cameraError = false },
            title = { Text(stringResource(R.string.scan_camera_error)) },
            confirmButton = {
                TextButton(onClick = { cameraError = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

/**
 * Bildruhe-Erkennung: ein grobes Helligkeitsraster je Frame; bleibt der Unterschied
 * zum Vorbild über genug Frames klein, gilt das Bild als ruhig und der Auslöser feuert.
 */
private class SteadyAnalyzer(
    private val onSteady: () -> Unit
) : ImageAnalysis.Analyzer {

    private var previous: FloatArray? = null
    private var steadyFrames = 0

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val imageWidth = image.width
            val imageHeight = image.height
            val gridW = 24
            val gridH = 18
            val grid = FloatArray(gridW * gridH)
            for (gy in 0 until gridH) {
                val baseY = gy * imageHeight / gridH
                for (gx in 0 until gridW) {
                    val baseX = gx * imageWidth / gridW
                    val index = baseY * rowStride + baseX * pixelStride
                    if (index < buffer.limit()) {
                        grid[gy * gridW + gx] = (buffer.get(index).toInt() and 0xFF).toFloat()
                    }
                }
            }
            val prev = previous
            if (prev != null && prev.size == grid.size) {
                var diff = 0f
                for (i in grid.indices) {
                    val d = grid[i] - prev[i]
                    diff += if (d >= 0f) d else -d
                }
                diff /= grid.size
                if (diff < 6f) {
                    steadyFrames++
                } else {
                    steadyFrames = 0
                }
                if (steadyFrames == STEADY_TRIGGER) {
                    steadyFrames = 0
                    onSteady()
                }
            }
            previous = grid
        } finally {
            image.close()
        }
    }

    companion object {
        private const val STEADY_TRIGGER = 20
    }
}
