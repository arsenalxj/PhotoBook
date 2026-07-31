package com.mantou.photobook.archive

import java.net.URI

data class XiaohongshuShareLink(
    val requestUrl: String,
    val requestKey: String,
    val sourcePostId: String?,
)

object XiaohongshuShareParser {
    private val urlPattern = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    private val notePathPattern =
        Regex("""/(?:explore|discovery/item)/([0-9a-f]{16,32})(?:/|$)""", RegexOption.IGNORE_CASE)
    private val shortLinkHosts =
        setOf(
            "xhslink.com",
            "www.xhslink.com",
            "xhslink.cn",
            "www.xhslink.cn",
        )
    private val allowedHosts =
        shortLinkHosts +
            setOf(
            "xiaohongshu.com",
            "www.xiaohongshu.com",
            "rednote.com",
            "www.rednote.com",
            )

    fun parse(sharedText: String): XiaohongshuShareLink? {
        for (match in urlPattern.findAll(sharedText)) {
            val raw = match.value.trimEnd('.', ',', ';', ')', ']', '}', '，', '。')
            val uri = runCatching { URI(raw) }.getOrNull() ?: continue
            val host = uri.host?.lowercase() ?: continue
            if (host !in allowedHosts) continue
            if (uri.userInfo != null || uri.port !in setOf(-1, 443)) continue
            val normalizedUri =
                when {
                    uri.scheme.equals("https", ignoreCase = true) -> uri
                    uri.scheme.equals("http", ignoreCase = true) && host in shortLinkHosts ->
                        URI(
                            "https",
                            null,
                            host,
                            -1,
                            uri.path,
                            uri.query,
                            uri.fragment,
                        )
                    else -> continue
                }
            val requestUrl = normalizedUri.toASCIIString()
            val sourcePostId =
                notePathPattern.find(normalizedUri.path.orEmpty())?.groupValues?.get(1)
            return XiaohongshuShareLink(
                requestUrl = requestUrl,
                requestKey = requestUrl,
                sourcePostId = sourcePostId,
            )
        }
        return null
    }
}
