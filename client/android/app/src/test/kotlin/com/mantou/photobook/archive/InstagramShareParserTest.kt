package com.mantou.photobook.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstagramShareParserTest {
    @Test
    fun `normalizes reel share`() {
        val result =
            InstagramShareParser.parse(
                "看看 https://www.instagram.com/reels/Abc_123/?igsh=test",
            )

        assertEquals("Abc_123", result?.sourcePostId)
        assertEquals("https://www.instagram.com/reel/Abc_123/", result?.canonicalUrl)
    }

    @Test
    fun `rejects profile and other hosts`() {
        assertNull(InstagramShareParser.parse("https://www.instagram.com/author/"))
        assertNull(InstagramShareParser.parse("https://example.com/p/Abc123/"))
    }
}
