package com.mantou.photobook.archive

import io.minio.messages.ListBucketResultV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.simpleframework.xml.core.Persister

class R2AndroidXmlCompatibilityTest {
    @Test
    fun `simple xml includes Android pull parser fallback`() {
        val pullProvider =
            runCatching {
                Class.forName("org.simpleframework.xml.stream.PullProvider")
            }.getOrNull()

        assertNotNull(
            "R2 XML parsing must fall back to Android XmlPullParser when StAX is unavailable",
            pullProvider,
        )
    }

    @Test
    fun `simple xml parses an R2 list objects response`() {
        val response =
            Persister().read(
                ListBucketResultV2::class.java,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Name>photobook</Name>
                  <Prefix>photobook/</Prefix>
                  <KeyCount>0</KeyCount>
                  <MaxKeys>1</MaxKeys>
                  <IsTruncated>false</IsTruncated>
                </ListBucketResult>
                """.trimIndent(),
                false,
            )

        assertEquals("photobook", response.name())
        assertEquals("photobook/", response.prefix())
        assertEquals(1, response.maxKeys())
        assertTrue(response.contents().isEmpty())
    }
}
