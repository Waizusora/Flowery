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
    val cuePlaylist: String? = null,
    val playlistName: String? = null,
    val playlistOrder: Int? = null
)

class LibraryScanner(private val context: Context) {
    fun scan(roots: List<LibraryFolder>): List<AudioTrackItem> {
        val result = LinkedHashMap<String, AudioTrackItem>()
        roots.filter { it.enabled }.forEach { root ->
            val document = DocumentFile.fromTreeUri(context, Uri.parse(root.uri)) ?: return@forEach
            visit(document, root.name, result, listOf(document))
        }
        return result.values.toList()
    }

    private fun visit(directory: DocumentFile, rootName: String, output: MutableMap<String, AudioTrackItem>, pathStack: List<DocumentFile>) {
        val children = directory.listFiles()
        val lyricFiles = children.filter { it.isFile && it.name?.endsWith(".lrc", ignoreCase = true) == true }
            .associateBy { it.name?.substringBeforeLast('.')?.lowercase().orEmpty() }
        val cueFiles = children.filter { it.isFile && it.name?.endsWith(".cue", ignoreCase = true) == true }
        val playlistFiles = children.filter { it.isFile && (it.name?.substringAfterLast(".", "")?.lowercase() ?: "") in setOf("m3u", "m3u8", "pls") }
        children.forEach { file ->
            if (file.isDirectory) {
                visit(file, rootName, output, pathStack + file)
            } else if (file.isFile) {
                val name = file.name ?: return@forEach
                val extension = name.substringAfterLast(".", "").lowercase()
                if (NativeAudio.nativeIsSupportedAudioExtension(extension)) {
                    output[file.uri.toString()] = AudioTrackItem(
                        uri = file.uri, title = name.substringBeforeLast("."), folder = rootName,
                        mimeType = file.type,
                        sidecarLyricsUri = lyricFiles[name.substringBeforeLast(".").lowercase()]?.uri
                    )
                }
            }
        }
        val preferences = context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
        val selectedEncoding = preferences.getString("library_text_encoding", "UTF-8") ?: "UTF-8"
        cueFiles.forEach cueLoop@{ cueFile ->
            val cueName = (listOf(rootName) + pathStack.drop(1).mapNotNull { it.name } + (cueFile.name ?: "CUE playlist")).joinToString("/")
            val cueText = readText(cueFile, selectedEncoding) ?: return@cueLoop
            val sheet = CuePlaylistParser.parseCue(cueText)
            sheet.tracks.forEach trackLoop@{ cueTrack ->
                val audioFile = resolvePlaylistPath(pathStack, cueTrack.fileName) ?: return@trackLoop
                if (audioFile.isDirectory) return@trackLoop
                val item = AudioTrackItem(
                    uri = audioFile.uri, title = cueTrack.title, folder = rootName, mimeType = audioFile.type,
                    cueStartMs = cueTrack.startTimeMs, cueEndMs = cueTrack.endTimeMs, cuePlaylist = cueName
                )
                output[audioFile.uri.toString() + "#cue:" + cueName + ":" + cueTrack.number] = item
            }
        }
        playlistFiles.forEach playlistLoop@{ playlistFile ->
            val playlistName = (listOf(rootName) + pathStack.drop(1).mapNotNull { it.name } + (playlistFile.name ?: "Playlist")).joinToString("/")
            val extension = playlistName.substringAfterLast(".", "").lowercase()
            val playlistText = readText(playlistFile, if (extension == "m3u8") "UTF-8" else selectedEncoding) ?: return@playlistLoop
            val entries = if (extension == "pls") CuePlaylistParser.parsePls(playlistText) else CuePlaylistParser.parseM3u(playlistText)
            entries.forEachIndexed entryLoop@{ index, entry ->
                val audioFile = resolvePlaylistPath(pathStack, entry.path) ?: return@entryLoop
                if (audioFile.isDirectory) return@entryLoop
                val audioExtension = audioFile.name?.substringAfterLast(".", "")?.lowercase()
                if (audioExtension == null || !NativeAudio.nativeIsSupportedAudioExtension(audioExtension)) return@entryLoop
                val item = AudioTrackItem(
                    uri = audioFile.uri,
                    title = entry.title?.takeIf { it.isNotBlank() } ?: audioFile.name?.substringBeforeLast(".") ?: "Track " + (index + 1),
                    folder = rootName, mimeType = audioFile.type, playlistName = playlistName, playlistOrder = index
                )
                output[audioFile.uri.toString() + "#playlist:" + playlistName + ":" + index] = item
            }
        }
    }

    private fun readText(file: DocumentFile, encoding: String): String? = runCatching {
        context.contentResolver.openInputStream(file.uri)?.use { LibraryTextEncoding.decode(it.readBytes(), encoding) }
    }.getOrNull()

    private fun resolvePlaylistPath(pathStack: List<DocumentFile>, rawPath: String): DocumentFile? {
        val path = Uri.decode(rawPath.trim().removeSurrounding("\"").replace(92.toChar(), '/'))
        if (path.startsWith("content://", ignoreCase = true)) {
            return runCatching { DocumentFile.fromSingleUri(context, Uri.parse(path)) }.getOrNull()
        }
        if (path.isBlank() || path.startsWith("http://", true) || path.startsWith("https://", true)) return null
        val segments = path.split('/').filter { it.isNotBlank() && it != "." }
        if (segments.isEmpty()) return null
        val stack = pathStack.toMutableList()
        var resolved: DocumentFile? = null
        for ((index, segment) in segments.withIndex()) {
            if (segment == "..") { if (stack.size > 1) stack.removeAt(stack.lastIndex); continue }
            val parent = stack.lastOrNull() ?: break
            val next = parent.findFile(segment) ?: parent.listFiles().firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: break
            if (index < segments.lastIndex) {
                if (!next.isDirectory) break
                stack += next
            } else resolved = next
        }
        if (resolved != null) return resolved
        val targetName = segments.last()
        val matches = mutableListOf<DocumentFile>()
        fun search(directory: DocumentFile) {
            directory.listFiles().forEach { child ->
                if (child.isDirectory) search(child)
                else if (child.name.equals(targetName, ignoreCase = true)) matches += child
            }
        }
        pathStack.firstOrNull()?.let(::search)
        return matches.singleOrNull()
    }
}
