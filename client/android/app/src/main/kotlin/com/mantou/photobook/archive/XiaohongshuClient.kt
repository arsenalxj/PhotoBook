package com.mantou.photobook.archive

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

internal class XiaohongshuClient(context: Context) {
    private val applicationContext = context.applicationContext

    fun fetchPost(requestUrl: String, isAttemptActive: () -> Boolean = { true }): RemotePost {
        if (!isAttemptActive()) throw ArchiveAttemptStoppedException()
        try {
            val raw = module().callAttr("fetch_post", requestUrl).toString()
            if (!isAttemptActive()) throw ArchiveAttemptStoppedException()
            return RemotePost.fromJson(raw).also {
                require(it.sourcePlatform == SOURCE_PLATFORM_XIAOHONGSHU) {
                    "小红书返回了错误的来源平台"
                }
            }
        } catch (error: PyException) {
            throw parsePythonError(error)
        } catch (error: ArchiveAttemptStoppedException) {
            throw error
        } catch (error: ArchiveException) {
            throw error
        } catch (error: Exception) {
            throw ArchiveException("INVALID_RESPONSE", "小红书返回的数据无法解析", error)
        }
    }

    private fun module(): PyObject =
        synchronized(XiaohongshuClient::class.java) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
            Python.getInstance().getModule("xiaohongshu_bridge")
        }

    private fun parsePythonError(error: PyException): ArchiveException {
        val raw = error.message.orEmpty()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching {
                val json = JSONObject(raw.substring(start, end + 1))
                val code = json.optString("code").takeIf(String::isNotBlank)
                val message = json.optString("message").takeIf(String::isNotBlank)
                if (code != null && message != null) {
                    return ArchiveException(code, message, error)
                }
            }
        }
        return ArchiveException("XIAOHONGSHU_ERROR", "小红书帖子解析失败", error)
    }
}
