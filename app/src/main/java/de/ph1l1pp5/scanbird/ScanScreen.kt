package de.ph1l1pp5.scanbird

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
    paused: Boolean,
    onCapture: (List<File>) -> Unit
) {
    val context = LocalContext.current
    var pages by remember { mutableStateOf(listOf<File>()) }
    var discardConfirm by remember { mutableStateOf(false) }

    // Zurueck-Knopf mit gepufferten Seiten: erst nachfragen, sonst wandern vier
    // Seiten Arbeit mit einem Fehlipp ins Nirwana.
    BackHandler(enabled = pages.isNotEmpty() && !paused) { discardConfirm = true }

    fun requestClose() {
        if (pages.isEmpty()) onClose() else discardConfirm = true
    }
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
            CameraContent(
                paused = paused,
                pages = pages,
                onPageCaptured = { file ->
                    pages = pages + file
                    onCapture(pages)
                },
                onRemoveLast = {
                    pages.lastOrNull()?.delete()
                    pages = pages.dropLast(1)
                }
            )
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
            onClick = { requestClose() },
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

    // Verwerfen-Rueckfrage: X oder Zurueck mit gepufferten Seiten.
    if (discardConfirm) {
        AlertDialog(
            onDismissRequest = { discardConfirm = false },
            title = { Text(stringResource(R.string.discard_pages_title)) },
            text = { Text(stringResource(R.string.discard_pages_text)) },
            confirmButton = {
                TextButton(onClick = {
                    discardConfirm = false
                    for (page in pages) {
                        try {
                            page.delete()
                        } catch (e: Exception) {
                        }
                    }
                    pages = emptyList()
                    onClose()
                }) {
                    Text(stringResource(R.string.discard_scan))
                }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CameraContent(
    paused: Boolean,
    pages: List<File>,
    onPageCaptured: (File) -> Unit,
    onRemoveLast: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // Live-Wert des Pausen-Flags: der Analyser-Thread fragt ihn je Frame ab —
    // ein einmal eingefrorener Konstruktor-Boolean wuerde nie aktualisieren.
    val pausedNow = rememberUpdatedState(paused)

    var torchOn by remember { mutableStateOf(true) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraError by remember { mutableStateOf(false) }
    val capturing = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            // Kamera sauber lösen — vorher blieb sie an die Activity gebunden und die
            // Taschenlampe brannte weiter, während der Speichern-Dialog schon offen war.
            try {
                camera?.cameraControl?.enableTorch(false)
            } catch (e: Exception) {
            }
            try {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    try {
                        providerFuture.get().unbindAll()
                    } catch (e: Exception) {
                    }
                }, mainExecutor)
            } catch (e: Exception) {
            }
        }
    }

    // Fortschritt der Ruhe-Erkennung (0..1) — treibt den AutoSnap-Puls.
    var snapProgress by remember { mutableStateOf(0f) }
    var snapGlow by remember { mutableStateOf(1f) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    fun takeShot() {
        // Waehrend der Speichern-Dialog offen ist, knipst nichts — auch der AutoSnap nicht.
        if (paused) return
        if (!capturing.compareAndSet(false, true)) return
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "scan_" + System.currentTimeMillis() + ".jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    capturing.set(false)
                    // Batch-Modus: Blitz bleibt an und die Kamera offen — die naechste
                    // Seite wird gelegt, ohne dass das Licht ausgeht.
                    onPageCaptured(file)
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
                                onProgress = { progress -> mainExecutor.execute { snapProgress = progress } },
                                isPaused = { pausedNow.value }
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

        // AutoSnap-Zielsucher (Philipps 90er-Spielfilm-Wunsch) als mittiger HUD-Chip:
        // Die dunkle Pille hält das Label lesbar — auf weissem Papier wie auf dunklem
        // Tisch — und der Text bleibt immer voll deckend; der Chip atmet: im Leerlauf
        // langsam und rund, sobald das Handy ruhig ueber dem Blatt gehalten wird,
        // steil hektischer werdend bis zum Einloggen und Knipsen.
        LaunchedEffect(Unit) {
            while (true) {
                val progress = snapProgress
                if (progress <= 0.02f) {
                    // Ruhiges Atmen im Leerlauf: eine runde Welle, ca. 1,8 Sekunden.
                    animate(1f, 0.25f, animationSpec = tween(900)) { v, _ -> snapGlow = v }
                    animate(0.25f, 1f, animationSpec = tween(900)) { v, _ -> snapGlow = v }
                } else {
                    // Scharf: steile Rampe von 550 ms auf 120 ms — der Puls sprint
                    // schneller schneller, je ruhiger das Bild, bis er einloggt.
                    val period = (550 - 430 * progress).toInt().coerceIn(120, 550)
                    animate(1f, 0f, animationSpec = tween(period / 2)) { v, _ -> snapGlow = v }
                    animate(0f, 1f, animationSpec = tween(period / 2)) { v, _ -> snapGlow = v }
                }
            }
        }
        val armed = snapProgress > 0.02f
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .scale(1f + 0.04f * snapGlow)
                .background(Color(0x99000000), RoundedCornerShape(999.dp))
                .border(
                    width = if (armed) 2.dp else 1.dp,
                    color = if (armed) {
                        Color(0xFF00FFFF).copy(alpha = 0.3f + 0.7f * snapGlow)
                    } else {
                        Color(0xFFFFFFFF).copy(alpha = 0.35f + 0.3f * snapGlow)
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
        // zeigt welcher Bau tatsaechlich laeuft, damit wir nie wieder raten muessen.
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

        // Seiten-Zaehler im AutoSnap-Stil: die leuchtende Zahl oben mittig zaehlt
        // mit und zeigt, welche Seite gerade im Sucher liegt — daneben Undo.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .scale(1f + 0.04f * snapGlow)
                    .background(Color(0x99000000), RoundedCornerShape(999.dp))
                    .border(
                        width = 2.dp,
                        color = Color(0xFF00FFFF).copy(alpha = 0.35f + 0.65f * snapGlow),
                        shape = RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    // Zaehlt erst weiter, wenn die aufgenommene Seite mit
                    // „Weiter scannen“ bestaetigt wurde — waehrend der
                    // Rueckfrage hinter dem Speichern-Dialog bleibt die
                    // alte Zahl stehen (Philipps Wunsch, 0.5.3).
                    (if (paused && pages.isNotEmpty()) pages.size else pages.size + 1).toString(),
                    color = Color(0xFF00FFFF),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                )
            }
            if (pages.isNotEmpty()) {
                IconButton(onClick = onRemoveLast) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.undo_last_page),
                        tint = Color.White
                    )
                }
            }
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
    private val onProgress: (Float) -> Unit,
    private val isPaused: () -> Boolean
) : ImageAnalysis.Analyzer {

    private var previous: FloatArray? = null
    private var steadyFrames = 0
    // True, seit zuletzt Bewegung im Bild war (oder im frischen Start): nur dann
    // darf der AutoSnap feuern — nach einem Snap wartet er auf den Blattwechsel.
    private var sawMotion = true

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
            if (isPaused()) {
                // Speichern-Dialog offen: keine Ruhe zaehlt, kein Re-Armen —
                // nach dem Weiter-Knopf verlangt der AutoSnap erst wieder Bewegung.
                steadyFrames = 0
                sawMotion = false
                onProgress(0f)
            } else {
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
                        sawMotion = true
                    }
                    if (steadyFrames == STEADY_TRIGGER) {
                        steadyFrames = 0
                        // Nach jedem Snap muss erst wieder Bewegung rein — das Blatt
                        // drehen zaehlt. Sonst feuert der AutoSnap im Sekundentakt
                        // dasselbe ruhige Blatt ab.
                        if (sawMotion) {
                            sawMotion = false
                            onSteady()
                        }
                    }
                    onProgress(if (sawMotion) steadyFrames / STEADY_TRIGGER.toFloat() else 0f)
                }
            }
            previous = grid
        } finally {
            image.close()
        }
    }

    companion object {
        // 24 ruhige Frames (ca. 0,8 Sekunden): knackige Reaktion — der
        // AutoSnap-Zielsucher sprint in dieser Zeit auf Sparbrand-Takt.
        private const val STEADY_TRIGGER = 24
    }

}
