package com.mantou.photobook.archive

data class InstagramShareLink(val canonicalUrl: String, val sourcePostId: String)

object InstagramShareParser {
    private val linkPattern =
        Regex(
            """https?://(?:www\.)?instagram\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)(?:[/?#][^\s]*)?""",
            RegexOption.IGNORE_CASE,
        )

    fun parse(sharedText: String): InstagramShareLink? {
        val match = linkPattern.find(sharedText) ?: return null
        val shortcode = match.groupValues[1]
        val kind =
            if (match.value.substringBefore(shortcode).contains("reel", ignoreCase = true)) {
                "reel"
            } else {
                "p"
            }
        return InstagramShareLink(
            canonicalUrl = "https://www.instagram.com/$kind/$shortcode/",
            sourcePostId = shortcode,
        )
    }
}
