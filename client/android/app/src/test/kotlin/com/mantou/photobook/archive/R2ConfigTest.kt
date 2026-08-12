package com.mantou.photobook.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
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

    @Test
    fun `one connection resolves multiple prefix targets with shared credentials`() {
        val connection = connection()
        val family = R2BackupTarget.create(connection, "家庭相册", "family")
        val travel = R2BackupTarget.create(connection, "旅行收藏", "/travel/")
        val settings = R2Settings(listOf(connection), listOf(family, travel))

        assertEquals(connection.connectionId, family.connectionId)
        assertNotEquals(family.targetId, travel.targetId)
        assertEquals("family", settings.resolve(family.targetId)?.prefix)
        assertEquals("access-key", settings.resolve(travel.targetId)?.accessKeyId)
    }

    @Test
    fun `legacy config migrates to one connection and one target`() {
        val legacy = config(prefix = "legacy-prefix")

        val migrated = R2Settings.fromJson(legacy.toJson())

        assertEquals(1, migrated.connections.size)
        assertEquals(1, migrated.targets.size)
        assertEquals("legacy-prefix", migrated.targets.single().prefix)
        assertEquals(legacy.backupTargetId, migrated.targets.single().targetId)
    }

    @Test
    fun `settings summaries expose no credentials`() {
        val connection = connection()
        val target = R2BackupTarget.create(connection, "默认备份", "photobook")
        val summary = R2Settings(listOf(connection), listOf(target)).summary()

        assertTrue(summary.toString().contains("ac****ey"))
        assertEquals(false, summary.toString().contains("secret-key"))
        assertEquals(false, summary.toString().contains("access-key"))
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

    private fun connection(): R2Connection =
        R2Connection.fromMap(
            mapOf(
                "endpoint" to "https://example.r2.cloudflarestorage.com",
                "bucket" to "photobook-test",
                "accessKeyId" to "access-key",
                "secretAccessKey" to "secret-key",
            ),
        )
}
