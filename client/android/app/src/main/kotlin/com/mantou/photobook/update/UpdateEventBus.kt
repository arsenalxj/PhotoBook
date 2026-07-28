package com.mantou.photobook.update

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

internal object UpdateEventBus : EventChannel.StreamHandler {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var sink: EventChannel.EventSink? = null

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    fun emitDownloadProgress(receivedBytes: Long, totalBytes: Long) {
        emit(
            mapOf(
                "type" to "downloadProgress",
                "receivedBytes" to receivedBytes,
                "totalBytes" to totalBytes,
            ),
        )
    }

    fun emitInstallFailed(message: String) {
        emit(mapOf("type" to "installFailed", "message" to message))
    }

    private fun emit(event: Map<String, Any>) {
        mainHandler.post { sink?.success(event) }
    }
}
