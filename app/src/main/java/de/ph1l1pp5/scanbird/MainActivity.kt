package de.ph1l1pp5.scanbird

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import de.ph1l1pp5.scanbird.data.AppDatabase
import de.ph1l1pp5.scanbird.data.BackupStore
import de.ph1l1pp5.scanbird.data.ScanDao
import de.ph1l1pp5.scanbird.data.ScanEntity
import de.ph1l1pp5.scanbird.data.ScanStore
import de.ph1l1pp5.scanbird.data.SettingsStore
import de.ph1l1pp5.scanbird.mail.MailSender
import de.ph1l1pp5.scanbird.ocr.TitleOcr
import de.ph1l1pp5.scanbird.pdf.PdfWriter
import de.ph1l1pp5.scanbird.scan.DocumentFinder
import de.ph1l1pp5.scanbird.scan.ScanPolish
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

private enum class Screen { Verlauf, Einstellungen, CloudSpeicher }

@Composable
fun ScanbirdApp() {
    var screen by remember { mutableStateOf(Screen.Verlauf) }

    when (screen) {
        Screen.Verlauf -> VerlaufScreen(onOpenSettings = { screen = Screen.Einstellungen })
        Screen.Einstellungen -> EinstellungenScreen(
            onBack = { screen = Screen.Verlauf },
            onOpenCleanup = { screen = Screen.CloudSpeicher }
        )
        Screen.CloudSpeicher -> CloudCleanupScreen(onBack = { screen = Screen.Einstellungen })
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VerlaufScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).scanDao() }
    val settings = remember { SettingsStore(context) }
    val mailSender = remember { MailSender(context) }
    val scope = rememberCoroutineScope()

    val scans by dao.allNewestFirst().collectAsState(initial = emptyList())

    // Start-Catch-up des SAF-Backups (0.5.0): Alles, was beim letzten Mal nicht
    // mehr in die Cloud kam, wird hier still nachgezogen — auch nach einem
    // Offline-Tag oder App-Neustart. Läuft schweigend im Hintergrund.
    LaunchedEffect(Unit) {
        try { BackupStore.ensureBackedUp(context, dao) } catch (e: Exception) { }
    }

    // Simplicity-Regel (0.5.2): Niemand soll die Backup-Einrichtung in den
    // Einstellungen suchen muessen. Die App fragt genau dann nach, wenn zum
    // ersten Mal ein Scan im Verlauf liegt — ein Tap auf „Nein danke" beendet
    // die Nachfrage fuer immer, die Einstellungen bleiben trotzdem moeglich.
    var showBackupPrompt by remember { mutableStateOf(false) }
    val backupPromptPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null && result.resultCode == android.app.Activity.RESULT_OK) {
            BackupStore.saveTarget(context, uri)
            // Ziel frisch gewaehlt: den ganzen Rueckstand sofort still nachziehen.
            scope.launch {
                try { BackupStore.ensureBackedUp(context, dao) } catch (e: Exception) { }
            }
        }
        showBackupPrompt = false
    }
    LaunchedEffect(scans) {
        if (scans.isNotEmpty() &&
            !settings.backupPromptDeclined &&
            settings.backupTreeUri == null
        ) {
            showBackupPrompt = true
        }
    }

    if (showBackupPrompt) {
        AlertDialog(
            onDismissRequest = { showBackupPrompt = false },
            title = { Text(stringResource(R.string.backup_prompt_title)) },
            text = { Text(stringResource(R.string.backup_prompt_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showBackupPrompt = false
                    backupPromptPicker.launch(BackupStore.pickFolderIntent())
                }) { Text(stringResource(R.string.backup_prompt_choose)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    settings.backupPromptDeclined = true
                    showBackupPrompt = false
                }) { Text(stringResource(R.string.backup_prompt_decline)) }
            }
        )
    }

    var showScanner by remember { mutableStateOf(false) }
    var viewScan by remember { mutableStateOf<ScanEntity?>(null) }
    var pendingPages by remember { mutableStateOf(emptyList<File>()) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var titleInput by remember { mutableStateOf("") }
    var saveErrorDetail by remember { mutableStateOf<String?>(null) }

    // Menü per langem Druck: Umbenennen, Ordner öffnen, Löschen, Auswählen.
    var scanForRename by remember { mutableStateOf<ScanEntity?>(null) }
    var scanForDelete by remember { mutableStateOf<ScanEntity?>(null) }
    val folderAvailable = remember { ScanStore.folderIntent(context) != null }

    // Mehrfachauswahl fürs Löschen: langes Drücken → „Auswählen", dann Häkchen setzen.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var confirmDeleteMany by remember { mutableStateOf(false) }

    fun openScanFolder() {
        val intent = ScanStore.folderIntent(context)
        if (intent == null) return
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Fallback: Download-Übersicht des Systems
            try {
                context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (e2: Exception) {
            }
        }
    }

    fun mailScan(scan: ScanEntity) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                ScanStore.shareUri(context, ScanStore.locate(context, scan.pdfFileName))
            }
            mailSender.send(scan.title, uri, settings.standardText)
        }
    }

    /** Kern des Umbenennens (IO): Datei und Verlaufseintrag auf den neuen Titel ziehen. */
    suspend fun renameScanTo(scan: ScanEntity, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val safeTitle = fileTitle(newTitle)
        // Zeitstempel-Präfix bleibt — Sortierung läuft weiter über den Zeitstempel.
        // Separator "__" (ab 0.2.9); alte Namen nutzten "_".
        val base = scan.pdfFileName.removeSuffix(".pdf")
        val prefix = if (base.contains("__")) base.substringBefore("__") else base.substringBefore('_')
        val newFileName = prefix + "__" + safeTitle + ".pdf"
        val actual = ScanStore.rename(
            context, ScanStore.locate(context, scan.pdfFileName), newFileName
        )
        if (actual != null) {
            dao.update(scan.copy(title = newTitle, pdfFileName = actual, backedUp = false))
            // Alte Backup-Kopie entsorgen; die neue laedt beim naechsten
            // Sicherungslauf unter ihrem neuen Namen hoch.
            BackupStore.removeBackupName(context, scan.pdfFileName)
            true
        } else {
            false
        }
    }

    fun applyRename(scan: ScanEntity, newTitle: String) {
        scope.launch {
            val renamed = renameScanTo(scan, newTitle)
            if (!renamed) {
                Toast.makeText(context, R.string.error_rename, Toast.LENGTH_LONG).show()
            }
            // Umbenannt oder nicht — offene Sicherungen ziehen leise nach.
            try { BackupStore.ensureBackedUp(context, dao) } catch (e: Exception) { }
        }
    }

    // M3 — Titel automatisch erkennen: Tesseract liest komplett offline, asynchron
    // nach dem Speichern. Nur wenn niemand inzwischen von Hand benannt hat,
    // setzt sich der erkannte Titel durch; der Verlauf aktualisiert sich von selbst.
    // Danach (finally) sichert das SAF-Backup still den Rueckstand — die OCR
    // benennt meist noch um, also erst NACH ihr kopieren, sonst laege der
    // alte Name in der Cloud.
    fun autoTitleScan(fileName: String?, savedTitle: String?) {
        if (fileName == null || savedTitle == null) return
        scope.launch {
            try {
                val newTitle = withContext(Dispatchers.IO) {
                    try {
                        TitleOcr.extractTitle(context, fileName)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (newTitle.isNullOrBlank() || newTitle == savedTitle) return@launch
                val scanEntry = withContext(Dispatchers.IO) { dao.findByFile(fileName) } ?: return@launch
                if (scanEntry.title != savedTitle) return@launch
                renameScanTo(scanEntry, newTitle)
            } finally {
                try { BackupStore.ensureBackedUp(context, dao) } catch (e: Exception) { }
            }
        }
    }

    fun applyDelete(scan: ScanEntity) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val location = ScanStore.locate(context, scan.pdfFileName)
                val fileGone = ScanStore.delete(context, location)
                if (fileGone) {
                    dao.delete(scan)
                }
                fileGone
            }
            if (!deleted) {
                Toast.makeText(context, R.string.error_delete, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun applyDeleteMany(ids: Set<Long>) {
        scope.launch {
            var anyFailed = false
            withContext(Dispatchers.IO) {
                for (scanEntry in scans) {
                    if (scanEntry.id !in ids) continue
                    val location = ScanStore.locate(context, scanEntry.pdfFileName)
                    val gone = ScanStore.delete(context, location) ||
                        (location is ScanStore.Location.Internal && !location.file.exists())
                    if (gone) {
                        dao.delete(scanEntry)
                    } else {
                        anyFailed = true
                    }
                }
            }
            selectionMode = false
            selectedIds = emptySet()
            if (anyFailed) {
                Toast.makeText(context, R.string.error_delete, Toast.LENGTH_LONG).show()
            }
        }
    }

    val openScan = viewScan
    if (openScan != null) {
        PdfPreviewScreen(
            title = openScan.title,
            fileName = openScan.pdfFileName,
            onBack = { viewScan = null }
        )
        return
    }

    fun saveNow() {
        val files = pendingPages
        if (files.isEmpty()) {
            showSaveDialog = false
            return
        }
        val title = titleInput.ifBlank { defaultTitle() }
        scope.launch {
            val outcome = saveScan(context, files, title, dao)
            showSaveDialog = false
            if (outcome.error == null) {
                pendingPages = emptyList()
                showScanner = false
                autoTitleScan(outcome.fileName, outcome.title)
            } else {
                // Fehlgeschlagene Seiten rueumen wir weg; der Fehler-Dialog lebt
                // im Haupt-Baum, also dorthin zurueck.
                for (file in files) {
                    try {
                        file.delete()
                    } catch (e: Exception) {
                    }
                }
                pendingPages = emptyList()
                showScanner = false
                saveErrorDetail = outcome.error
            }
        }
    }

    if (showScanner) {
        ScanScreen(
            paused = showSaveDialog,
            onClose = {
                showScanner = false
                pendingPages = emptyList()
            },
            onCapture = { files ->
                // Titel-Vorschlag nur bei der ersten Seite — spaetere Seiten
                // scannen in dasselbe Dokument, der Titel bleibt der von Seite 1.
                val firstPage = pendingPages.isEmpty()
                pendingPages = files
                if (firstPage) {
                    titleInput = defaultTitle()
                }
                showSaveDialog = true
            }
        )
        // Der Speichern-Dialog liegt im Scanner-Zweig: Die Kamera bleibt dahinter
        // offen, der Weiter-Knopf kehrt direkt zurueck ins Suchbild.
        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text(stringResource(R.string.save_title)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = titleInput,
                            onValueChange = { titleInput = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (pendingPages.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.save_dialog_pages, pendingPages.size),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { saveNow() }) {
                        Text(stringResource(R.string.save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) {
                        Text(stringResource(R.string.continue_scan))
                    }
                }
            )
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) stringResource(R.string.selected_count, selectedIds.size)
                        else stringResource(R.string.app_name)
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(
                            onClick = { confirmDeleteMany = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete_scan)
                            )
                        }
                    } else {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.settings)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                showSaveDialog = false
                pendingPages = emptyList()
                showScanner = true
            }) {
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
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(scans, key = { it.id }) { scan ->
                    var menuOpen by remember { mutableStateOf(false) }
                    val isSelected = scan.id in selectedIds
                    Box {
                        // Verlaufs-Pille im AutoSnap-Stil: volle Breite, Neon-Cyan-Rand,
                        // Innenleben in Hintergrundfarbe, rechteckig mit runden Ecken —
                        // so bekommt der Inhalt mehr Platz als in einer Rundpille.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            selectedIds = if (isSelected) selectedIds - scan.id
                                            else selectedIds + scan.id
                                        } else {
                                            viewScan = scan
                                        }
                                    },
                                    onLongClick = { if (!selectionMode) menuOpen = true }
                                )
                                .border(
                                    width = 2.dp,
                                    color = Color(0xFF00FFFF).copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectionMode) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        selectedIds = if (isSelected) selectedIds - scan.id
                                        else selectedIds + scan.id
                                    }
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    scan.title,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    formatDate(scan.createdAt),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (!selectionMode) {
                                IconButton(onClick = { mailScan(scan) }) {
                                    Icon(
                                        Icons.Filled.Email,
                                        contentDescription = stringResource(R.string.send_as_mail)
                                    )
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.select)) },
                                onClick = {
                                    menuOpen = false
                                    selectionMode = true
                                    selectedIds = setOf(scan.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                onClick = {
                                    menuOpen = false
                                    scanForRename = scan
                                }
                            )
                            if (folderAvailable) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.open_folder)) },
                                    onClick = {
                                        menuOpen = false
                                        openScanFolder()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_scan)) },
                                onClick = {
                                    menuOpen = false
                                    scanForDelete = scan
                                }
                            )
                        }
                    }
                }
            }
        }
    }


    val scanToRename = scanForRename
    if (scanToRename != null) {
        var renameInput by remember { mutableStateOf(scanToRename.title) }
        AlertDialog(
            onDismissRequest = { scanForRename = null },
            title = { Text(stringResource(R.string.save_title)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newTitle = renameInput.ifBlank { scanToRename.title }
                    scanForRename = null
                    applyRename(scanToRename, newTitle)
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { scanForRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val scanToDelete = scanForDelete
    if (scanToDelete != null) {
        AlertDialog(
            onDismissRequest = { scanForDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_title, scanToDelete.title)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    scanForDelete = null
                    applyDelete(scanToDelete)
                }) {
                    Text(stringResource(R.string.delete_scan))
                }
            },
            dismissButton = {
                TextButton(onClick = { scanForDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (confirmDeleteMany) {
        AlertDialog(
            onDismissRequest = { confirmDeleteMany = false },
            title = { Text(stringResource(R.string.delete_confirm_many_title, selectedIds.size)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteMany = false
                    applyDeleteMany(selectedIds)
                }) {
                    Text(stringResource(R.string.delete_scan))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteMany = false }) {
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
fun EinstellungenScreen(onBack: () -> Unit, onOpenCleanup: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val dao = remember { AppDatabase.get(context).scanDao() }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(settings.standardText) }
    var backupTarget by remember { mutableStateOf(settings.backupTreeUri) }

    // Zielordner-Picker: StartActivityForResult statt OpenDocumentTree-Contract,
    // denn nur der Intent aus BackupStore nimmt die persist-Berechtigung mit —
    // ohne sie waere der Zugriff nach dem App-Neustart weg.
    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null && result.resultCode == android.app.Activity.RESULT_OK) {
            BackupStore.saveTarget(context, uri)
            backupTarget = uri
            // Ziel frisch gewaehlt: den ganzen Rueckstand sofort still nachziehen.
            scope.launch {
                try { BackupStore.ensureBackedUp(context, dao) } catch (e: Exception) { }
            }
        }
    }

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
                .verticalScroll(rememberScrollState())
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

            // Backup (0.5.0): SAF-Zielordner, provider-agnostisch — die App
            // kennt weder Cloud noch Account, sie spricht nur mit dem Ordner.
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.backup),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.backup_desc),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            val target = backupTarget
            Text(
                if (target != null) {
                    stringResource(R.string.backup_target_set, BackupStore.displayName(target))
                } else {
                    stringResource(R.string.backup_target_missing)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { backupPicker.launch(BackupStore.pickFolderIntent()) }) {
                Text(stringResource(R.string.choose_backup_folder))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.backup_hint),
                style = MaterialTheme.typography.bodySmall
            )
            if (backupTarget != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenCleanup) {
                    Text(stringResource(R.string.cloud_cleanup))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    BackupStore.clearTarget(context)
                    backupTarget = null
                }) {
                    Text(stringResource(R.string.remove_backup_target))
                }
            }
        }
    }
}

