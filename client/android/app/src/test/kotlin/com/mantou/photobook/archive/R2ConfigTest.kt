package com.mantou.photobook.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class R2ConfigTest {
    @Test
    fun `repository identity ignores credentials`() {
        val first = config(accessKey = "first", secret = "secret-a")
        val second = config(accessKey = "second", secret = "secret-b")

        assertEquals(first.backupTargetId, second.backupTargetId)
        assertEquals("photobook", first.basePrefix)
    }

    @Test
    fun `repository identity changes with prefix`() {
        val first = config(prefix = "photobook")
        val second = config(prefix = "another")

        assertNotEquals(first.backupTargetId, second.backupTargetId)
    }

    @Test
    fun `summary exposes repository identity without credentials`() {
        val config = config(accessKey = "access-key", secret = "secret-key")

        assertEquals(config.backupTargetId, config.summary()["backupTargetId"])
        assertEquals(false, config.summary().containsKey("secretAccessKey"))
        assertEquals(false, config.summary().containsKey("accessKeyId"))
    }

    @Test
    fun `rejects insecure endpoint and parent path`() {
        assertThrows(IllegalArgumentException::class.java) {
            config(endpoint = "http://example.r2.cloudflarestorage.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(prefix = "photobook/../other")
        }
    }

    private fun config(
        endpoint: String = "https://example.r2.cloudflarestorage.com/",
        prefix: String = "photobook",
        accessKey: String = "key",
        secret: String = "secret",
    ): R2Config =
        R2Config.fromMap(
            mapOf(
                "endpoint" to endpoint,
                "bucket" to "photobook-test",
                "prefix" to prefix,
                "accessKeyId" to accessKey,
                "secretAccessKey" to secret,
            ),
        )
}
