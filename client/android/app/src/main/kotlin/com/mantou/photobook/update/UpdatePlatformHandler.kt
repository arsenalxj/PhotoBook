package com.mantou.photobook.update

import android.app.Activity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class UpdatePlatformHandler(
    private val activity: Activity,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {
    private val downloader = UpdateDownloader(activity.applicationContext)
    private val installer = UpdateInstaller(activity)
    private val executor = Executors.newSingleThreadExecutor()
    private val channel = MethodChannel(messenger, METHOD_CHANNEL)

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getInstalledApp" -> runCatching(result) { installer.installedApp() }
            "downloadUpdate" -> {
                val spec = try {
                    UpdateDownloadSpec.fromMap(call.arguments as? Map<*, *> ?: emptyMap<Any, Any>())
                } catch (error: Exception) {
                    reportError(result, error)
                    return
                }
                executor.execute {
                    runCatching(result) { downloader.download(spec).absolutePath }
                }
            }
            "cancelUpdate" -> {
                downloader.cancel()
                result.success(null)
            }
            "installUpdate" -> {
                val spec = try {
                    UpdateInstallSpec.fromMap(call.arguments as? Map<*, *> ?: emptyMap<Any, Any>())
                } catch (error: Exception) {
                    reportError(result, error)
                    return
                }
                executor.execute {
                    runCatching(result) { installer.install(spec).wireValue }
                }
            }
            else -> result.notImplemented()
        }
    }

    fun close() {
        downloader.cancel()
        channel.setMethodCallHandler(null)
        executor.shutdownNow()
    }

    private fun <T> runCatching(result: MethodChannel.Result, action: () -> T) {
        try {
            result.success(action())
        } catch (error: Exception) {
            reportError(result, error)
        }
    }

    private fun reportError(result: MethodChannel.Result, error: Exception) {
        if (error is UpdateException) {
            result.error(error.code, error.message, null)
        } else {
            result.error("UPDATE_ERROR", error.message ?: "更新操作失败", null)
        }
    }

    companion object {
        const val METHOD_CHANNEL = "com.mantou.photobook/update"
        const val EVENT_CHANNEL = "com.mantou.photobook/update_events"
    }
}
