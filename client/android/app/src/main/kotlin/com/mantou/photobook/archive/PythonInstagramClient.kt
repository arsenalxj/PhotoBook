package com.mantou.photobook.archive

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

class PythonInstagramClient(context: Context) {
    private val applicationContext = context.applicationContext

    fun fetchPost(shortcode: String): RemotePost {
        try {
            val raw = module().callAttr("fetch_post", shortcode).toString()
            return RemotePost.fromJson(raw)
        } catch (error: PyException) {
            throw parsePythonError(error)
        } catch (error: ArchiveException) {
            throw error
        } catch (error: Exception) {
            throw ArchiveException("INVALID_RESPONSE", "Instagram 返回的数据无法解析", error)
        }
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
