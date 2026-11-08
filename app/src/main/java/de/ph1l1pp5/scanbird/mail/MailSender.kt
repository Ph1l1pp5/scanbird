package de.ph1l1pp5.scanbird.mail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import de.ph1l1pp5.scanbird.R
import java.io.File

class MailSender(private val context: Context) {

    fun send(scanTitle: String, pdfFile: File, standardText: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "de.ph1l1pp5.scanbird.fileprovider",
            pdfFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, scanTitle)
            putExtra(Intent.EXTRA_TEXT, standardText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(
            intent,
            context.getString(R.string.mail_chooser_title)
        ).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)

        // Fallback: Thunderbird für Android übernimmt EXTRA_TEXT nicht immer
        // (vgl. thunderbird-android #1336) — der Standardtext liegt zusätzlich in der Ablage.
        if (standardText.isNotBlank()) {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(scanTitle, standardText))
            Toast.makeText(
                context,
                context.getString(R.string.standardtext_in_clipboard),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