/**
 * Cloud-Aufräumen (0.5.1): Der Zielordner von außen betrachtet — Dateien aus
 * der Cloud ankreuzen und gezielt löschen, ohne die Scans auf dem Gerät zu
 * berühren. Aufgeräumte Dateien werden bewusst NICHT wieder hochgeladen.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CloudCleanupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupTarget by remember { mutableStateOf(SettingsStore(context).backupTreeUri) }
    var files by remember { mutableStateOf<List<BackupStore.BackupFile>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }

    // Ordnerinhalt frisch laden: beim Betreten und nach jedem Löschen.
    LaunchedEffect(backupTarget, reloadTick) {
        if (backupTarget == null) {
            files = null
        } else {
            files = withContext(Dispatchers.IO) { BackupStore.listBackupFiles(context) }
            val ids = files.orEmpty().map { it.documentId }.toSet()
            selected = selected.intersect(ids)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_storage)) },
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
        if (backupTarget == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    stringResource(R.string.cloud_no_target),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                val list = files
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.cloud_file_count, list?.size ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (!list.isNullOrEmpty()) {
                        TextButton(onClick = {
                            selected = if (selected.size == list.size) {
                                emptySet()
                            } else {
                                list.map { it.documentId }.toSet()
                            }
                        }) {
                            Text(
                                stringResource(
                                    if (selected.size == list.size) {
                                        R.string.cloud_select_none
                                    } else {
                                        R.string.cloud_select_all
                                    }
                                )
                            )
                        }
                    }
                }

                when {
                    list == null -> Text(
                        stringResource(R.string.pdf_loading),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    list.isEmpty() -> Text(
                        stringResource(R.string.cloud_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(list, key = { it.documentId }) { file ->
                            val checked = file.documentId in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            selected = if (checked) {
                                                selected - file.documentId
                                            } else {
                                                selected + file.documentId
                                            }
                                        },
                                        onLongClick = { }
                                    )
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selected = if (checked) {
                                            selected - file.documentId
                                        } else {
                                            selected + file.documentId
                                        }
                                    }
                                )
                                Column {
                                    Text(
                                        file.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        formatDate(file.lastModified) + " · " +
                                            formatSize(file.size),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { confirmDelete = true },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.delete_scan))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.cloud_delete_title)) },
            text = { Text(stringResource(R.string.cloud_delete_text, selected.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val toDelete = selected.toList()
                    scope.launch {
                        val count = withContext(Dispatchers.IO) {
                            BackupStore.deleteBackupFiles(context, toDelete)
                        }
                        selected = emptySet()
                        reloadTick++
                        Toast.makeText(
                            context,
                            context.getString(R.string.cloud_deleted, count),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text(stringResource(R.string.delete_scan)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun defaultTitle(): String =
    "Scan " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())

private fun formatDate(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(millis))

/** Dateigrösse für die Cloud-Ansicht: MB ab einer Megabyte, sonst KB. */
private fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) {
        String.format(Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024.0))
    } else {
        String.format(Locale.GERMANY, "%d KB", bytes / 1024)
    }

