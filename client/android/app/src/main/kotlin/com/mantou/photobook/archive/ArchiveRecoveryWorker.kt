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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mantou.photobook.MainActivity
import com.mantou.photobook.R
import java.util.concurrent.TimeUnit

class ArchiveRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        setForeground(foregroundInfo())
        if (!ArchiveExecutionGate.tryAcquire()) return Result.retry()
        val database = ArchiveDatabase(applicationContext)
        var runError: String? = null
        ArchiveEventBus.emitRunStarted()
        return try {
            database.recoverInterruptedJobs()
            ArchiveRunner(applicationContext, database).runPending { _, _, _ -> }
            val syncResult = R2SyncEngine(applicationContext, database).syncIfConfigured()
            runError = syncResult.error
            if (database.nextQueuedDelayMs() == null && !syncResult.shouldRetry) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (error: Exception) {
            runError = "后台恢复失败，请重新打开 App 后重试"
            Result.retry()
        } finally {
            database.recoverInterruptedJobs()
            database.close()
            ArchiveExecutionGate.release()
            ArchiveEventBus.emitArchiveChanged()
            ArchiveEventBus.emitRunFinished(runError)
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val openApp =
            PendingIntent.getActivity(
                applicationContext,
                0,
                Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("PhotoBook")
                .setContentText("正在恢复未完成的帖子")
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
        return ForegroundInfo(NOTIFICATION_ID, notification, type)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "任务恢复", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "archive_recovery"
        private const val NOTIFICATION_ID = 1002
    }
}

object ArchiveRecoveryScheduler {
    private const val WORK_NAME = "archive_recovery"

    fun scheduleIfNeeded(
        context: Context,
        database: ArchiveDatabase,
        syncResult: R2SyncResult?,
    ) {
        val captureDelay = database.nextQueuedDelayMs()
        val syncDelay =
            syncResult?.takeIf { it.shouldRetry }
                ?.retryDelay
                ?: syncResult?.takeIf { it.shouldRetry }?.let { SYNC_RETRY_DELAY_MS }
        val workManager = WorkManager.getInstance(context.applicationContext)
        val delay = listOfNotNull(captureDelay, syncDelay).minOrNull()
        if (delay == null) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request =
            OneTimeWorkRequestBuilder<ArchiveRecoveryWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private const val SYNC_RETRY_DELAY_MS = 15L * 60L * 1000L
}
