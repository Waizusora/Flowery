package com.waizusora.flowery

/** Minimal persisted SAF grant data used to validate a selected tree URI. */
internal data class SafReadGrant(
    val uri: String,
    val canRead: Boolean,
)

internal object SafPermissionPolicy {
    fun hasReadGrant(uri: String, grants: Iterable<SafReadGrant>): Boolean =
        uri.isNotBlank() && grants.any { it.uri == uri && it.canRead }
}
