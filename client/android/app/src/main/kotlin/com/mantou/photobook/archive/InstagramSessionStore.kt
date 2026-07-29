package com.mantou.photobook.archive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface InstagramSessionRepository {
    fun read(): InstagramSession?

    fun save(session: InstagramSession)

    fun markNeedsRefresh()

    fun clear()
}

internal class InstagramSessionStore(
    context: Context,
    private val cipher: InstagramSessionCipher = AndroidKeystoreInstagramSessionCipher(),
) : InstagramSessionRepository {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): InstagramSession? {
        val encodedIv = preferences.getString(KEY_IV, null) ?: return null
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        return try {
            val plaintext = cipher.decrypt(encodedIv, encodedCiphertext)
            InstagramSession.fromStoredJson(plaintext)
        } catch (_: Exception) {
            null
        }
    }

    override fun save(session: InstagramSession) {
        val encrypted = cipher.encrypt(session.toStoredJson())
        check(
            preferences.edit()
                .putString(KEY_IV, encrypted.iv)
                .putString(KEY_CIPHERTEXT, encrypted.ciphertext)
                .commit(),
        ) { "Instagram Session 保存失败" }
    }

    override fun markNeedsRefresh() {
        val current = read() ?: return
        if (current.status == InstagramSessionStatus.READY) save(current.needsRefresh())
    }

    override fun clear() {
        check(preferences.edit().clear().commit()) { "Instagram Session 清除失败" }
        cipher.deleteKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "photobook_instagram_session"
        private const val KEY_IV = "session_iv"
        private const val KEY_CIPHERTEXT = "session_ciphertext"
    }
}

internal data class EncryptedInstagramSession(val iv: String, val ciphertext: String)

internal interface InstagramSessionCipher {
    fun encrypt(plaintext: String): EncryptedInstagramSession

    fun decrypt(encodedIv: String, encodedCiphertext: String): String

    fun deleteKey()
}

private class AndroidKeystoreInstagramSessionCipher : InstagramSessionCipher {
    override fun encrypt(plaintext: String): EncryptedInstagramSession {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedInstagramSession(
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        )
    }

    override fun decrypt(encodedIv: String, encodedCiphertext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
            .toString(Charsets.UTF_8)
    }

    override fun deleteKey() {
        synchronized(AndroidKeystoreInstagramSessionCipher::class.java) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun secretKey(): SecretKey {
        synchronized(AndroidKeystoreInstagramSessionCipher::class.java) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        }
    }

    companion object {
        private const val KEY_ALIAS = "photobook_instagram_session_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
