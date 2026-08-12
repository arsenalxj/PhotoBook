package com.mantou.photobook.archive

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.app.ActivityCompat
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.security.MessageDigest

internal class ArchivePlatformHandler(
    private val activity: Activity,
    messenger: BinaryMessenger,
    private val automaticClipboardImportGate: AutomaticClipboardImportGate,
) : MethodChannel.MethodCallHandler {
    private val applicationContext = activity.applicationContext
    private val database = ArchiveDatabase(applicationContext)
    private val configStore = R2ConfigStore(applicationContext)
    private val instagram = InstagramClient(applicationContext)
    private val instagramCookieClipboard = InstagramCookieClipboard(applicationContext)
    private val mediaActions = ArchiveMediaActions(activity, database)
    private val permissionExecutor = Executors.newSingleThreadExecutor()
    private val sessionExecutor = Executors.newSingleThreadExecutor()
    private val instagramLoginAttempts = InstagramLoginAttemptCoordinator()
    private val clipboardPreferences =
        applicationContext.getSharedPreferences(CLIPBOARD_PREFERENCES, Context.MODE_PRIVATE)
    private val webDataCleanupLock = Any()
    private var webDataCleanupTail = CompletableFuture.completedFuture(Unit)
    private var pendingLegacySave: PendingLegacySave? = null
    private val channel =
        MethodChannel(
            messenger,
            METHOD_CHANNEL,
            StandardMethodCodec.INSTANCE,
            messenger.makeBackgroundTaskQueue(),
        )

    init {
        if (database.migrateToManualBackupMode()) {
            ArchiveRecoveryScheduler.scheduleBackupIfNeeded(applicationContext, null)
        }
        scheduleExistingManualBackups()
        channel.setMethodCallHandler(this)
        // 进程被杀时无法执行退出清理，下次创建原生通道时先清掉遗留的 WebView 数据。
        clearInstagramWebData().whenComplete { _, error ->
            if (error != null) {
                Log.w(TAG, "冷启动 Instagram WebView 数据清理失败：${asException(error).javaClass.name}")
            }
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "getRuntimeState" -> result.success(runtimeState())
                "importClipboard" -> importClipboard(call, result)
                "retryJob" -> retryJob(call, result)
                "cancelJob" -> cancelJob(call, result)
                "deleteJob" -> deleteJob(call, result)
                "copyJobSourceUrl" -> copyJobSourceUrl(call, result)
                "beginInstagramLogin" -> beginInstagramLogin(result)
                "cancelInstagramLogin" -> cancelInstagramLogin(result)
                "captureInstagramSession" -> captureInstagramSession(result)
                "importInstagramCookies" -> importInstagramCookies(call, result)
                "copyInstagramCookies" -> copyInstagramCookies(result)
                "clearInstagramSession" -> clearInstagramSession(result)
                "saveR2Connection" -> saveR2Connection(call, result)
                "updateR2Connection" -> updateR2Connection(call, result)
                "saveR2Target" -> saveR2Target(call, result)
                "deleteR2Target" -> deleteR2Target(call, result)
                "deleteR2Connection" -> deleteR2Connection(call, result)
                "enqueueR2Backup" -> enqueueR2Backup(call, result)
                "resumeBackupJobs" -> {
                    scheduleExistingManualBackups()
                    result.success(null)
                }
                "resumeCaptureJobs" -> {
                    ArchiveForegroundService.start(applicationContext)
                    result.success(null)
                }
                "ensureOriginal" -> ensureOriginal(call, result)
                "deletePost" -> deletePost(call, result)
                "deleteMediaSelection" -> deleteMediaSelection(call, result)
                "shareMedia" -> shareMedia(call, result)
                "saveMedia" -> saveMedia(call, result)
                else -> result.notImplemented()
            }
        } catch (error: Exception) {
            reportError(result, error)
        }
    }

    fun close() {
        channel.setMethodCallHandler(null)
        instagramLoginAttempts.cancel()
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
                pending.result.success(saveMediaNow(pending.mediaId, pending.exportMode))
            } catch (error: Exception) {
                reportError(pending.result, error)
            }
        }
        return true
    }

    private fun runtimeState(): Map<String, Any?> {
        val settings = configStore.read()
        return mapOf(
            "activeJobCount" to database.activeJobCount(),
            "failedJobCount" to database.failedJobCount(),
            "instagramSession" to instagram.sessionSummary(),
            "r2Settings" to settings.summary(),
        )
    }

    private fun importClipboard(call: MethodCall, result: MethodChannel.Result) {
        val automatic = call.argument<Boolean>("automatic") == true
        if (automatic && automaticClipboardImportGate.consumeSkip()) {
            result.success(CLIPBOARD_SKIPPED)
            return
        }
        val clipboard =
            applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val primaryClip =
            runCatching {
                clipboard.primaryClip
            }.getOrElse {
                result.success(CLIPBOARD_UNAVAILABLE)
                return
            }
        if (automatic &&
            primaryClip != null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !isRecentClipboardTimestamp(
                    primaryClip.description.timestamp,
                    System.currentTimeMillis(),
                ))
        ) {
            result.success(CLIPBOARD_STALE)
            return
        }
        val sharedText =
            primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
                .orEmpty()
        if (sharedText.isBlank()) {
            result.success(CLIPBOARD_EMPTY)
            return
        }
        val request = ArchiveLinkImporter.parse(sharedText)
        if (request == null) {
            result.success(CLIPBOARD_UNSUPPORTED)
            return
        }
        val fingerprint = clipboardFingerprint(request)
        if (automatic &&
            clipboardPreferences.getString(KEY_LAST_CLIPBOARD_FINGERPRINT, null) == fingerprint
        ) {
            result.success(CLIPBOARD_ALREADY_PROCESSED)
            return
        }
        val job = ArchiveLinkImporter.enqueue(database, request)
        clipboardPreferences.edit().putString(KEY_LAST_CLIPBOARD_FINGERPRINT, fingerprint).apply()
        ArchiveEventBus.emitJobChanged()
        if (job.status != "completed") ArchiveForegroundService.start(applicationContext)
        result.success(if (job.status == "completed") CLIPBOARD_COMPLETED else CLIPBOARD_QUEUED)
    }

    private fun clipboardFingerprint(request: ArchiveImportRequest): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${request.sourcePlatform}\n${request.requestKey}".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun captureInstagramSession(result: MethodChannel.Result) {
        activity.runOnUiThread {
            try {
                val attemptId = instagramLoginAttempts.currentAttempt()
                if (attemptId == null) {
                    reportLoginCancelled(result)
                    return@runOnUiThread
                }
                val cookieHeader =
                    CookieManager.getInstance().getCookie(INSTAGRAM_URL).orEmpty().trim()
                if (cookieHeader.isEmpty()) {
                    result.error("LOGIN_INCOMPLETE", "Instagram 登录尚未完成，请继续登录", null)
                    return@runOnUiThread
                }
                validateInstagramSession(cookieHeader, attemptId, result)
            } catch (error: Exception) {
                reportError(result, error)
            }
        }
    }

    private fun validateInstagramSession(
        cookieHeader: String,
        attemptId: Long,
        result: MethodChannel.Result,
    ) {
        sessionExecutor.execute {
            var acquired = false
            val validated =
                try {
                    ArchiveExecutionGate.acquire()
                    acquired = true
                    instagram.validateSession(cookieHeader)
                } catch (error: Exception) {
                    if (instagramLoginAttempts.isActive(attemptId)) {
                        reportError(result, error)
                    } else {
                        reportLoginCancelled(result)
                    }
                    return@execute
                } finally {
                    if (acquired) ArchiveExecutionGate.release()
                }
            if (!instagramLoginAttempts.isActive(attemptId)) {
                reportLoginCancelled(result)
                return@execute
            }
            clearInstagramWebData().whenComplete { _, cleanupError ->
                if (cleanupError != null) {
                    instagramLoginAttempts.cancelIfActive(attemptId)
                    reportError(result, asException(cleanupError))
                    return@whenComplete
                }
                try {
                    sessionExecutor.execute {
                        saveValidatedInstagramSession(validated, attemptId, result)
                    }
                } catch (error: Exception) {
                    instagramLoginAttempts.cancelIfActive(attemptId)
                    reportError(result, error)
                }
            }
        }
    }

    private fun saveValidatedInstagramSession(
        session: InstagramSession,
        attemptId: Long,
        result: MethodChannel.Result,
    ) {
        var acquired = false
        try {
            ArchiveExecutionGate.acquire()
            acquired = true
            val committed =
                instagramLoginAttempts.commitIfActive(attemptId) {
                    instagram.saveSession(session)
                }
            if (!committed) {
                reportLoginCancelled(result)
                return
            }
            result.success(session.summary())
        } catch (error: Exception) {
            instagramLoginAttempts.cancelIfActive(attemptId)
            reportError(result, error)
        } finally {
            if (acquired) ArchiveExecutionGate.release()
        }
    }

    private fun beginInstagramLogin(result: MethodChannel.Result) {
        val attemptId = instagramLoginAttempts.begin()
        clearInstagramWebData().whenComplete { _, error ->
            if (error != null) {
                instagramLoginAttempts.cancelIfActive(attemptId)
                reportError(result, asException(error))
            } else if (instagramLoginAttempts.isActive(attemptId)) {
                result.success(null)
            } else {
                reportLoginCancelled(result)
            }
        }
    }

    private fun cancelInstagramLogin(result: MethodChannel.Result) {
        instagramLoginAttempts.cancel()
        completeInstagramWebDataCleanup(result)
    }

    private fun copyInstagramCookies(result: MethodChannel.Result) {
        instagramCookieClipboard.copy(instagram.copyableCookieHeader())
        result.success(null)
    }

    private fun importInstagramCookies(call: MethodCall, result: MethodChannel.Result) {
        val cookieHeader = call.argument<String>("cookieHeader").orEmpty().trim()
        if (cookieHeader.isEmpty()) {
            result.error("LOGIN_INCOMPLETE", "请先粘贴完整 Instagram Cookie", null)
            return
        }
        if (cookieHeader.length > MAX_COOKIE_HEADER_LENGTH) {
            result.error("LOGIN_INCOMPLETE", "Instagram Cookie 格式无效", null)
            return
        }
        sessionExecutor.execute {
            var acquired = false
            try {
                ArchiveExecutionGate.acquire()
                acquired = true
                val session = instagram.validateAndSaveSession(cookieHeader)
                result.success(session.summary())
            } catch (error: Exception) {
                reportError(result, error)
            } finally {
                if (acquired) ArchiveExecutionGate.release()
            }
        }
    }

    private fun clearInstagramSession(result: MethodChannel.Result) {
        instagramLoginAttempts.cancel()
        var acquired = false
        try {
            ArchiveExecutionGate.acquire()
            acquired = true
            instagram.clearSession()
        } finally {
            if (acquired) ArchiveExecutionGate.release()
        }
        completeInstagramWebDataCleanup(result)
    }

    private fun completeInstagramWebDataCleanup(
        result: MethodChannel.Result,
        successValue: Any? = null,
    ) {
        clearInstagramWebData().whenComplete { _, error ->
            if (error == null) {
                result.success(successValue)
            } else {
                reportError(result, asException(error))
            }
        }
    }

    private fun clearInstagramWebData(): CompletableFuture<Unit> =
        synchronized(webDataCleanupLock) {
            // 旧验证和新登录可能交错，串行清理可避免旧任务擦掉新页面刚写入的 Cookie。
            val cleanup =
                webDataCleanupTail
                    .handle { _, _ -> Unit }
                    .thenCompose { clearInstagramWebDataOnce() }
            webDataCleanupTail = cleanup
            cleanup
        }

    private fun clearInstagramWebDataOnce(): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        try {
            activity.runOnUiThread {
                try {
                    WebStorage.getInstance().deleteAllData()
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)
                    cookies.removeAllCookies {
                        try {
                            sessionExecutor.execute {
                                try {
                                    cookies.flush()
                                    completion.complete(Unit)
                                } catch (error: Exception) {
                                    completion.completeExceptionally(error)
                                }
                            }
                        } catch (error: Exception) {
                            completion.completeExceptionally(error)
                        }
                    }
                } catch (error: Exception) {
                    completion.completeExceptionally(error)
                }
            }
        } catch (error: Exception) {
            completion.completeExceptionally(error)
        }
        return completion
    }

    private fun retryJob(call: MethodCall, result: MethodChannel.Result) {
        val jobId = call.argument<String>("jobId").orEmpty()
        if (jobId.isBlank() || !database.retryJob(jobId)) {
            result.error("JOB_NOT_FOUND", "失败任务不存在或已被处理", null)
            return
        }
        ArchiveEventBus.emitJobChanged()
        ArchiveForegroundService.start(applicationContext)
        result.success(null)
    }

    private fun cancelJob(call: MethodCall, result: MethodChannel.Result) {
        val jobId = call.argument<String>("jobId").orEmpty()
        val cancellation = jobId.takeIf(String::isNotBlank)?.let(database::cancelJob)
        if (cancellation == null) {
            result.error("JOB_NOT_FOUND", "活动任务不存在或已结束", null)
            return
        }
        if (cancellation == JobCancellationResult.QUEUED) {
            try {
                ArchiveRecoveryScheduler.scheduleCaptureIfNeeded(applicationContext, database)
            } catch (error: Exception) {
                Log.w(TAG, "任务已取消，但刷新抓取恢复调度失败", error)
            }
        }
        ArchiveEventBus.emitJobChanged()
        result.success(null)
    }

    private fun deleteJob(call: MethodCall, result: MethodChannel.Result) {
        val jobId = call.argument<String>("jobId").orEmpty()
        if (jobId.isBlank() || !database.deleteJob(jobId)) {
            result.error("JOB_NOT_FOUND", "失败任务不存在或已被处理", null)
            return
        }
        ArchiveEventBus.emitJobChanged()
        result.success(null)
    }

    private fun copyJobSourceUrl(call: MethodCall, result: MethodChannel.Result) {
        val jobId = call.argument<String>("jobId").orEmpty()
        if (jobId.isBlank()) {
            result.error("JOB_NOT_FOUND", "任务不存在或已被处理", null)
            return
        }
        val sourceUrl = database.jobSourceUrl(jobId)
        val clip = ClipData.newPlainText(ARCHIVE_LINK_CLIP_LABEL, sourceUrl)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras =
                PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
        }
        val clipboard =
            applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
        result.success(null)
    }

    private fun saveR2Connection(call: MethodCall, result: MethodChannel.Result) {
        val raw = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
        val connection = R2Connection.fromMap(raw)
        val targetName = raw["targetName"]?.toString().orEmpty()
        val prefix = raw["prefix"]?.toString().orEmpty()
        R2ObjectStore(connection.resolve(prefix)).testConnection()
        ArchiveExecutionGate.acquire()
        try {
            val settings = configStore.saveConnectionWithTarget(connection, targetName, prefix)
            result.success(settings.summary())
        } finally {
            ArchiveExecutionGate.release()
        }
    }

    private fun updateR2Connection(call: MethodCall, result: MethodChannel.Result) {
        val raw = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
        val expectedConnectionId = raw["connectionId"]?.toString().orEmpty()
        val connection = R2Connection.fromMap(raw)
        require(connection.connectionId == expectedConnectionId) { "R2 连接标识不匹配" }
        val settings = configStore.read()
        val target = settings.targets.firstOrNull { it.connectionId == connection.connectionId }
            ?: throw IllegalArgumentException("R2 连接没有可验证的备份位置")
        R2ObjectStore(connection.resolve(target.prefix)).testConnection()
        ArchiveExecutionGate.acquire()
        try {
            result.success(configStore.updateConnection(connection).summary())
        } finally {
            ArchiveExecutionGate.release()
        }
    }

    private fun saveR2Target(call: MethodCall, result: MethodChannel.Result) {
        val raw = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
        val connectionId = raw["connectionId"]?.toString().orEmpty()
        val name = raw["name"]?.toString().orEmpty()
        val prefix = raw["prefix"]?.toString().orEmpty()
        val previousTargetId = raw["previousTargetId"]?.toString()?.takeIf(String::isNotBlank)
        ArchiveExecutionGate.acquire()
        try {
            val settings = configStore.saveTarget(connectionId, name, prefix, previousTargetId)
            result.success(settings.summary())
        } finally {
            ArchiveExecutionGate.release()
        }
    }

    private fun deleteR2Target(call: MethodCall, result: MethodChannel.Result) {
        val targetId = call.argument<String>("targetId").orEmpty()
        require(targetId.isNotBlank()) { "R2 备份位置不存在" }
        ArchiveExecutionGate.acquire()
        try {
            val settings = configStore.deleteTarget(targetId)
            database.discardPendingBackupJobs(targetId)
            database.clearBackupResult()
            rescheduleManualBackups()
            result.success(settings.summary())
        } finally {
            ArchiveExecutionGate.release()
        }
    }

    private fun deleteR2Connection(call: MethodCall, result: MethodChannel.Result) {
        val connectionId = call.argument<String>("connectionId").orEmpty()
        require(connectionId.isNotBlank()) { "R2 连接不存在" }
        ArchiveExecutionGate.acquire()
        try {
            val current = configStore.read()
            val targetIds = current.targets.filter { it.connectionId == connectionId }.map { it.targetId }
            val settings = configStore.deleteConnection(connectionId)
            targetIds.forEach(database::discardPendingBackupJobs)
            database.clearBackupResult()
            rescheduleManualBackups()
            result.success(settings.summary())
        } finally {
            ArchiveExecutionGate.release()
        }
    }

    private fun enqueueR2Backup(call: MethodCall, result: MethodChannel.Result) {
        val postId = call.argument<String>("postId").orEmpty()
        val targetId = call.argument<String>("targetId").orEmpty()
        require(configStore.read().target(targetId) != null) { "R2 备份位置不存在" }
        val deviceId = DeviceIdentity(applicationContext).getOrCreate()
        ArchiveExecutionGate.acquire()
        val status =
            try {
                database.enqueueManualBackup(postId, targetId, deviceId)
            } finally {
                ArchiveExecutionGate.release()
            }
        if (status != ManualBackupEnqueueStatus.COMPLETED) {
            ArchiveRecoveryScheduler.scheduleBackupNow(applicationContext)
        }
        ArchiveEventBus.emitArchiveChanged()
        result.success(status.name.lowercase())
    }

    private fun rescheduleManualBackups() {
        val deviceId = DeviceIdentity(applicationContext).getOrCreate()
        if (database.hasPendingBackupJobs(deviceId)) {
            ArchiveRecoveryScheduler.scheduleBackupNow(applicationContext)
        } else {
            ArchiveRecoveryScheduler.scheduleBackupIfNeeded(applicationContext, null)
        }
    }

    private fun scheduleExistingManualBackups() {
        ArchiveRecoveryScheduler.scheduleExistingBackups(
            applicationContext,
            database,
            DeviceIdentity(applicationContext).getOrCreate(),
        )
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
                R2BackupEngine(applicationContext, database).ensureOriginal(mediaId).absolutePath
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
            database.deletePost(postId)
        } finally {
            ArchiveExecutionGate.release()
        }
        afterArchiveMutation()
        result.success(null)
    }

    private fun deleteMediaSelection(call: MethodCall, result: MethodChannel.Result) {
        val postId = call.argument<String>("postId").orEmpty()
        val mediaIds = call.argument<List<String>>("mediaIds").orEmpty()
        if (postId.isBlank()) throw ArchiveException("POST_NOT_FOUND", "帖子不存在")
        ArchiveExecutionGate.acquire()
        val deleted =
            try {
                database.deleteMediaSelection(
                    postId,
                    mediaIds,
                )
            } finally {
                ArchiveExecutionGate.release()
            }
        afterArchiveMutation()
        result.success(
            mapOf(
                "postId" to deleted.postId,
                "postDeleted" to deleted.postDeleted,
            ),
        )
    }

    private fun shareMedia(call: MethodCall, result: MethodChannel.Result) {
        val mediaIds = call.argument<List<String>>("mediaIds").orEmpty()
        val exportMode = call.argument<String>("exportMode") ?: ArchiveMediaActions.EXPORT_ORIGINAL
        ArchiveExecutionGate.acquire()
        try {
            mediaActions.share(mediaIds, exportMode) { error ->
                if (error == null) result.success(null) else reportError(result, error)
            }
        } finally {
            ArchiveExecutionGate.release()
        }
    }

    private fun saveMedia(call: MethodCall, result: MethodChannel.Result) {
        val mediaId = call.argument<String>("mediaId").orEmpty()
        val exportMode = call.argument<String>("exportMode") ?: ArchiveMediaActions.EXPORT_ORIGINAL
        if (mediaId.isBlank()) throw ArchiveException("MEDIA_NOT_FOUND", "媒体不存在")
        if (ArchiveMediaActions.requiresLegacyStoragePermission() &&
            ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            synchronized(this) {
                if (pendingLegacySave != null) {
                    throw ArchiveException("MEDIA_ACTION_BUSY", "已有媒体保存操作正在等待授权")
                }
                pendingLegacySave = PendingLegacySave(mediaId, exportMode, result)
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
        result.success(saveMediaNow(mediaId, exportMode))
    }

    private fun saveMediaNow(
        mediaId: String,
        exportMode: String = ArchiveMediaActions.EXPORT_ORIGINAL,
    ): String {
        ArchiveExecutionGate.acquire()
        return try {
            mediaActions.save(mediaId, exportMode)
        } finally {
            ArchiveExecutionGate.release()
        }
    }

    private fun afterArchiveMutation() {
        ArchiveEventBus.emitArchiveChanged()
    }

    private fun reportError(result: MethodChannel.Result, error: Exception) {
        if (error is ArchiveException) {
            result.error(error.code, error.message, null)
        } else {
            Log.e(TAG, sanitizedStackTrace(error))
            result.error("ARCHIVE_ERROR", error.message ?: "本地归档操作失败", null)
        }
    }

    private fun reportLoginCancelled(result: MethodChannel.Result) {
        result.error("LOGIN_CANCELLED", "Instagram 登录已取消，请重新打开登录页", null)
    }

    private fun asException(error: Throwable): Exception {
        var current = error
        while (current is CompletionException && current.cause != null) {
            current = current.cause!!
        }
        return current as? Exception ?: RuntimeException("Instagram WebView 数据清理失败", current)
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
        val exportMode: String,
        val result: MethodChannel.Result,
    )

    companion object {
        const val METHOD_CHANNEL = "com.mantou.photobook/archive"
        const val EVENT_CHANNEL = "com.mantou.photobook/archive_events"
        private const val INSTAGRAM_URL = "https://www.instagram.com/"
        private const val ARCHIVE_LINK_CLIP_LABEL = "PhotoBook 帖子链接"
        private const val MAX_COOKIE_HEADER_LENGTH = 32 * 1024
        private const val LEGACY_STORAGE_PERMISSION_REQUEST = 102
        private const val CLIPBOARD_PREFERENCES = "archive_clipboard"
        private const val KEY_LAST_CLIPBOARD_FINGERPRINT = "last_fingerprint"
        private const val CLIPBOARD_QUEUED = "queued"
        private const val CLIPBOARD_COMPLETED = "completed"
        private const val CLIPBOARD_EMPTY = "empty"
        private const val CLIPBOARD_UNSUPPORTED = "unsupported"
        private const val CLIPBOARD_ALREADY_PROCESSED = "already_processed"
        private const val CLIPBOARD_SKIPPED = "skipped"
        private const val CLIPBOARD_UNAVAILABLE = "unavailable"
        private const val CLIPBOARD_STALE = "stale"
        private const val TAG = "ArchivePlatform"
    }
}
