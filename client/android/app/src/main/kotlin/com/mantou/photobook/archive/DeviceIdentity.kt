package com.mantou.photobook.archive

import android.content.Context
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("photobook_runtime", Context.MODE_PRIVATE)

    fun getOrCreate(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        val value = UUID.randomUUID().toString()
        check(preferences.edit().putString(KEY_DEVICE_ID, value).commit()) {
            "无法保存设备标识"
        }
        return value
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
    }
}
