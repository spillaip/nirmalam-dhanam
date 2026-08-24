package com.nirmalamgroup.nirmalamdhanam.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

const val NDF_MIME_TYPE = "application/vnd.nirmalam-dhanam.backup+zip"

/**
 * Storage Access Framework controls. The host should ask for the user's passphrase, then pass
 * the returned Uri to NdfBackupManager.exportTo/importFrom from its ViewModel.
 */
@Composable
fun NdfBackupFilePickers(
    onExportDestinationSelected: (Uri) -> Unit,
    onImportSourceSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(NDF_MIME_TYPE)
    ) { uri -> uri?.let(onExportDestinationSelected) }
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportSourceSelected) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { createDocument.launch("nirmalam-dhanam-backup.ndf") }) { Text("Export .ndf") }
        OutlinedButton(onClick = { openDocument.launch(arrayOf(NDF_MIME_TYPE, "application/zip")) }) { Text("Import .ndf") }
    }
}