// Liefert bei Erfolg Dateinamen und Titel des neuen Scans zurück; sonst eine Klartext-Ursache.
private suspend fun saveScan(
    context: Context,
    photoFiles: List<File>,
    title: String,
    dao: ScanDao
): SaveOutcome = withContext(Dispatchers.IO) {
    try {
        if (photoFiles.isEmpty()) {
            return@withContext SaveOutcome("Keine Seiten vorhanden.")
        }

        // Je Seite: dekodieren, aufrichten, Blatt finden, polieren — dann sofort
        // als JPEG-Seite packen und das Bitmap recyceln. So liegt nie mehr als
        // eine Seite als Pixel im Speicher, egal wie dick das Dokument wird.
        val pages = ArrayList<PdfWriter.Page>()
        for (photoFile in photoFiles) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            var readable = false
            for (attempt in 0..9) {
                BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
                if (bounds.outWidth > 0) {
                    readable = true
                    break
                }
                delay(500)
            }
            if (!readable) {
                return@withContext SaveOutcome(
                    context.getString(R.string.error_photo_unreadable, photoFile.length())
                )
            }

            var sample = 1
            // Volle Quellauflösung dekodieren (bis ~2100 px): Das Kamerabild liefert
            // mehr echte Details, als 150 dpi je hergeben — seit 0.4.2 wird das A4
            // mit 200 dpi geschrieben, und die Politur schärft nur, was reinkommt.
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2100) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val rawBitmap = BitmapFactory.decodeFile(photoFile.absolutePath, opts)
                ?: return@withContext SaveOutcome(context.getString(R.string.error_photo_empty))

            val upright = applyExifRotation(photoFile.absolutePath, rawBitmap)
            // Vollauflösung braucht Platz: das ungedrehte Original fliegt sofort
            // raus, sobald die aufrechte Kopie steht.
            if (upright !== rawBitmap) {
                rawBitmap.recycle()
            }

            val bitmap = DocumentFinder.extractPage(upright)
            if (bitmap !== upright) {
                upright.recycle()
            }
            // Flachbett-Look: Schatten, Überbelichtung und Knitter plätten,
            // Ränder füllen, Restschiefe geraderücken — wie vom Flachbett-Scanner.
            val polished = ScanPolish.polish(bitmap)
            bitmap.recycle()
            pages.add(PdfWriter.pageOf(polished))
            polished.recycle()
        }

        val safeFileTitle = fileTitle(title)
        // Kurzer, sauberer Dateiname: keine Millisekunden-Ziffernwurst, keine Leerzeichen —
        // lange Namen mit vielen Ziffern blockierten sonst Uploads und Teilen.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date())
        val fileName = "Scan_" + stamp + "__" + safeFileTitle + ".pdf"

        val target = ScanStore.createPdf(context, fileName)
        try {
            PdfWriter.writeTo(pages, target.stream)
            target.finish()
        } catch (e: Exception) {
            target.abandon()
            throw e
        }

        dao.insert(
            ScanEntity(
                title = title,
                createdAt = System.currentTimeMillis(),
                pdfFileName = fileName
            )
        )
        for (photoFile in photoFiles) {
            photoFile.delete()
        }
        SaveOutcome(null, fileName, title)
    } catch (e: Exception) {
        SaveOutcome("Fehler: " + (e.message ?: e.javaClass.simpleName))
    }
}

