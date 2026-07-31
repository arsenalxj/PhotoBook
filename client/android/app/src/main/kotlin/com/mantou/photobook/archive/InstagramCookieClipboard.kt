package com.mantou.photobook.archive

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.util.UUID

internal fun interface CookieClearScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit)
}

internal class InstagramCookieClipboard internal constructor(
    private val clipboard: ClipboardManager,
    private val scheduler: CookieClearScheduler,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager,
        CookieClearScheduler { delayMillis, action ->
            Handler(Looper.getMainLooper()).postDelayed({ action() }, delayMillis)
        },
    )

    fun copy(cookieHeader: String) {
        require(cookieHeader.isNotBlank()) { "Instagram Cookie 为空" }
        val ownershipToken = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(CLIP_LABEL, cookieHeader)
        clip.description.extras =
            PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                putString(CLIP_OWNERSHIP_TOKEN, ownershipToken)
            }
        clipboard.setPrimaryClip(clip)
        scheduler.schedule(CLEAR_DELAY_MILLIS) {
            if (clipboard.primaryClipDescription?.extras?.getString(CLIP_OWNERSHIP_TOKEN) !=
                ownershipToken
            ) {
                return@schedule
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    companion object {
        private const val CLIP_LABEL = "Instagram Cookie"
        private const val CLIP_OWNERSHIP_TOKEN = "com.mantou.photobook.instagram_cookie_clip"
        internal const val CLEAR_DELAY_MILLIS = 60_000L
    }
}
