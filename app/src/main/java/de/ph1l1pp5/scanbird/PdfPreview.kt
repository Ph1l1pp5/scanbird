package de.ph1l1pp5.scanbird

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.ph1l1pp5.scanbird.data.ScanStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Zeigt ein gespeichertes PDF — gerendert mit dem eingebauten PdfRenderer, ganz
 * ohne externe PDF-Bibliothek. VOLLBILD: Systemleisten werden weggeblendet und
 * per Wisch-Geste an den Rand wieder hervorgeholt; zurueck geht es mit der
 * Android-Zurueck-Geste. Mehrseitige Scans lassen sich per Wischen blättern
 * (unten Mitte zählt der Seiten-Chip mit), Pinch-Zoom bis 6x (der Fokus bleibt
 * unter den Fingern), freies Schieben im Zoom mit Klemmen am Rand, Doppeltipp
 * schaltet zwischen 1x und 2,5x um. Gerendert wird mit 3facher Auflösung, damit
 * die Vergrößerung scharf bleibt.
 */
@Composable
fun PdfPreviewScreen(
    title: String,
    fileName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var openError by remember { mutableStateOf(false) }
    var pageCount by remember { mutableStateOf(1) }
    var currentPage by remember { mutableStateOf(0) }

    LaunchedEffect(fileName, currentPage) {
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
                    pageCount = renderer.pageCount
                    if (currentPage >= renderer.pageCount) {
                        // PDF kuerzer als gedacht (z. B. alt gekappt): zurueck auf
                        // die letzte Seite — der LaunchedEffect rendert dann neu.
                        currentPage = renderer.pageCount - 1
                    } else {
                        page = renderer.openPage(currentPage)
                        val scale = 3f
                        val width = (page.width * scale).toInt().coerceAtMost(2400)
                        val height = (page.height * scale).toInt().coerceAtMost(3400)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pageBitmap = bitmap
                    }
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

    // Vollbild: Systemleisten verschwinden, kommen per Wisch-Geste an den
    // Bildschirmrand zurueck — und die Android-Zurueck-Geste fuehrt zur Liste.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        }
    }
    BackHandler(onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0F))
    ) {
        val bitmap = pageBitmap
        if (bitmap != null) {
            var zoom by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var viewSize by remember { mutableStateOf(IntSize.Zero) }

            // Seitenwechsel: Zoom und Position kehren auf Eins zurueck.
            LaunchedEffect(currentPage) {
                zoom = 1f
                offset = Offset.Zero
            }

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
                    }
                    .pointerInput(Unit) {
                        // Wischen blaettert — aber nur im ungezoomten Zustand, sonst
                        // schiebt die Geste ja das versegroeserte Blatt.
                        var dragAccum = 0f
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                dragAccum += amount
                            },
                            onDragEnd = {
                                val threshold = 60.dp.toPx()
                                if (zoom <= 1.01f && pageCount > 1) {
                                    if (dragAccum <= -threshold && currentPage < pageCount - 1) {
                                        currentPage += 1
                                    } else if (dragAccum >= threshold && currentPage > 0) {
                                        currentPage -= 1
                                    }
                                }
                                dragAccum = 0f
                            }
                        )
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

                // Seiten-Chip unten Mitte: zaehlt mit und zeigt, wo man steht.
                if (pageCount > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .background(Color(0x99000000), RoundedCornerShape(999.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            stringResource(R.string.page_indicator, currentPage + 1, pageCount),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        } else {
            Text(
                stringResource(R.string.pdf_loading),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
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
