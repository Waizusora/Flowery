package com.waizusora.flowery

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Indexed audio track from the library catalog.
 *
 * [title] and [artist] are filled from embedded tags when available (lossy and
 * lossless). When tags are missing, [title] falls back to the file stem and
 * [artist] is empty so the UI can show a neutral placeholder.
 */
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
    val playlistOrder: Int? = null,
    val artist: String = ""
)

/** SAF adapter. Rust owns catalog policy; Kotlin enumerates documents and maps results. */
class LibraryScanner(private val context: Context) {
    fun scan(roots: List<LibraryFolder>): List<AudioTrackItem> {
        val snapshot = JSONArray()
        roots.filter { it.enabled }.forEach { root ->
            val rootUri = Uri.parse(root.uri)
            val hasReadGrant = context.contentResolver.persistedUriPermissions.any {
                it.uri == rootUri && it.isReadPermission
            }
            if (!hasReadGrant) {
                throw SecurityException("Missing persisted read access for folder ${root.name}; select the folder again")
            }
            val document = DocumentFile.fromTreeUri(context, rootUri)
                ?: throw IllegalStateException("Unable to open selected folder: ${root.name}")
            if (!document.exists() || !document.canRead()) {
                throw SecurityException("Selected folder is no longer readable: ${root.name}")
            }
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
                val uri = Uri.parse(item.getString("uri"))
                val catalogTitle = item.getString("title")
                val isVirtual = !item.isNull("cuePlaylist") || !item.isNull("playlistName")
                val (title, artist) = if (isVirtual) {
                    catalogTitle to ""
                } else {
                    readDisplayMetadata(uri, catalogTitle)
                }
                add(
                    AudioTrackItem(
                        uri = uri,
                        title = title,
                        folder = item.getString("folder"),
                        mimeType = if (item.isNull("mimeType")) null else item.optString("mimeType"),
                        sidecarLyricsUri = if (item.isNull("sidecarLyricsUri")) null else Uri.parse(item.getString("sidecarLyricsUri")),
                        cueStartMs = item.nullableLong("cueStartMs"),
                        cueEndMs = item.nullableLong("cueEndMs"),
                        cuePlaylist = if (item.isNull("cuePlaylist")) null else item.getString("cuePlaylist"),
                        playlistName = if (item.isNull("playlistName")) null else item.getString("playlistName"),
                        playlistOrder = item.nullableInt("playlistOrder"),
                        artist = artist
                    )
                )
            }
        }
    }

    /**
     * Reads title and artist from the audio document for list display.
     * Uses MediaMetadataRetriever so both lossy and lossless containers are
     * covered without staging a full Lofty temp file. Falls back to the
     * catalog file-stem title when tags are absent.
     */
    private fun readDisplayMetadata(uri: Uri, fallbackTitle: String): Pair<String, String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val taggedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                .orEmpty()
            val taggedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()
                .orEmpty()
            val title = taggedTitle.ifBlank { fallbackTitle }
            title to taggedArtist
        } catch (_: Exception) {
            fallbackTitle to ""
        } finally {
            runCatching { retriever.release() }
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
