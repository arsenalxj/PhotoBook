package com.mantou.photobook.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstagramSessionStoreTest {
    private lateinit var context: Context
    private lateinit var cipher: FakeCipher
    private lateinit var store: InstagramSessionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        cipher = FakeCipher()
        store = InstagramSessionStore(context, cipher)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `round trip stores only encrypted payload`() {
        store.save(session())

        val restored = store.read()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        assertEquals("archive_user", restored?.username)
        assertEquals(InstagramSessionStatus.READY, restored?.status)
        assertFalse(preferences.all.values.any { it.toString().contains("session-secret") })
    }

    @Test
    fun `corrupted ciphertext is treated as missing session`() {
        store.save(session())
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString("session_ciphertext", "not-base64")
            .commit()

        assertNull(store.read())
    }

    @Test
    fun `clear removes ciphertext and destroys key`() {
        store.save(session())

        store.clear()

        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertTrue(preferences.all.isEmpty())
        assertTrue(cipher.deleted)
        assertNull(store.read())
    }

    private fun session(): InstagramSession =
        InstagramSession.fromPythonJson(
            """{"username":"archive_user","cookies":{"sessionid":"session-secret","csrftoken":"csrf-value"}}""",
            100,
        )

    private class FakeCipher : InstagramSessionCipher {
        var deleted = false

        override fun encrypt(plaintext: String): EncryptedInstagramSession =
            EncryptedInstagramSession(
                iv = "fake-iv",
                ciphertext = Base64.getEncoder().encodeToString(plaintext.toByteArray()),
            )

        override fun decrypt(encodedIv: String, encodedCiphertext: String): String =
            String(Base64.getDecoder().decode(encodedCiphertext))

        override fun deleteKey() {
            deleted = true
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "photobook_instagram_session"
    }
}
