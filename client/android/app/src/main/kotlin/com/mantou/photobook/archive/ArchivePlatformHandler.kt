package com.mantou.photobook.archive

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.app.ActivityCompat
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec
import java.util.concurrent.Executors

class ArchivePlatformHandler(
    private val activity: Activity,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {
    private val applicationContext = activity.applicationContext
    private val database = ArchiveDatabase(applicationContext)
    private val configStore = R2ConfigStore(applicationContext)
    private val instagram = InstagramClient(applicationContext)
    private val mediaActions = ArchiveMediaActions(activity, database)
    private val permissionExecutor = Executors.newSingleThreadExecutor()
    private val sessionExecutor = Executors.newSingleThreadExecutor()
    private var pendingLegacySave: PendingLegacySave? = null
    private val channel =
        MethodChannel(
            messenger,
            METHOD_CHANNEL,
            StandardMethodCodec.INSTANCE,
            messenger.makeBackgroundTaskQueue(),
        )

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "getRuntimeState" -> result.success(runtimeState())
                "retryJob" -> retryJob(call, result)
                "beginInstagramLogin", "cancelInstagramLogin" -> clearInstagramWebData(result)
                "captureInstagramSession" -> captureInstagramSession(result)
                "clearInstagramSession" -> clearInstagramSession(result)
                "saveR2Config" -> saveR2Config(call, result)
                "clearR2Config" -> {
                    configStore.clear()
                    database.clearSyncResult()
                    ArchiveRecoveryScheduler.scheduleIfNeeded(
                        applicationContext,
                        database,
                        null,
                    )
                    result.success(null)
                }
                "ensureOriginal" -> ensureOriginal(call, result)
                "deletePost" -> deletePost(call, result)
                "deleteMedia" -> deleteMedia(call, result)
                "shareMedia" -> shareMedia(call, result)
                "saveMedia" -> saveMedia(call, result)
                "syncNow" -> {
                    ArchiveForegroundService.start(applicationContext)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (error: Exception) {
            reportError(result, error)
        }
    }

    fun close() {
        channel.setMethodCallHandler(null)
        synchronized(this) {
            pendingLegacySave?.result?.error("ACTIVITY_CLOSED", "页面已关闭，请重新操作", null)
            pendingLegacySave = null
        }
        permissionExecutor.shutdownNow()
        sessionExecutor.shutdownNow()
        database.close()
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != LEGACY_STORAGE_PERMISSION_REQUEST) return false
        val pending = synchronized(this) {
            pendingLegacySave.also { pendingLegacySave = null }
        } ?: return true
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pending.result.error("STORAGE_PERMISSION_DENIED", "未获得存储权限，无法保存到系统相册", null)
            return true
        }
        permissionExecutor.execute {
            try {
                pending.result.success(saveMediaNow(pending.mediaId))
            } catch (error: Exception) {
                reportError(pending.result, error)
            }
        }
        return true
    }

    private fun runtimeState(): Map<String, Any?> {
        val config = configStore.read()
        return mapOf(
            "activeJobCount" to database.activeJobCount(),
            "failedJobCount" to database.failedJobCount(),
            "instagramSession" to instagram.sessionSummary(),
            "r2Config" to config?.summary(),
        )
    }

    private fun captureInstagramSession(result: MethodChannel.Result) {
        activity.runOnUiThread {
            try {
                val cookieHeader =
                    CookieManager.getInstance().getCookie(INSTAGRAM_URL).orEmpty().trim()
                if (cookieHeader.isEmpty()) {
                    result.error("LOGIN_INCOMPLETE", "Instagram 登录尚未完成，请继续登录", null)
                    return@runOnUiThread
                }
                validateInstagramSession(cookieHeader, result)
            } catch (error: Exception) {
                reportError(result, error)
            }
        }
    }

    private fun validateInstagramSession(
        cookieHeader: String,
        result: MethodChannel.Result,
    ) {
        sessionExecutor.execute {
            var acquired = false
            val summary =
                try {
                    ArchiveExecutionGate.acquire()
                    acquired = true
                    instagram.validateAndSaveSession(cookieHeader).summary()
                } catch (error: Exception) {
                    reportError(result, error)
                    return@execute
                } finally {
                    if (acquired) ArchiveExecutionGate.release()
                }
            clearInstagramWebData(result, summary)
        }
    }

    private fun clearInstagramSession(result: MethodChannel.Result) {
        var acquired = false
        try {
            ArchiveExecutionGate.acquire()
            acquired = true
            instagram.clearSession()
        } finally {
            if (acquired) ArchiveExecutionGate.release()
        }
        clearInstagramWebData(result)
    }

    private fun clearInstagramWebData(
        result: MethodChannel.Result,
        successValue: Any? = null,
    ) {
        activity.runOnUiThread {
            try {
                WebStorage.getInstance().deleteAllData()
                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                cookies.removeAllCookies {
                    sessionExecutor.execute {
                        try {
                            cookies.flush()
                            result.success(successValue)
                        } catch (error: Exception) {
                            reportError(result, error)
                        }
                    }
                }
            } catch (error: Exception) {
                reportError(result, error)
            }
        }
    }

    private fun retryJob(call: MethodCall, result: MethodChannel.Result) {
        val jobId = call.argument<String>("jobId").orEmpty()
        if (jobId.isBlank() || !database.retryJob(jobId)) {
            result.error("JOB_NOT_FOUND", "失败任务不存在或已被处理", null)
            return
        }
        ArchiveForegroundService.start(applicationContext)
        result.success(null)
    }

    private fun saveR2Config(call: MethodCall, result: MethodChannel.Result) {
        val config = R2Config.fromMap(call.arguments as? Map<*, *> ?: emptyMap<Any, Any>())
        R2ObjectStore(config).testConnection()
        database.seedRepositoryIfNeeded(
            config.repositoryId,
            DeviceIdentity(applicationContext).getOrCreate(),
        )
        configStore.save(config)
        ArchiveForegroundService.start(applicationContext)
        result.success(config.summary())
    }

    private fun ensureOriginal(call: MethodCall, result: MethodChannel.Result) {
        val mediaId = call.argument<String>("mediaId").orEmpty()
        if (mediaId.isBlank()) {
            result.error("MEDIA_NOT_FOUND", "媒体记录不存在", null)
            return
        }
        ArchiveExecutionGate.acquire()
        val path =
            try {
                R2SyncEngine(applicationContext, database).ensureOriginal(mediaId).absolutePath
            } finally {
                ArchiveExecutionGate.release()
            }
        result.success(path)
    }

    private fun deletePost(call: MethodCall, result: MethodChannel.Result) {
        val postId = call.argument<String>("postId").orEmpty()
        if (postId.isBlank()) throw ArchiveException("POST_NOT_FOUND", "帖子不存在")
        ArchiveExecutionGate.acquire()
        try {
            database.deletePost(postId, DeviceIdentity(applicationContext).getOrCreate())
        } finally {
            ArchiveExecutionGate.release()
        }
        afterArchiveMutation()
        result.success(null)
    }

    private fun deleteMedia(call: MethodCall, result: MethodChannel.Result) {
        val mediaId = call.argument<String>("mediaId").orEmpty()
        if (mediaId.isBlank()) throw ArchiveException("MEDIA_NOT_FOUND", "媒体不存在")
        ArchiveExecutionGate.acquire()
        val deleted =
            try {
                database.deleteMedia(mediaId, DeviceIdentity(applicationContext).getOrCreate())
            } finally {
                ArchiveExecutionGate.release()
            }
        if (!deleted.postDeleteRequired) afterArchiveMutation()
        result.success(
            mapOf(
                "postId" to deleted.postId,
                "postDeleteRequired" to deleted.postDeleteRequired,
            ),
        )
    }

    private fun shareMedia(call: MethodCall, result: MethodChannel.Result) {
        val mediaIds = call.argument<List<String>>("mediaIds").orEmpty()
        ArchiveExecutionGate.acquire()
        try {
            mediaActions.share(mediaIds) { error ->
                if (error == null) result.success(null) else reportError(result, error)
            }
        } finally {
            ArchiveExecutionGate.release()
        }
    }

    private fun saveMedia(call: MethodCall, result: MethodChannel.Result) {
        val mediaId = call.argument<String>("mediaId").orEmpty()
        if (mediaId.isBlank()) throw ArchiveException("MEDIA_NOT_FOUND", "媒体不存在")
        if (ArchiveMediaActions.requiresLegacyStoragePermission() &&
            ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            synchronized(this) {
                if (pendingLegacySave != null) {
                    throw ArchiveException("MEDIA_ACTION_BUSY", "已有媒体保存操作正在等待授权")
                }
                pendingLegacySave = PendingLegacySave(mediaId, result)
            }
            activity.runOnUiThread {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    LEGACY_STORAGE_PERMISSION_REQUEST,
                )
            }
            return
        }
        result.success(saveMediaNow(mediaId))
    }

    private fun saveMediaNow(mediaId: String): String {
        ArchiveExecutionGate.acquire()
        return try {
            mediaActions.save(mediaId)
        } finally {
            ArchiveExecutionGate.release()
        }
    }

    private fun afterArchiveMutation() {
        ArchiveEventBus.emitArchiveChanged()
        try {
            if (configStore.read() != null) ArchiveForegroundService.start(applicationContext)
        } catch (error: Exception) {
            Log.w(TAG, "本地删除已提交，但启动 R2 同步服务失败", error)
        }
    }

    private fun reportError(result: MethodChannel.Result, error: Exception) {
        if (error is ArchiveException) {
            result.error(error.code, error.message, null)
        } else {
            Log.e(TAG, sanitizedStackTrace(error))
            result.error("ARCHIVE_ERROR", error.message ?: "本地归档操作失败", null)
        }
    }

    private fun sanitizedStackTrace(error: Throwable): String =
        buildString {
            append("原生归档操作失败: ")
            generateSequence(error) { it.cause }
                .take(8)
                .forEachIndexed { index, cause ->
                    if (index > 0) append("\nCaused by: ")
                    append(cause.javaClass.name)
                    cause.stackTrace.take(32).forEach { frame ->
                        append("\n  at ")
                        append(frame.className)
                        append('.')
                        append(frame.methodName)
                        append('(')
                        append(frame.fileName ?: "Unknown Source")
                        if (frame.lineNumber >= 0) {
                            append(':')
                            append(frame.lineNumber)
                        }
                        append(')')
                    }
                }
        }

    private data class PendingLegacySave(
        val mediaId: String,
        val result: MethodChannel.Result,
    )

    companion object {
        const val METHOD_CHANNEL = "com.mantou.photobook/archive"
        const val EVENT_CHANNEL = "com.mantou.photobook/archive_events"
        private const val INSTAGRAM_URL = "https://www.instagram.com/"
        private const val LEGACY_STORAGE_PERMISSION_REQUEST = 102
        private const val TAG = "ArchivePlatform"
    }
}
