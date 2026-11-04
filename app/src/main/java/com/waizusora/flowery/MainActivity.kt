package com.waizusora.flowery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile

class MainActivity : ComponentActivity() {
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFolder(uri)
    }

    private val folders = mutableStateListOf<LibraryFolder>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreFolders()
        setContent { FloweryApp(
            folders = folders,
            onAddFolder = { folderPicker.launch(null) },
            onRemoveFolder = ::removeFolder,
            onToggleFolder = ::toggleFolder
        ) }
    }

    private fun addFolder(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        if (folders.none { it.uri == uri.toString() }) {
            val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "Folder"
            folders += LibraryFolder(uri.toString(), name, true)
            persistFolders()
        }
    }

    private fun removeFolder(folder: LibraryFolder) {
        folders.remove(folder)
        runCatching {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(folder.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        persistFolders()
    }

    private fun toggleFolder(folder: LibraryFolder, enabled: Boolean) {
        val index = folders.indexOf(folder)
        if (index >= 0) folders[index] = folder.copy(enabled = enabled)
        persistFolders()
    }

    private fun restoreFolders() {
        val prefs = getSharedPreferences("library", MODE_PRIVATE)
        val stored = prefs.getStringSet("folders", emptySet()).orEmpty()
        stored.forEach { entry ->
            val parts = entry.split("|", limit = 3)
            if (parts.size == 3) folders += LibraryFolder(parts[0], parts[1], parts[2].toBoolean())
        }
    }

    private fun persistFolders() {
        getSharedPreferences("library", MODE_PRIVATE).edit()
            .putStringSet("folders", folders.map { "${it.uri}|${it.name}|${it.enabled}" }.toSet())
            .apply()
    }
}

data class LibraryFolder(val uri: String, val name: String, val enabled: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun FloweryApp(
    folders: List<LibraryFolder>,
    onAddFolder: () -> Unit,
    onRemoveFolder: (LibraryFolder) -> Unit,
    onToggleFolder: (LibraryFolder, Boolean) -> Unit
) {
    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Flowery") }) }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Folder selection", style = MaterialTheme.typography.headlineSmall)
                Text("Choose multiple music folders. Access is retained between app launches.")
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(folders, key = { it.uri }) { folder ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(folder.name, style = MaterialTheme.typography.titleMedium)
                                    Text(folder.uri, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                                Checkbox(
                                    checked = folder.enabled,
                                    onCheckedChange = { onToggleFolder(folder, it) }
                                )
                                IconButton(onClick = { onRemoveFolder(folder) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove folder")
                                }
                            }
                        }
                    }
                }
                Divider()
                Button(onClick = onAddFolder, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  Add folder or storage")
                }
                Button(onClick = { /* Full scanning is added with the library scanner module. */ }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save and scan")
                }
            }
        }
    }
}
