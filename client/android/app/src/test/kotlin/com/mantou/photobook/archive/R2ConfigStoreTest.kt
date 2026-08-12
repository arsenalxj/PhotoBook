package com.mantou.photobook.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class R2ConfigStoreTest {
    private lateinit var context: Context
    private lateinit var cipher: FakeR2ConfigCipher
    private lateinit var store: R2ConfigStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences().edit().clear().commit()
        cipher = FakeR2ConfigCipher()
        store = R2ConfigStore(context, cipher)
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun `missing encrypted values means no R2 settings`() {
        assertEquals(R2Settings.EMPTY, store.read())
    }

    @Test
    fun `partial encrypted values report corrupted configuration`() {
        preferences().edit().putString("r2_config_iv", "fake-iv").commit()

        val error = assertThrows(ArchiveException::class.java) { store.read() }

        assertEquals("R2_CONFIG_CORRUPTED", error.code)
    }

    @Test
    fun `decryption failure is not treated as missing configuration`() {
        store.saveConnectionWithTarget(connection(), "默认备份", "photobook")
        cipher.decryptError = IllegalStateException("broken key")

        val error = assertThrows(ArchiveException::class.java) { store.read() }

        assertEquals("R2_CONFIG_UNREADABLE", error.code)
    }

    @Test
    fun `invalid decrypted JSON reports corrupted configuration`() {
        preferences().edit()
            .putString("r2_config_iv", "fake-iv")
            .putString(
                "r2_config_ciphertext",
                Base64.getEncoder().encodeToString("not-json".toByteArray()),
            ).commit()

        val error = assertThrows(ArchiveException::class.java) { store.read() }

        assertEquals("R2_CONFIG_CORRUPTED", error.code)
    }

    @Test
    fun `new connection rejects an existing endpoint and bucket`() {
        store.saveConnectionWithTarget(connection(), "默认备份", "photobook")

        val error = assertThrows(IllegalArgumentException::class.java) {
            store.saveConnectionWithTarget(
                connection(accessKey = "replacement", secret = "replacement-secret"),
                "另一个位置",
                "travel",
            )
        }

        assertEquals("R2 连接已存在，请在已有连接中添加位置", error.message)
    }

    @Test
    fun `editing a target only changes its name`() {
        val initial = store.saveConnectionWithTarget(connection(), "默认备份", "photobook")
        val target = initial.targets.single()

        val renamed = store.saveTarget(
            connectionId = target.connectionId,
            name = "家庭相册",
            prefix = target.prefix,
            previousTargetId = target.targetId,
        )

        assertEquals(target.targetId, renamed.targets.single().targetId)
        assertEquals("家庭相册", renamed.targets.single().name)
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.saveTarget(
                connectionId = target.connectionId,
                name = "旅行收藏",
                prefix = "travel",
                previousTargetId = target.targetId,
            )
        }
        assertEquals("Prefix 不能直接修改，请新增备份位置", error.message)
    }

    @Test
    fun `adding a duplicate target is rejected instead of overwritten`() {
        val initial = store.saveConnectionWithTarget(connection(), "默认备份", "photobook")

        val error = assertThrows(IllegalArgumentException::class.java) {
            store.saveTarget(
                connectionId = initial.connections.single().connectionId,
                name = "重复位置",
                prefix = "photobook",
            )
        }

        assertEquals("R2 备份位置已存在", error.message)
        assertEquals("默认备份", store.read().targets.single().name)
    }

    private fun preferences() =
        context.getSharedPreferences("photobook_secure", Context.MODE_PRIVATE)

    private fun connection(
        accessKey: String = "access-key",
        secret: String = "secret-key",
    ): R2Connection =
        R2Connection.fromMap(
            mapOf(
                "endpoint" to "https://example.r2.cloudflarestorage.com",
                "bucket" to "photobook-test",
                "accessKeyId" to accessKey,
                "secretAccessKey" to secret,
            ),
        )

    private class FakeR2ConfigCipher : R2ConfigCipher {
        var decryptError: Exception? = null

        override fun encrypt(plaintext: String): EncryptedR2Settings =
            EncryptedR2Settings(
                iv = "fake-iv",
                ciphertext = Base64.getEncoder().encodeToString(plaintext.toByteArray()),
            )

        override fun decrypt(encodedIv: String, encodedCiphertext: String): String {
            decryptError?.let { throw it }
            return String(Base64.getDecoder().decode(encodedCiphertext))
        }
    }
}
