package de.ph1l1pp5.scanbird

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.ph1l1pp5.scanbird.data.ScanStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Zeigt die erste Seite eines gespeicherten Scans — gerendert mit dem
 * eingebauten PdfRenderer, ganz ohne externe PDF-Bibliothek.
 * Pinch-Zoom bis 6x (der Fokus bleibt unter den Fingern), freies Schieben
 * im Zoom mit Klemmen am Rand, Doppeltipp schaltet zwischen 1x und 2,5x um.
 * Gerendert wird mit 3facher Auflösung, damit die Vergrößerung scharf bleibt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreviewScreen(
    title: String,
    fileName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var openError by remember { mutableStateOf(false) }

    LaunchedEffect(fileName) {
        withContext(Dispatchers.IO) {
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            var page: PdfRenderer.Page? = null
            try {
                descriptor = ScanStore.openRead(
                    context, ScanStore.locate(context, fileName)
                )
                if (descriptor == null) {
                    openError = true
                } else {
                    renderer = PdfRenderer(descriptor)
                    page = renderer.openPage(0)
                    val scale = 3f
                    val width = (page.width * scale).toInt().coerceAtMost(2400)
                    val height = (page.height * scale).toInt().coerceAtMost(3400)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pageBitmap = bitmap
                }
            } catch (e: Exception) {
                openError = true
            } finally {
                try { page?.close() } catch (e: Exception) { }
                try { renderer?.close() } catch (e: Exception) { }
                try { descriptor?.close() } catch (e: Exception) { }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        val bitmap = pageBitmap
        if (bitmap != null) {
            var zoom by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var viewSize by remember { mutableStateOf(IntSize.Zero) }

            // Schiebegrenzen: das Blatt darf nie ganz aus dem Bild geschoben werden.
            fun clamp(z: Float, raw: Offset): Offset {
                if (viewSize == IntSize.Zero) return Offset.Zero
                val contentH = viewSize.width.toFloat() * bitmap.height / bitmap.width
                val maxX = viewSize.width * (z - 1f) / 2f
                val maxY = (contentH * z - viewSize.height).coerceAtLeast(0f) / 2f
                return Offset(
                    raw.x.coerceIn(-maxX, maxX),
                    raw.y.coerceIn(-maxY, maxY)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .onSizeChanged { viewSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (zoom > 1.01f) {
                                    zoom = 1f
                                    offset = Offset.Zero
                                } else {
                                    zoom = 2.5f
                                    offset = clamp(2.5f, offset)
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                            val ratio = newZoom / zoom
                            val cx = viewSize.width / 2f
                            val cy = viewSize.height / 2f
                            // Fokus unter den Fingern halten, dann Schieben zulassen.
                            val raw = Offset(
                                (centroid.x - cx) * (1f - ratio) + ratio * offset.x + pan.x,
                                (centroid.y - cy) * (1f - ratio) + ratio * offset.y + pan.y
                            )
                            zoom = newZoom
                            offset = clamp(newZoom, raw)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
            }
        } else {
            Text(
                stringResource(R.string.pdf_loading),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .padding(16.dp)
            )
        }
    }

    if (openError) {
        AlertDialog(
            onDismissRequest = { openError = false },
            title = { Text(stringResource(R.string.pdf_error_title)) },
            confirmButton = {
                TextButton(onClick = { openError = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}
