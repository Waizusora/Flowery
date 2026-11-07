package com.waizusora.flowery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp

@androidx.compose.runtime.Composable
internal fun TagInfoDialog(tag: AudioTag, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio file information") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "File" to tag.fileName, "Title" to tag.title, "Artist" to tag.artist,
                    "Album" to tag.album, "Album artist" to tag.albumArtist, "Genre" to tag.genre,
                    "Year" to tag.year, "Track" to tag.track, "Disc" to tag.disc,
                    "Composer" to tag.composer, "Comment" to tag.comment, "Tag type" to tag.tagType,
                    "Container" to tag.fileType, "Embedded lyrics" to tag.lyrics,
                    "Artwork" to if (tag.pictureBase64.isBlank()) "Not embedded" else tag.pictureMime,
                    "Tag writing" to if (tag.writable) "Supported by Lofty" else "Read-only for this format",
                    "Sample rate" to tag.sampleRate.takeIf { it > 0 }?.let { "$it Hz" }.orEmpty(),
                    "Bit depth" to tag.bitDepth.takeIf { it > 0 }?.let { "$it bit" }.orEmpty(),
                    "Channels" to tag.channels.takeIf { it > 0 }?.toString().orEmpty(),
                    "Bitrate" to tag.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" }.orEmpty(),
                    "Duration" to tag.durationMs.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60000, (it / 1000) % 60) }.orEmpty()
                ).forEach { (label, value) ->
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@androidx.compose.runtime.Composable
internal fun TagEditorDialog(tag: AudioTag, onDismiss: () -> Unit, onSave: (AudioTag) -> Unit) {
    var title by remember(tag) { mutableStateOf(tag.title) }
    var artist by remember(tag) { mutableStateOf(tag.artist) }
    var album by remember(tag) { mutableStateOf(tag.album) }
    var albumArtist by remember(tag) { mutableStateOf(tag.albumArtist) }
    var genre by remember(tag) { mutableStateOf(tag.genre) }
    var year by remember(tag) { mutableStateOf(tag.year) }
    var track by remember(tag) { mutableStateOf(tag.track) }
    var disc by remember(tag) { mutableStateOf(tag.disc) }
    var composer by remember(tag) { mutableStateOf(tag.composer) }
    var comment by remember(tag) { mutableStateOf(tag.comment) }
    var lyrics by remember(tag) { mutableStateOf(tag.lyrics) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(min = 360.dp, max = 600.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TagEditorField("Title", title) { title = it }
                    TagEditorField("Artist", artist) { artist = it }
                    TagEditorField("Album", album) { album = it }
                    TagEditorField("Album artist", albumArtist) { albumArtist = it }
                    TagEditorField("Genre", genre) { genre = it }
                    TagEditorField("Year / release date", year) { year = it }
                    TagEditorField("Track number", track) { track = it }
                    TagEditorField("Disc number", disc) { disc = it }
                    TagEditorField("Composer", composer) { composer = it }
                    TagEditorField("Comment", comment, singleLine = false) { comment = it }
                    TagEditorField("Lyrics", lyrics, singleLine = false) { lyrics = it }
                    Text(
                        "Artwork is preserved when the selected tag format supports it.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            onSave(tag.copy(
                                title = title, artist = artist, album = album, albumArtist = albumArtist,
                                genre = genre, year = year, track = track, disc = disc,
                                composer = composer, comment = comment, lyrics = lyrics
                            ))
                        }
                    ) { Text("Save") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
internal fun TagEditorField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine
    )
}