/** Ergebnis eines Speicherns: Fehlerklartext oder Dateiname und Titel des neuen Scans. */
private data class SaveOutcome(
    val error: String?,
    val fileName: String? = null,
    val title: String? = null
)

/**
 * Dateinamen-Feile: Umlaute deutsch transliterieren, Sonderzeichen raus, Ergebnis
 * reines ASCII. Umlaute im Dateinamen brachten sonst Uploads zum Straucheln —
 * im Verlauf steht der Titel trotzdem mit echten Umlauten.
 */
private fun fileTitle(raw: String): String =
    raw.replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        .replace("ß", "ss")
        .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
        .replace(' ', '_')
        .map { if (it.code in 32..126) it else '_' }
        .joinToString("")
        .let { cutAtWordBoundary(it, 40) }

/** Kappung an der Wortgrenze: nie mitten im Wort abschneiden („…Kultur Ba“). */
private fun cutAtWordBoundary(s: String, max: Int): String {
    if (s.length <= max) return s
    val cut = s.take(max)
    val atWord = cut.lastIndexOf('_')
    return if (atWord >= max / 2) cut.substring(0, atWord) else cut.trimEnd('_')
}

// JPEGs tragen die Drehung in den EXIF-Daten; BitmapFactory ignoriert sie, wir nicht.
private fun applyExifRotation(path: String, bitmap: Bitmap): Bitmap {
    val orientation = try {
        ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
