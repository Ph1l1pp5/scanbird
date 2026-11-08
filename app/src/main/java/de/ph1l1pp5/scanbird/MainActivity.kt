package de.ph1l1pp5.scanbird

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.ph1l1pp5.scanbird.data.AppDatabase
import de.ph1l1pp5.scanbird.data.ScanDao
import de.ph1l1pp5.scanbird.data.ScanEntity
import de.ph1l1pp5.scanbird.data.SettingsStore
import de.ph1l1pp5.scanbird.mail.MailSender
import de.ph1l1pp5.scanbird.pdf.PdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Color(0xFF2E7D32))
            ) {
                ScanbirdApp()
            }
        }
    }
}

private enum class Screen { Verlauf, Einstellungen }

@Composable
fun ScanbirdApp() {
    var screen by remember { mutableStateOf(Screen.Verlauf) }

    when (screen) {
        Screen.Verlauf -> VerlaufScreen(onOpenSettings = { screen = Screen.Einstellungen })
        Screen.Einstellungen -> EinstellungenScreen(onBack = { screen = Screen.Verlauf })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerlaufScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).scanDao() }
    val settings = remember { SettingsStore(context) }
    val mailSender = remember { MailSender(context) }
    val scope = rememberCoroutineScope()

    val scans by dao.allNewestFirst().collectAsState(initial = emptyList())

    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var titleInput by remember { mutableStateOf("") }
    var saveErrorDetail by remember { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok && pendingCaptureUri != null) {
            titleInput = defaultTitle()
            showSaveDialog = true
        }
    }

    fun startScan() {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "foto_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context, "de.ph1l1pp5.scanbird.fileprovider", file
        )
        pendingCaptureFile = file
        pendingCaptureUri = uri
        camera.launch(uri)
    }

    fun saveNow() {
        val uri = pendingCaptureUri
        val file = pendingCaptureFile
        if (uri == null || file == null) {
            showSaveDialog = false
            return
        }
        val title = titleInput.ifBlank { defaultTitle() }
        scope.launch {
            val errorText = saveScan(context, file, uri, title, dao)
            showSaveDialog = false
            if (errorText == null) {
                pendingCaptureFile = null
                pendingCaptureUri = null
            } else {
                saveErrorDetail = errorText
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { startScan() }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.scan)
                )
            }
        }
    ) { padding ->
        if (scans.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.empty_history),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(scans, key = { it.id }) { scan ->
                    ListItem(
                        headlineContent = { Text(scan.title) },
                        supportingContent = { Text(formatDate(scan.createdAt)) },
                        trailingContent = {
                            IconButton(onClick = {
                                val pdf = File(File(context.filesDir, "scans"), scan.pdfFileName)
                                mailSender.send(scan.title, pdf, settings.standardText)
                            }) {
                                Icon(
                                    Icons.Filled.Email,
                                    contentDescription = stringResource(R.string.send_as_mail)
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.save_title)) },
            text = {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { saveNow() }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val errorText = saveErrorDetail
    if (errorText != null) {
        AlertDialog(
            onDismissRequest = { saveErrorDetail = null },
            title = { Text(stringResource(R.string.error_save_title)) },
            text = { Text(errorText) },
            confirmButton = {
                TextButton(onClick = { saveErrorDetail = null }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EinstellungenScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    var text by remember { mutableStateOf(settings.standardText) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.standard_text),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    settings.standardText = it
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        }
    }
}

private fun defaultTitle(): String =
    "Scan " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())

private fun formatDate(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(millis))

// Liefert null zurück, wenn das Speichern klappte; sonst eine Klartext-Ursache für den Dialog.
private suspend fun saveScan(
    context: Context,
    photoFile: File,
    photoUri: Uri,
    title: String,
    dao: ScanDao
): String? = withContext(Dispatchers.IO) {
    try {
        // Erst Maße lesen — manche Kameras (z. B. GrapheneOS-Camera) melden der aufrufenden
        // App Erfolg, bevor das Foto fertig geschrieben ist: kurz warten und erneut probieren.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        var readable = false
        for (attempt in 0..9) {
            context.contentResolver.openInputStream(photoUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth > 0) {
                readable = true
                break
            }
            delay(500)
        }
        if (!readable) {
            return@withContext context.getString(
                R.string.error_photo_unreadable, photoFile.length()
            )
        }

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 620) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(photoUri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        if (bitmap == null) {
            return@withContext context.getString(R.string.error_photo_empty)
        }

        val safeFileTitle = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
        val fileName = System.currentTimeMillis().toString() + "_" + safeFileTitle + ".pdf"
        val scansDir = File(context.filesDir, "scans").apply { mkdirs() }
        val pdfFile = File(scansDir, fileName)

        PdfWriter.write(bitmap, pdfFile)
        bitmap.recycle()

        dao.insert(
            ScanEntity(
                title = title,
                createdAt = System.currentTimeMillis(),
                pdfFileName = fileName
            )
        )
        null
    } catch (e: Exception) {
        "Fehler: " + (e.message ?: e.javaClass.simpleName)
    }
}
