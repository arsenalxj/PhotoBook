package com.mantou.photobook.archive

import android.content.Context
import android.util.Log

internal data class InstagramFetchResult(
    val post: RemotePost,
    val refreshedSession: InstagramSession?,
)

internal interface InstagramGateway {
    fun validateSession(cookieHeader: String, validatedAt: Long): InstagramSession

    fun fetchPost(shortcode: String, session: InstagramSession?): InstagramFetchResult
}

internal class InstagramClient internal constructor(
    private val gateway: InstagramGateway,
    private val sessions: InstagramSessionRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        PythonInstagramClient(context),
        InstagramSessionStore(context),
    )

    fun sessionSummary(): Map<String, Any>? = sessions.read()?.summary()

    fun validateSession(cookieHeader: String): InstagramSession =
        gateway.validateSession(cookieHeader, clock())

    fun saveSession(session: InstagramSession) = sessions.save(session)

    fun clearSession() = sessions.clear()

    fun fetchPost(shortcode: String): RemotePost {
        val anonymousFailure =
            try {
                return gateway.fetchPost(shortcode, null).post
            } catch (error: ArchiveException) {
                if (error.code != "LOGIN_REQUIRED") throw error
                error
            }

        val session = sessions.read() ?: throw anonymousFailure
        if (session.status != InstagramSessionStatus.READY) {
            throw ArchiveException("LOGIN_REQUIRED", "Instagram 登录已失效，请重新登录")
        }

        val authenticated =
            try {
                gateway.fetchPost(shortcode, session)
            } catch (error: ArchiveException) {
                if (error.code == "LOGIN_REQUIRED") {
                    sessions.markNeedsRefresh()
                    throw ArchiveException("LOGIN_REQUIRED", "Instagram 登录已失效，请重新登录", error)
                }
                throw error
            }

        authenticated.refreshedSession?.let { refreshed ->
            if (!session.hasSameAccount(refreshed)) {
                throw ArchiveException("INVALID_RESPONSE", "Instagram Session 账号发生变化")
            }
            try {
                sessions.save(refreshed.refreshedAt(clock()))
            } catch (_: Exception) {
                Log.w(TAG, "帖子已获取，但 Instagram Session 刷新保存失败")
            }
        }
        return authenticated.post
    }

    companion object {
        private const val TAG = "InstagramClient"
    }
}
