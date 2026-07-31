package com.mantou.photobook.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaohongshuShareParserTest {
    @Test
    fun `parses short link and keeps local request context`() {
        val link = XiaohongshuShareParser.parse("复制打开 https://xhslink.com/a/AbCd?xsec_token=test 看笔记")!!

        assertEquals("https://xhslink.com/a/AbCd?xsec_token=test", link.requestUrl)
        assertNull(link.sourcePostId)
    }

    @Test
    fun `parses direct note id`() {
        val link = XiaohongshuShareParser.parse(
            "https://www.xiaohongshu.com/explore/64abc123456789ab?xsec_token=test",
        )!!

        assertEquals("64abc123456789ab", link.sourcePostId)
        assertTrue(link.requestKey.contains("xsec_token=test"))
    }

    @Test
    fun `parses full web share text with discovery item`() {
        val link =
            XiaohongshuShareParser.parse(
                "35 【漆皮配灰丝 - NiNiLihaoya | 小红书】 " +
                    "https://www.xiaohongshu.com/discovery/item/" +
                    "6a6805b1000000000f01c9de?source=webshare&xsec_token=test",
            )!!

        assertEquals("6a6805b1000000000f01c9de", link.sourcePostId)
        assertTrue(link.requestUrl.contains("/discovery/item/6a6805b1000000000f01c9de"))
        assertTrue(link.requestUrl.contains("xsec_token=test"))
    }

    @Test
    fun `upgrades official http short link copied by mobile app`() {
        val link =
            XiaohongshuShareParser.parse(
                "吃饱了才有力气减肥 http://xhslink.cn/o/8cuxlUm1O8E " +
                    "小伙伴复制一下，打开【小红书】就能看到内容。",
            )!!

        assertEquals("https://xhslink.cn/o/8cuxlUm1O8E", link.requestUrl)
        assertEquals(link.requestUrl, link.requestKey)
        assertNull(link.sourcePostId)
    }

    @Test
    fun `rejects insecure direct or lookalike hosts`() {
        assertNull(
            XiaohongshuShareParser.parse(
                "http://www.xiaohongshu.com/explore/64abc123456789ab",
            ),
        )
        assertNull(XiaohongshuShareParser.parse("https://xhslink.com.example/a/test"))
    }
}
