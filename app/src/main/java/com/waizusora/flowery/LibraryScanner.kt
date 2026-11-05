package com.waizusora.flowery

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class AudioTrackItem(
    val uri: Uri,
    val title: String,
    val folder: String,
    val mimeType: String?,
    val sidecarLyricsUri: Uri? = null,
    val cueStartMs: Long? = null,
    val cueEndMs: Long? = null,
    val cuePlaylist: String? = null
)

class LibraryScanner(private val context: Context) {
    private val extensions = setOf(
        "mp3", "flac", "wav", "wave", "aiff", "aif", "alac", "m4a", "mp4",
        "aac", "ogg", "opus", "oga", "wma", "ape", "wv", "tta", "mka",
        "m4b", "dsf", "dff"
    )

    fun scan(roots: List<LibraryFolder>): List<AudioTrackItem> {
        val result = LinkedHashMap<String, AudioTrackItem>()
        roots.filter { it.enabled }.forEach { root ->
            val document = DocumentFile.fromTreeUri(context, Uri.parse(root.uri)) ?: return@forEach
            visit(document, root.name, result)
        }
        return result.values.toList()
    }

    private fun visit(directory: DocumentFile, rootName: String, output: MutableMap<String, AudioTrackItem>) {
        val children = directory.listFiles()
        val lyricFiles = children.filter { it.isFile && it.name?.endsWith(".lrc", ignoreCase = true) == true }
            .associateBy { it.name?.substringBeforeLast('.')?.lowercase().orEmpty() }
        val cueFiles = children.filter { it.isFile && it.name?.endsWith(".cue", ignoreCase = true) == true }
        children.forEach { file ->
            if (file.isDirectory) {
                visit(file, rootName, output)
            } else if (file.isFile) {
                val name = file.name ?: return@forEach
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension in extensions) {
                    output[file.uri.toString()] = AudioTrackItem(
                        uri = file.uri,
                        title = name.substringBeforeLast('.'),
                        folder = rootName,
                        mimeType = file.type,
                        sidecarLyricsUri = lyricFiles[name.substringBeforeLast('.').lowercase()]?.uri
                    )
                }
            }
        }
        val encoding = context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
            .getString("library_text_encoding", "UTF-8")
        cueFiles.forEach cueLoop@{ cueFile ->
            val cueName = cueFile.name ?: "CUE playlist"
            val cueText = runCatching {
                context.contentResolver.openInputStream(cueFile.uri)?.use {
                    LibraryTextEncoding.decode(it.readBytes(), encoding)
                }
            }.getOrNull() ?: return@cueLoop
            val sheet = CuePlaylistParser.parseCue(cueText)
            sheet.tracks.forEach trackLoop@{ cueTrack ->
                val audioFile = children.firstOrNull {
                    it.isFile && it.name.equals(cueTrack.fileName, ignoreCase = true)
                } ?: return@trackLoop
                val item = AudioTrackItem(
                    uri = audioFile.uri,
                    title = cueTrack.title,
                    folder = rootName,
                    mimeType = audioFile.type,
                    cueStartMs = cueTrack.startTimeMs,
                    cueEndMs = cueTrack.endTimeMs,
                    cuePlaylist = cueName
                )
                output["${audioFile.uri}#cue:${cueName}:${cueTrack.number}"] = item
            }
        }
    }
}
