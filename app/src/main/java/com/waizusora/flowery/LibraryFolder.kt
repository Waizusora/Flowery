package com.waizusora.flowery

/**
 * A persisted Storage Access Framework tree selected by the user.
 *
 * The URI is stored as a string so the folder configuration can be serialized
 * without retaining a live Android Uri instance.
 */
data class LibraryFolder(
    val uri: String,
    val name: String,
    val enabled: Boolean = true
)
