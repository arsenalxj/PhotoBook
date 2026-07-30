package com.mantou.photobook.archive

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object ArchiveEventBus : EventChannel.StreamHandler {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var sink: EventChannel.EventSink? = null

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    fun emitArchiveChanged() {
        emit("archiveChanged")
    }

    fun emitJobChanged() {
        emit("jobChanged")
    }

    fun emitRunStarted() {
        emit("runStarted")
    }

    fun emitRunFinished(error: String?) {
        emit("runFinished", error)
    }

    private fun emit(type: String, error: String? = null) {
        mainHandler.post {
            val event =
                mutableMapOf<String, Any>(
                    "type" to type,
                    "timestamp" to System.currentTimeMillis(),
                )
            if (error != null) event["error"] = error.take(300)
            sink?.success(event)
        }
    }
}
