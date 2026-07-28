package com.mantou.photobook.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal enum class InstallResult(val wireValue: String) {
    INSTALLING("installing"),
    PERMISSION_REQUIRED("permissionRequired"),
}

internal class UpdateInstaller(private val activity: Activity) {
    private val context = activity.applicationContext
    private val packageManager = context.packageManager
    private val updateDirectory = File(context.cacheDir, UpdateDownloader.UPDATE_DIRECTORY)

    fun installedApp(): Map<String, Any> {
        val info = currentPackageInfo(0)
        return mapOf(
            "applicationId" to context.packageName,
            "versionName" to info.versionName.orEmpty(),
            "versionCode" to packageVersionCode(info),
            "sdkInt" to Build.VERSION.SDK_INT,
            "canInstallPackages" to canInstallPackages(),
        )
    }

    fun install(spec: UpdateInstallSpec): InstallResult {
        val file = verifyPackage(spec)
        if (!canInstallPackages()) {
            activity.runOnUiThread {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            return InstallResult.PERMISSION_REQUIRED
        }

        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(APPLICATION_ID)
            setSize(spec.size)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(file).use { input ->
                    session.openWrite("photobook-update.apk", 0, spec.size).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callbackIntent =
                    Intent(context, UpdateInstallReceiver::class.java).apply {
                        action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
                    }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            if (error is UpdateException) throw error
            throw UpdateException("UPDATE_INSTALL", "无法启动系统安装器", error)
        }
        return InstallResult.INSTALLING
    }

    private fun verifyPackage(spec: UpdateInstallSpec): File {
        val file = File(spec.path).canonicalFile
        if (file.parentFile != updateDirectory.canonicalFile || !file.name.endsWith("-arm64-v8a.apk")) {
            throw UpdateException("UPDATE_PACKAGE", "更新文件路径无效")
        }
        if (!UpdateFileIntegrity.verify(file, spec.size, spec.sha256)) {
            throw UpdateException("UPDATE_HASH", "更新文件校验失败，请重新下载")
        }
        val archiveInfo = archivePackageInfo(file)
            ?: throw UpdateException("UPDATE_PACKAGE", "无法读取更新安装包")
        if (context.packageName != APPLICATION_ID || archiveInfo.packageName != APPLICATION_ID) {
            throw UpdateException("UPDATE_PACKAGE", "更新安装包的包名不匹配")
        }
        val currentInfo = currentPackageInfo(signingInfoFlags())
        val archiveVersionCode = packageVersionCode(archiveInfo)
        if (archiveVersionCode != spec.versionCode ||
            archiveVersionCode <= packageVersionCode(currentInfo)
        ) {
            throw UpdateException("UPDATE_VERSION", "更新安装包版本无效")
        }
        if (archiveInfo.applicationInfo?.minSdkVersion?.let { it > Build.VERSION.SDK_INT } == true) {
            throw UpdateException("UPDATE_SDK", "当前 Android 版本不支持此更新")
        }
        val currentSigners = signerDigests(currentInfo)
        val archiveSigners = signerDigests(archiveInfo)
        if (currentSigners.isEmpty() || archiveSigners != currentSigners) {
            throw UpdateException("UPDATE_SIGNATURE", "更新安装包签名与当前 App 不一致")
        }
        return file
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun currentPackageInfo(flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            packageManager.getPackageInfo(context.packageName, flags)
        }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(file: File): PackageInfo? {
        val flags = signingInfoFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    @Suppress("DEPRECATION")
    private fun signingInfoFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    companion object {
        private const val APPLICATION_ID = "com.mantou.photobook"
    }
}

class UpdateInstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent)
                }
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) context.startActivity(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                File(context.cacheDir, UpdateDownloader.UPDATE_DIRECTORY).listFiles()?.forEach(File::delete)
            }
            else -> {
                UpdateEventBus.emitInstallFailed("系统安装失败（状态 $status），请重新下载后重试")
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.mantou.photobook.UPDATE_INSTALL_STATUS"
    }
}
