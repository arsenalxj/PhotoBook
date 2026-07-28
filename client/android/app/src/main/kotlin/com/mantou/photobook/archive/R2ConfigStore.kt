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

    fun read(): R2Config? = readEncrypted(KEY_IV, KEY_CIPHERTEXT)

    fun readMigrationSource(): R2Config? =
        readEncrypted(KEY_MIGRATION_IV, KEY_MIGRATION_CIPHERTEXT)

    private fun readEncrypted(ivKey: String, ciphertextKey: String): R2Config? {
        val encodedIv = preferences.getString(ivKey, null) ?: return null
        val encodedCiphertext = preferences.getString(ciphertextKey, null) ?: return null
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
        val previous = read()
        val encrypted = encrypt(config)
        val editor =
            preferences.edit()
                .putString(KEY_IV, encrypted.iv)
                .putString(KEY_CIPHERTEXT, encrypted.ciphertext)
        if (previous != null && previous.repositoryId != config.repositoryId) {
            val migrationSource = encrypt(previous)
            editor
                .putString(KEY_MIGRATION_IV, migrationSource.iv)
                .putString(KEY_MIGRATION_CIPHERTEXT, migrationSource.ciphertext)
        }
        check(editor.commit()) { "R2 配置保存失败" }
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
        preferences.edit()
            .remove(KEY_IV)
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_MIGRATION_IV)
            .remove(KEY_MIGRATION_CIPHERTEXT)
            .apply()
    }

    fun clearMigrationSource() {
        preferences.edit()
            .remove(KEY_MIGRATION_IV)
            .remove(KEY_MIGRATION_CIPHERTEXT)
            .apply()
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
        private const val KEY_MIGRATION_IV = "r2_migration_config_iv"
        private const val KEY_MIGRATION_CIPHERTEXT = "r2_migration_config_ciphertext"
        private const val KEY_ALIAS = "photobook_r2_config"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private data class EncryptedConfig(val iv: String, val ciphertext: String)
}
