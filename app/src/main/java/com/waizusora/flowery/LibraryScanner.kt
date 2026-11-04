package com.waizusora.flowery

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class AudioTrackItem(
    val uri: Uri,
    val title: String,
    val folder: String,
    val mimeType: String?
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
        directory.listFiles().forEach { file ->
            if (file.isDirectory) {
                visit(file, rootName, output)
            } else if (file.isFile) {
                val extension = file.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
                if (extension in extensions) {
                    output[file.uri.toString()] = AudioTrackItem(
                        file.uri,
                        file.name?.substringBeforeLast('.') ?: file.name ?: "Unknown track",
                        rootName,
                        file.type
                    )
                }
            }
        }
    }
}
