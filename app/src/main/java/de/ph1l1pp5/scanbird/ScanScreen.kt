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
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

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
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
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

    // Fortschritt der Ruhe-Erkennung (0..1) — treibt den AutoSnap-Zielsucher-Puls.
    var snapProgress by remember { mutableStateOf(0f) }
    var snapGlow by remember { mutableStateOf(1f) }

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
                            SteadyAnalyzer(
                                onSteady = { mainExecutor.execute { takeShot() } },
                                onProgress = { progress -> mainExecutor.execute { snapProgress = progress } }
                            )
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

        // Sucher-Rahmen: das Scanbird-Symbol-Handy in Cyan-Neon.
        // Alle Maße stammen aus der Symbol-Vermessung (Rahmenbreite 219) und
        // werden mit k = frameWidth/219 mitgeführt — Glow, Kern, Notch, Lautsprecher,
        // Kamera, Bodenpille und Seitentaste sitzen dadurch exakt wie im Icon.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val frameWidth = size.width * 0.94f
            var frameHeight = frameWidth * (1754f / 1240f)
            if (frameHeight > size.height * 0.88f) {
                frameHeight = size.height * 0.88f
            }
            val left = (size.width - frameWidth) / 2f
            val top = (size.height - frameHeight) / 2f
            val k = frameWidth / 219f
            val cyan = Color(0xFF00FFFF)
            val topLeft = Offset(left, top)
            val frameSize = Size(frameWidth, frameHeight)
            val corner = CornerRadius(frameWidth * 0.066f, frameWidth * 0.066f)

            // Rahmen-Glow (weich, halbtransparent)
            drawRoundRect(
                color = cyan.copy(alpha = 0.4f),
                topLeft = topLeft,
                size = frameSize,
                cornerRadius = corner,
                style = Stroke(width = frameWidth * 0.05f)
            )
            // Rahmen-Kern
            drawRoundRect(
                color = cyan,
                topLeft = topLeft,
                size = frameSize,
                cornerRadius = corner,
                style = Stroke(width = frameWidth * 0.025f)
            )

            // Notch-Striche oben links
            for (i in 0..2) {
                drawRect(
                    color = cyan.copy(alpha = 0.9f),
                    topLeft = Offset(left + (21.1f + i * 12.5f) * k, top + 16.2f * k),
                    size = Size(10.5f * k, 9.4f * k)
                )
            }

            // Lautsprecher-Schlitz
            drawRoundRect(
                color = cyan,
                topLeft = Offset(left + 67.9f * k, top + 16f * k),
                size = Size(69.5f * k, 7f * k),
                cornerRadius = CornerRadius(3.5f * k, 3.5f * k),
                style = Stroke(width = 3f * k)
            )

            // Kamera-Auge
            drawCircle(
                color = cyan,
                radius = 8f * k,
                center = Offset(left + 149.7f * k, top + 22.1f * k),
                style = Stroke(width = 3f * k)
            )

            // Bodenpille
            drawRoundRect(
                color = cyan,
                topLeft = Offset(left + 77.3f * k, top + frameHeight - 33.9f * k),
                size = Size(57.1f * k, 19.3f * k),
                cornerRadius = CornerRadius(9.65f * k, 9.65f * k),
                style = Stroke(width = 3f * k)
            )

            // Seitentaste am rechten Rand (dunkler Patch + Neon-Strich)
            drawRect(
                color = Color(0xFF0C0C18),
                topLeft = Offset(left + frameWidth - 6.4f * k, top + 19.3f * k),
                size = Size(8f * k, 31.4f * k)
            )
            drawRoundRect(
                color = cyan.copy(alpha = 0.9f),
                topLeft = Offset(left + 214.4f * k, top + 22.9f * k),
                size = Size(6.4f * k, 26.4f * k),
                cornerRadius = CornerRadius(2f * k, 2f * k),
                style = Stroke(width = 1.5f * k)
            )
        }

        // AutoSnap-Zielsucher (Philipps 90er-Spielfilm-Wunsch) als HUD-Chip:
        // Die dunkle Pille hält das Label lesbar — auf weissem Papier wie auf
        // dunklem Tisch — und der Text bleibt immer voll deckend; pulsiert wird
        // nur der Cyan-Ring der Pille, dessen Takt mit dem Ruhe-Fortschritt
        // steigt: langsam am Anfang, schnell kurz vorm Einloggen des Suchers.
        LaunchedEffect(Unit) {
            while (true) {
                val progress = snapProgress
                if (progress <= 0.02f) {
                    snapGlow = 1f
                    delay(150)
                } else {
                    // Pulsperiode: 1150 ms bei kaum Ruhe, 200 ms kurz vorm Einloggen.
                    val period = (1150 - 950 * progress).toInt().coerceIn(200, 1150)
                    animate(1f, 0.35f, animationSpec = tween(period / 2)) { v, _ -> snapGlow = v }
                    animate(0.35f, 1f, animationSpec = tween(period / 2)) { v, _ -> snapGlow = v }
                }
            }
        }
        val armed = snapProgress > 0.02f
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 124.dp)
                .background(Color(0x99000000), RoundedCornerShape(999.dp))
                .border(
                    width = if (armed) 2.dp else 1.dp,
                    color = if (armed) {
                        Color(0xFF00FFFF).copy(alpha = snapGlow)
                    } else {
                        Color(0x66FFFFFF)
                    },
                    shape = RoundedCornerShape(999.dp)
                )
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                stringResource(R.string.autosnap),
                color = if (armed) Color(0xFF00FFFF) else Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp,
                    letterSpacing = 4.sp
                )
            )
        }

        // Versions-Bake: unsichtbar unauffaellig, aber auf jedem Screenshot lesbar —
        // zeigt welcher Bau tatsaechlich laeuft, damit wir Nie-wieder raten muessen.
        val versionName = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            } catch (e: Exception) {
                "?"
            }
        }
        Text(
            "v" + versionName,
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 10.dp)
        )

        TextButton(
            onClick = { torchOn = !torchOn },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
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
 * Der aktuelle Fortschritt (0..1) wird nebenbei gemeldet — er treibt den AutoSnap-Puls.
 */
private class SteadyAnalyzer(
    private val onSteady: () -> Unit,
    private val onProgress: (Float) -> Unit
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
                onProgress(steadyFrames / STEADY_TRIGGER.toFloat())
            }
            previous = grid
        } finally {
            image.close()
        }
    }

    companion object {
        // 45 ruhige Frames (ca. 1,5 Sekunden): gibt dem AutoSnap-Zielsucher Zeit,
        // von langsam nach schnell zu beschleunigen, bevor der Ausloeser einloggt.
        private const val STEADY_TRIGGER = 45
    }

}
