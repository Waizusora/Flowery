package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class LibraryTextEncodingTest {
    @Test
    fun decodesSelectedCharset() {
        val bytes = "Música".toByteArray(Charset.forName("windows-1252"))
        assertEquals("Música", LibraryTextEncoding.decode(bytes, "windows-1252"))
        assertNotNull(
            LibraryTextEncoding.available().firstOrNull {
                it.name() == StandardCharsets.UTF_8.name()
            }
        )
    }

    @Test
    fun fallsBackToUtf8ForUnknownCharset() {
        val bytes = "東京".toByteArray(StandardCharsets.UTF_8)
        assertEquals("東京", LibraryTextEncoding.decode(bytes, "not-a-real-charset"))
    }
}
