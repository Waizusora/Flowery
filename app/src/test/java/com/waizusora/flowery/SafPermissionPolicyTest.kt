package com.waizusora.flowery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafPermissionPolicyTest {
    @Test
    fun acceptsExactUriWithPersistedReadGrant() {
        val grants = listOf(SafReadGrant("content://provider/tree/music", true))

        assertTrue(SafPermissionPolicy.hasReadGrant("content://provider/tree/music", grants))
    }

    @Test
    fun rejectsWriteOnlyGrant() {
        val grants = listOf(SafReadGrant("content://provider/tree/music", false))

        assertFalse(SafPermissionPolicy.hasReadGrant("content://provider/tree/music", grants))
    }

    @Test
    fun rejectsGrantForDifferentTreeUri() {
        val grants = listOf(SafReadGrant("content://provider/tree/other", true))

        assertFalse(SafPermissionPolicy.hasReadGrant("content://provider/tree/music", grants))
    }

    @Test
    fun rejectsBlankUriAndEmptyGrantSet() {
        assertFalse(SafPermissionPolicy.hasReadGrant("", listOf(SafReadGrant("", true))))
        assertFalse(SafPermissionPolicy.hasReadGrant("content://provider/tree/music", emptyList()))
    }
}
