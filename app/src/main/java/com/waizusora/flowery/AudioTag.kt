package com.waizusora.flowery

import android.net.Uri

data class AudioTag(
    val uri: Uri,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: String,
    val track: String,
    val disc: String,
    val comment: String,
    val composer: String,
    val lyrics: String,
    val pictureMime: String,
    val pictureBase64: String,
    val tagType: String,
    val fileType: String,
    val writable: Boolean,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val bitrateKbps: Int,
    val durationMs: Long
)

