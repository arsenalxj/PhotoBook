package com.mantou.photobook

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import com.mantou.photobook.archive.ArchiveDatabase
import com.mantou.photobook.archive.ArchiveForegroundService
import com.mantou.photobook.archive.ArchiveEventBus
import com.mantou.photobook.archive.ArchiveLinkImporter
import com.mantou.photobook.archive.ArchivePlatformHandler
import com.mantou.photobook.archive.AutomaticClipboardImportGate
import com.mantou.photobook.update.UpdateEventBus
import com.mantou.photobook.update.UpdatePlatformHandler
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel

class MainActivity : FlutterActivity() {
    private val automaticClipboardImportGate = AutomaticClipboardImportGate()
    private var archivePlatformHandler: ArchivePlatformHandler? = null
    private var updatePlatformHandler: UpdatePlatformHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        archivePlatformHandler =
            ArchivePlatformHandler(this, messenger, automaticClipboardImportGate)
        updatePlatformHandler = UpdatePlatformHandler(this, messenger)
        EventChannel(messenger, ArchivePlatformHandler.EVENT_CHANNEL)
            .setStreamHandler(ArchiveEventBus)
        EventChannel(messenger, UpdatePlatformHandler.EVENT_CHANNEL)
            .setStreamHandler(UpdateEventBus)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        archivePlatformHandler?.close()
        archivePlatformHandler = null
        updatePlatformHandler?.close()
        updatePlatformHandler = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (archivePlatformHandler?.onRequestPermissionsResult(requestCode, grantResults) == true) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        automaticClipboardImportGate.markSystemShareReceived()
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        ArchiveDatabase(this).use { database ->
            val request = ArchiveLinkImporter.parse(sharedText) ?: return
            val job = ArchiveLinkImporter.enqueue(database, request)
            ArchiveEventBus.emitJobChanged()
            if (job.status != "completed") ArchiveForegroundService.start(this)
        }
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 101
    }
}
