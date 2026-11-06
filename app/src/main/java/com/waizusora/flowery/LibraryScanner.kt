package com.waizusora.flowery

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

data class AudioTrackItem(
    val uri: Uri, val title: String, val folder: String, val mimeType: String?,
    val sidecarLyricsUri: Uri? = null, val cueStartMs: Long? = null, val cueEndMs: Long? = null,
    val cuePlaylist: String? = null, val playlistName: String? = null, val playlistOrder: Int? = null
)

/** SAF adapter. Rust owns catalog policy; Kotlin enumerates documents and maps results. */
class LibraryScanner(private val context: Context) {
    fun scan(roots: List<LibraryFolder>): List<AudioTrackItem> {
        val snapshot = JSONArray()
        roots.filter { it.enabled }.forEach { root ->
            val document = DocumentFile.fromTreeUri(context, Uri.parse(root.uri)) ?: return@forEach
            val entries = JSONArray()
            collect(document, "", entries)
            snapshot.put(JSONObject().put("name", root.name).put("entries", entries))
        }
        val tracks = JSONObject(
            NativeAudio.nativeBuildLibraryCatalog(JSONObject().put("roots", snapshot).toString())
        ).getJSONArray("tracks")
        return buildList(tracks.length()) {
            for (index in 0 until tracks.length()) {
                val item = tracks.getJSONObject(index)
                add(
                    AudioTrackItem(
                        uri = Uri.parse(item.getString("uri")),
                        title = item.getString("title"),
                        folder = item.getString("folder"),
                        mimeType = item.optString("mimeType").takeIf { it.isNotEmpty() },
                        sidecarLyricsUri = item.optString("sidecarLyricsUri")
                            .takeIf { it.isNotEmpty() }?.let(Uri::parse),
                        cueStartMs = item.nullableLong("cueStartMs"),
                        cueEndMs = item.nullableLong("cueEndMs"),
                        cuePlaylist = item.optString("cuePlaylist").takeIf { it.isNotEmpty() },
                        playlistName = item.optString("playlistName").takeIf { it.isNotEmpty() },
                        playlistOrder = item.nullableInt("playlistOrder")
                    )
                )
            }
        }
    }

    private fun collect(directory: DocumentFile, parentPath: String, output: JSONArray) {
        val children = runCatching { directory.listFiles().toList() }.getOrDefault(emptyList())
        children.forEach { child ->
            val name = child.name ?: return@forEach
            val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
            val entry = JSONObject()
                .put("path", path)
                .put("name", name)
                .put("uri", child.uri.toString())
                .put("mimeType", child.type ?: JSONObject.NULL)
                .put("directory", child.isDirectory)
                .put("text", JSONObject.NULL)
            if (child.isFile && isPlaylistText(name)) {
                val encoding = if (name.endsWith(".m3u8", ignoreCase = true)) "UTF-8"
                else context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
                    .getString("library_text_encoding", "UTF-8") ?: "UTF-8"
                runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use {
                        LibraryTextEncoding.decode(it.readBytes(), encoding)
                    }
                }.getOrNull()?.let { entry.put("text", it) }
            }
            output.put(entry)
            if (child.isDirectory) collect(child, path, output)
        }
    }

    private fun isPlaylistText(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("cue", "m3u", "m3u8", "pls")

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key)) null else optLong(key)

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key)) null else optInt(key)
}
