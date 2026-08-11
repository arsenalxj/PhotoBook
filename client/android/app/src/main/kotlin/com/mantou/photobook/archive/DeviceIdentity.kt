package com.mantou.photobook.archive

import android.content.Context
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("photobook_runtime", Context.MODE_PRIVATE)

    fun getOrCreate(): String = getOrCreateInfo().deviceId

    fun getOrCreateInfo(): DeviceInfo {
        val existingId = preferences.getString(KEY_DEVICE_ID, null)
        val existingCreatedAt = preferences.getLong(KEY_CREATED_AT, 0)
        if (existingId != null && existingCreatedAt > 0) {
            return DeviceInfo(existingId, existingCreatedAt)
        }
        val deviceId = existingId ?: UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        check(
            preferences.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putLong(KEY_CREATED_AT, createdAt)
                .commit(),
        ) {
            "无法保存设备标识"
        }
        return DeviceInfo(deviceId, createdAt)
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CREATED_AT = "device_created_at"
    }
}

data class DeviceInfo(
    val deviceId: String,
    val createdAt: Long,
)
