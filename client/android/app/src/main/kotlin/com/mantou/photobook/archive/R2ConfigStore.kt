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

class R2ConfigStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): R2Config? {
        val encodedIv = preferences.getString(KEY_IV, null) ?: return null
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
            R2Config.fromJson(plaintext.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            null
        }
    }

    fun save(config: R2Config) {
        val encrypted = encrypt(config)
        check(
            preferences.edit()
                .putString(KEY_IV, encrypted.iv)
                .putString(KEY_CIPHERTEXT, encrypted.ciphertext)
                .commit(),
        ) { "R2 配置保存失败" }
    }

    private fun encrypt(config: R2Config): EncryptedConfig {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(config.toJson().toByteArray(Charsets.UTF_8))
        return EncryptedConfig(
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        )
    }

    fun clear() {
        check(
            preferences.edit()
                .remove(KEY_IV)
                .remove(KEY_CIPHERTEXT)
                .commit(),
        ) { "R2 配置清除失败" }
    }

    private fun secretKey(): SecretKey {
        synchronized(R2ConfigStore::class.java) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "photobook_secure"
        private const val KEY_IV = "r2_config_iv"
        private const val KEY_CIPHERTEXT = "r2_config_ciphertext"
        private const val KEY_ALIAS = "photobook_r2_config"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private data class EncryptedConfig(val iv: String, val ciphertext: String)
}
