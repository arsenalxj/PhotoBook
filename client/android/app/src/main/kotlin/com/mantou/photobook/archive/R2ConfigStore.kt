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
import org.json.JSONObject

internal data class EncryptedR2Settings(val iv: String, val ciphertext: String)

internal interface R2ConfigCipher {
    fun encrypt(plaintext: String): EncryptedR2Settings

    fun decrypt(encodedIv: String, encodedCiphertext: String): String
}

internal class R2ConfigStore(
    context: Context,
    private val cipher: R2ConfigCipher = AndroidKeystoreR2ConfigCipher(),
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): R2Settings =
        synchronized(R2ConfigStore::class.java) {
            val raw = decryptLocked() ?: return@synchronized R2Settings.EMPTY
            val settings = parseSettings(raw)
            if (!runCatching { JSONObject(raw).has("schemaVersion") }.getOrDefault(false)) {
                saveLocked(settings)
            }
            settings
        }

    fun saveConnectionWithTarget(
        connection: R2Connection,
        targetName: String,
        prefix: String,
    ): R2Settings =
        update { current ->
            require(current.connection(connection.connectionId) == null) {
                "R2 连接已存在，请在已有连接中添加位置"
            }
            val target = R2BackupTarget.create(connection, targetName, prefix)
            R2Settings(current.connections + connection, current.targets + target)
        }

    fun updateConnection(connection: R2Connection): R2Settings =
        update { current ->
            require(current.connection(connection.connectionId) != null) { "R2 连接不存在" }
            R2Settings(
                current.connections.map { existing ->
                    if (existing.connectionId == connection.connectionId) connection else existing
                },
                current.targets,
            )
        }

    fun saveTarget(
        connectionId: String,
        name: String,
        prefix: String,
        previousTargetId: String? = null,
    ): R2Settings =
        update { current ->
            val connection = current.connection(connectionId)
                ?: throw IllegalArgumentException("R2 连接不存在")
            val target = R2BackupTarget.create(connection, name, prefix)
            if (previousTargetId == null) {
                require(current.target(target.targetId) == null) { "R2 备份位置已存在" }
                return@update R2Settings(current.connections, current.targets + target)
            }
            val previous = current.target(previousTargetId)
                ?: throw IllegalArgumentException("R2 备份位置不存在")
            require(previous.connectionId == connectionId) { "R2 备份位置所属连接无效" }
            require(target.targetId == previous.targetId) {
                "Prefix 不能直接修改，请新增备份位置"
            }
            R2Settings(
                current.connections,
                current.targets.map { existing ->
                    if (existing.targetId == previousTargetId) target else existing
                },
            )
        }

    fun deleteTarget(targetId: String): R2Settings =
        update { current ->
            require(current.target(targetId) != null) { "R2 备份位置不存在" }
            R2Settings(current.connections, current.targets.filterNot { it.targetId == targetId })
        }

    fun deleteConnection(connectionId: String): R2Settings =
        update { current ->
            require(current.connection(connectionId) != null) { "R2 连接不存在" }
            R2Settings(
                current.connections.filterNot { it.connectionId == connectionId },
                current.targets.filterNot { it.connectionId == connectionId },
            )
        }

    private fun update(transform: (R2Settings) -> R2Settings): R2Settings =
        synchronized(R2ConfigStore::class.java) {
            val current = decryptLocked()?.let(::parseSettings) ?: R2Settings.EMPTY
            val updated = transform(current)
            saveLocked(updated)
            updated
        }

    private fun decryptLocked(): String? {
        val encodedIv = preferences.getString(KEY_IV, null)
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null)
        if (encodedIv == null && encodedCiphertext == null) return null
        if (encodedIv.isNullOrBlank() || encodedCiphertext.isNullOrBlank()) {
            throw ArchiveException("R2_CONFIG_CORRUPTED", "R2 配置已损坏，请重新配置")
        }
        return try {
            cipher.decrypt(encodedIv, encodedCiphertext)
        } catch (error: Exception) {
            throw ArchiveException("R2_CONFIG_UNREADABLE", "R2 配置无法解密，请重新配置", error)
        }
    }

    private fun parseSettings(raw: String): R2Settings =
        try {
            R2Settings.fromJson(raw)
        } catch (error: Exception) {
            throw ArchiveException("R2_CONFIG_CORRUPTED", "R2 配置已损坏，请重新配置", error)
        }

    private fun saveLocked(settings: R2Settings) {
        val encrypted = cipher.encrypt(settings.toJson())
        check(
            preferences.edit()
                .putString(KEY_IV, encrypted.iv)
                .putString(KEY_CIPHERTEXT, encrypted.ciphertext)
                .commit(),
        ) { "R2 配置保存失败" }
    }

    companion object {
        private const val PREFERENCES_NAME = "photobook_secure"
        private const val KEY_IV = "r2_config_iv"
        private const val KEY_CIPHERTEXT = "r2_config_ciphertext"
    }
}

private class AndroidKeystoreR2ConfigCipher : R2ConfigCipher {
    override fun encrypt(plaintext: String): EncryptedR2Settings {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedR2Settings(
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

    private fun secretKey(): SecretKey {
        synchronized(AndroidKeystoreR2ConfigCipher::class.java) {
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
        private const val KEY_ALIAS = "photobook_r2_config"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
