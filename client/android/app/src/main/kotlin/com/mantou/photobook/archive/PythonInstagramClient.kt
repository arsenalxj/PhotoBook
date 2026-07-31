package com.mantou.photobook.archive

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

internal class PythonInstagramClient(context: Context) : InstagramGateway {
    private val applicationContext = context.applicationContext

    override fun validateSession(cookieHeader: String, validatedAt: Long): InstagramSession {
        try {
            val raw = module().callAttr("validate_session", cookieHeader).toString()
            return InstagramSession.fromPythonJson(raw, validatedAt)
        } catch (error: PyException) {
            throw parsePythonError(error)
        } catch (error: ArchiveException) {
            throw error
        } catch (error: Exception) {
            throw ArchiveException("INVALID_RESPONSE", "Instagram 登录验证结果无效", error)
        }
    }

    override fun fetchPost(shortcode: String, session: InstagramSession?): InstagramFetchOutcome {
        try {
            val raw =
                module().callAttr("fetch_post", shortcode, session?.toPythonJson() ?: "").toString()
            val envelope = JSONObject(raw)
            if (envelope.optBoolean("mediaInfoRequired", false)) {
                if (session == null) {
                    throw ArchiveException("INVALID_RESPONSE", "Instagram 匿名解析返回了认证阶段")
                }
                return InstagramMediaInfoRequired
            }
            return parseFetchResult(envelope, session)
        } catch (error: PyException) {
            throw parsePythonError(error)
        } catch (error: ArchiveException) {
            throw error
        } catch (error: Exception) {
            throw ArchiveException("INVALID_RESPONSE", "Instagram 返回的数据无法解析", error)
        }
    }

    override fun fetchPostMediaInfo(
        shortcode: String,
        session: InstagramSession,
    ): InstagramFetchResult {
        try {
            val raw =
                module().callAttr("fetch_post_media_info", shortcode, session.toPythonJson()).toString()
            return parseFetchResult(JSONObject(raw), session)
        } catch (error: PyException) {
            throw parsePythonError(error)
        } catch (error: ArchiveException) {
            throw error
        } catch (error: Exception) {
            throw ArchiveException("INVALID_RESPONSE", "Instagram 返回的数据无法解析", error)
        }
    }

    private fun parseFetchResult(
        envelope: JSONObject,
        session: InstagramSession?,
    ): InstagramFetchResult {
        val post = RemotePost.fromJson(envelope.getJSONObject("post").toString())
        val refreshedSession =
            if (envelope.isNull("refreshedSession")) {
                null
            } else {
                InstagramSession.fromPythonJson(
                    envelope.getJSONObject("refreshedSession").toString(),
                    session?.validatedAt ?: System.currentTimeMillis(),
                )
            }
        return InstagramFetchResult(post, refreshedSession)
    }

    private fun module(): PyObject =
        synchronized(PythonInstagramClient::class.java) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
            Python.getInstance().getModule("photobook_bridge")
        }

    private fun parsePythonError(error: PyException): ArchiveException {
        val raw = error.message.orEmpty()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching {
                val json = JSONObject(raw.substring(start, end + 1))
                val code = json.optString("code").takeIf { it.isNotBlank() }
                val message = json.optString("message").takeIf { it.isNotBlank() }
                if (code != null && message != null) {
                    return ArchiveException(code, message, error)
                }
            }
        }
        return ArchiveException("INSTAGRAM_ERROR", "Instagram 帖子解析失败", error)
    }
}
