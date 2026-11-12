package com.waizusora.flowery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class LyricsSearchHit(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val hasPlain: Boolean,
    val hasSynced: Boolean,
    val hasWordSync: Boolean
)

@Composable
internal fun LyricsSearchDialog(
    initialQuery: String,
    onDismiss: () -> Unit,
    onDownload: (content: String, extension: String) -> Unit
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<LyricsSearchHit>>(emptyList()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun search(value: String) {
        if (value.isBlank()) return
        scope.launch {
            loading = true
            error = ""
            selected = null
            try {
                val body = withContext(Dispatchers.IO) { NativeAudio.nativeSearchLyrics(value.trim()) }
                val array = JSONArray(body)
                results = buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(LyricsSearchHit(
                            id = item.getLong("id"),
                            title = item.optString("trackName", item.optString("name")),
                            artist = item.optString("artistName"),
                            album = item.optString("albumName"),
                            hasPlain = !item.isNull("plainLyrics") && item.optString("plainLyrics").isNotBlank(),
                            hasSynced = !item.isNull("syncedLyrics") && item.optString("syncedLyrics").isNotBlank(),
                            hasWordSync = item.optBoolean("hasWordSync")
                        ))
                    }
                }
                if (results.isEmpty()) error = "No matching lyrics found."
            } catch (failure: Throwable) {
                error = failure.message ?: "Lyrics search failed."
                results = emptyList()
            } finally {
                loading = false
            }
        }
    }

    fun select(hit: LyricsSearchHit) {
        scope.launch {
            loading = true
            error = ""
            try {
                val body = withContext(Dispatchers.IO) { NativeAudio.nativeGetLyrics(hit.id) }
                selected = JSONObject(body)
            } catch (failure: Throwable) {
                error = failure.message ?: "Unable to retrieve selected lyrics."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(initialQuery) {
        query = initialQuery
        if (initialQuery.isNotBlank()) search(initialQuery)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search lyrics · LRCLIB") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Song title or artist") }
                    )
                    Button(onClick = { search(query) }, enabled = query.isNotBlank() && !loading) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
                if (loading) CircularProgressIndicator()
                if (error.isNotBlank()) Text(error)
                val record = selected
                if (record != null) {
                    Text(record.optString("trackName"))
                    Text(record.optString("artistName"))
                    val lyricsFile = record.optString("lyricsfile").takeUnless { it == "null" }.orEmpty()
                    val synced = record.optString("syncedLyrics").takeUnless { it == "null" }.orEmpty()
                    val plain = record.optString("plainLyrics").takeUnless { it == "null" }.orEmpty()
                    if (record.optBoolean("hasWordSync") && lyricsFile.isNotBlank()) {
                        Button(onClick = { onDownload(lyricsFile, "lyricsfile.yaml") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Download word-synced lyrics")
                        }
                    }
                    if (synced.isNotBlank()) {
                        Button(onClick = { onDownload(synced, "lrc") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Download synchronized LRC")
                        }
                    }
                    if (plain.isNotBlank()) {
                        TextButton(onClick = { onDownload(plain, "txt") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Download plain lyrics")
                        }
                    }
                    TextButton(onClick = { selected = null }) { Text("Back to results") }
                } else {
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(results, key = { it.id }) { hit ->
                            Surface(modifier = Modifier.fillMaxWidth().clickable(enabled = !loading) { select(hit) }) {
                                Column(Modifier.padding(vertical = 8.dp)) {
                                    Text(hit.title)
                                    Text(hit.artist)
                                    if (hit.album.isNotBlank()) Text(hit.album)
                                    val available = buildList {
                                        if (hit.hasWordSync) add("word sync")
                                        if (hit.hasSynced) add("synced")
                                        if (hit.hasPlain) add("plain")
                                    }.joinToString(" · ")
                                    Text(available.ifBlank { "No lyric text" })
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
