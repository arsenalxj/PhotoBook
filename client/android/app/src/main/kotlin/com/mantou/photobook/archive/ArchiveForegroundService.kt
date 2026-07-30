package com.mantou.photobook.archive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mantou.photobook.MainActivity
import com.mantou.photobook.R
import java.util.concurrent.Executors

class ArchiveForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var database: ArchiveDatabase
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        database = ArchiveDatabase(this)
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:archive",
            ).apply { setReferenceCounted(false) }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(buildNotification("正在准备保存帖子"))
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        executor.execute {
            var acquired = false
            var syncResult: R2SyncResult? = null
            var syncAttempted = false
            var runError: String? = null
            ArchiveEventBus.emitRunStarted()
            try {
                ArchiveExecutionGate.acquire()
                acquired = true
                database.recoverInterruptedJobs()
                ArchiveRunner(this, database).runPending { job, current, total ->
                    val text =
                        if (total > 0) {
                            "正在保存 ${job.sourcePostId}（$current/$total）"
                        } else {
                            "正在保存 ${job.sourcePostId}"
                        }
                    updateNotification(text, current, total)
                }
                updateNotification("正在同步 R2", 0, 0)
                syncAttempted = true
                syncResult = R2SyncEngine(this, database).syncIfConfigured()
                runError = syncResult?.error
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                runError = "归档任务执行失败，请重新打开 App 后重试"
                if (syncAttempted) {
                    syncResult =
                        R2SyncResult(
                            error = runError,
                            hasRemainingWork = true,
                            shouldRetry = true,
                        )
                }
            } finally {
                if (acquired) {
                    database.recoverInterruptedJobs()
                    ArchiveExecutionGate.release()
                }
                ArchiveRecoveryScheduler.scheduleCaptureIfNeeded(this, database)
                if (syncAttempted) {
                    ArchiveRecoveryScheduler.scheduleSyncIfNeeded(this, syncResult)
                }
                ArchiveEventBus.emitArchiveChanged()
                ArchiveEventBus.emitRunFinished(runError)
                stopCleanly(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (wakeLock.isHeld) wakeLock.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        executor.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification(text: String, current: Int, total: Int) {
        val notification = buildNotification(text, current, total)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        text: String,
        current: Int = 0,
        total: Int = 0,
    ): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PhotoBook")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (total > 0) setProgress(total, current.coerceAtMost(total), false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "帖子保存",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "显示正在保存的 Instagram 帖子" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun stopCleanly(startId: Int) {
        if (!stopSelfResult(startId)) return
        if (wakeLock.isHeld) wakeLock.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val CHANNEL_ID = "archive_jobs"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ArchiveForegroundService::class.java),
            )
        }
    }
}
