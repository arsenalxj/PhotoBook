package com.mantou.photobook.archive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mantou.photobook.MainActivity
import com.mantou.photobook.R
import java.util.concurrent.TimeUnit

class ArchiveCaptureRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!ArchiveExecutionGate.tryAcquire()) return Result.retry()
        val database = ArchiveDatabase(applicationContext)
        var runError: String? = null
        ArchiveEventBus.emitRunStarted()
        return try {
            setForeground(recoveryForegroundInfo(applicationContext, "正在恢复未完成的帖子"))
            if (database.recoverInterruptedJobs()) ArchiveEventBus.emitJobChanged()
            ArchiveRunner(applicationContext, database).runPending { _, _, _ -> }
            database.nextQueuedDelayMs()?.let { delay ->
                ArchiveRecoveryScheduler.appendCapture(applicationContext, delay)
            }
            if (runCatching { R2ConfigStore(applicationContext).read() != null }.getOrDefault(false)) {
                ArchiveRecoveryScheduler.scheduleSyncNow(applicationContext)
            }
            Result.success()
        } catch (error: Exception) {
            runError = "帖子后台恢复失败，请重新打开 App 后重试"
            Result.retry()
        } finally {
            if (runCatching { database.recoverInterruptedJobs() }.getOrDefault(false)) {
                ArchiveEventBus.emitJobChanged()
            }
            database.close()
            ArchiveExecutionGate.release()
            ArchiveEventBus.emitArchiveChanged()
            ArchiveEventBus.emitRunFinished(runError)
        }
    }
}

class R2SyncRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!ArchiveExecutionGate.tryAcquire()) return Result.retry()
        val database = ArchiveDatabase(applicationContext)
        var runError: String? = null
        ArchiveEventBus.emitRunStarted()
        return try {
            setForeground(recoveryForegroundInfo(applicationContext, "正在恢复 R2 同步"))
            val syncResult = R2SyncEngine(applicationContext, database).syncIfConfigured()
            runError = syncResult.error
            ArchiveRecoveryScheduler.syncDelay(syncResult)?.let { delay ->
                ArchiveRecoveryScheduler.appendSync(applicationContext, delay)
            }
            Result.success()
        } catch (error: Exception) {
            runError = "R2 后台恢复失败，请重新打开 App 后重试"
            Result.retry()
        } finally {
            database.close()
            ArchiveExecutionGate.release()
            ArchiveEventBus.emitArchiveChanged()
            ArchiveEventBus.emitRunFinished(runError)
        }
    }
}

internal enum class RecoveryWorkKind {
    CAPTURE,
    SYNC,
}

internal data class RecoveryWorkUpdate(
    val kind: RecoveryWorkKind,
    val delayMs: Long?,
)

object ArchiveRecoveryScheduler {
    internal const val CAPTURE_WORK_NAME = "archive_capture_recovery"
    internal const val SYNC_WORK_NAME = "r2_sync_recovery"

    fun scheduleCaptureIfNeeded(context: Context, database: ArchiveDatabase) {
        val update =
            captureUpdate(
                captureDelayMs = database.nextQueuedDelayMs(),
                hasRunningCapture = database.hasRunningCaptureJob(),
            ) ?: return
        replace(context, update)
    }

    fun scheduleSyncIfNeeded(context: Context, syncResult: R2SyncResult?) {
        replace(context, syncUpdate(syncResult))
    }

    fun scheduleSyncNow(context: Context) {
        replace(context, RecoveryWorkUpdate(RecoveryWorkKind.SYNC, 0))
    }

    internal fun appendCapture(context: Context, delayMs: Long) {
        append(context, RecoveryWorkUpdate(RecoveryWorkKind.CAPTURE, delayMs))
    }

    internal fun appendSync(context: Context, delayMs: Long) {
        append(context, RecoveryWorkUpdate(RecoveryWorkKind.SYNC, delayMs))
    }

    internal fun captureUpdate(
        captureDelayMs: Long?,
        hasRunningCapture: Boolean,
    ): RecoveryWorkUpdate? =
        if (hasRunningCapture) {
            null
        } else {
            RecoveryWorkUpdate(RecoveryWorkKind.CAPTURE, captureDelayMs)
        }

    internal fun syncUpdate(syncResult: R2SyncResult?): RecoveryWorkUpdate =
        RecoveryWorkUpdate(RecoveryWorkKind.SYNC, syncDelay(syncResult))

    internal fun syncDelay(syncResult: R2SyncResult?): Long? =
        syncResult?.takeIf { it.shouldRetry }?.retryDelay
            ?: syncResult?.takeIf { it.shouldRetry }?.let { SYNC_RETRY_DELAY_MS }

    private fun replace(context: Context, update: RecoveryWorkUpdate) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val workName = workName(update.kind)
        val delayMs = update.delayMs
        if (delayMs == null) {
            workManager.cancelUniqueWork(workName)
            return
        }
        workManager.enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE,
            request(update.kind, delayMs),
        )
    }

    private fun append(context: Context, update: RecoveryWorkUpdate) {
        val delayMs = checkNotNull(update.delayMs)
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(update.kind),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(update.kind, delayMs),
        )
    }

    private fun request(kind: RecoveryWorkKind, delayMs: Long): OneTimeWorkRequest {
        val builder =
            when (kind) {
                RecoveryWorkKind.CAPTURE ->
                    OneTimeWorkRequestBuilder<ArchiveCaptureRecoveryWorker>()
                RecoveryWorkKind.SYNC -> OneTimeWorkRequestBuilder<R2SyncRecoveryWorker>()
            }
        return builder
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            ).setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
    }

    private fun workName(kind: RecoveryWorkKind): String =
        when (kind) {
            RecoveryWorkKind.CAPTURE -> CAPTURE_WORK_NAME
            RecoveryWorkKind.SYNC -> SYNC_WORK_NAME
        }

    private const val SYNC_RETRY_DELAY_MS = 15L * 60L * 1000L
}

private fun recoveryForegroundInfo(context: Context, text: String): ForegroundInfo {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                RECOVERY_CHANNEL_ID,
                "任务恢复",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
    val openApp =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val notification =
        NotificationCompat.Builder(context, RECOVERY_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PhotoBook")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    val type =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
    return ForegroundInfo(RECOVERY_NOTIFICATION_ID, notification, type)
}

private const val RECOVERY_CHANNEL_ID = "archive_recovery"
private const val RECOVERY_NOTIFICATION_ID = 1002
