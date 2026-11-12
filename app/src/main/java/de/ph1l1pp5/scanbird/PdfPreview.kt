package de.ph1l1pp5.scanbird

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ph1l1pp5.scanbird.data.ScanStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Zeigt die erste Seite eines gespeicherten Scans — gerendert mit dem
 * eingebauten PdfRenderer, ganz ohne externe PDF-Bibliothek.
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
                    val scale = 2f
                    val width = (page.width * scale).toInt().coerceAtMost(2000)
                    val height = (page.height * scale).toInt().coerceAtMost(2800)
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
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
