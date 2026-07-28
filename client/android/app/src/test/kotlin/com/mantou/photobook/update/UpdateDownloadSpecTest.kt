package com.mantou.photobook.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateDownloadSpecTest {
    @Test
    fun `accepts fixed PhotoBook GitHub release asset`() {
        val spec = UpdateDownloadSpec.fromMap(validArguments())

        assertEquals("photobook-v1.1.0+2-arm64-v8a.apk", spec.fileName)
        assertEquals(2L, spec.versionCode)
    }

    @Test
    fun `rejects path traversal in file name`() {
        val arguments = validArguments().toMutableMap()
        arguments["fileName"] = "../photobook.apk"

        assertThrows(UpdateException::class.java) {
            UpdateDownloadSpec.fromMap(arguments)
        }
    }

    @Test
    fun `rejects download URL outside public PhotoBook repository`() {
        val arguments = validArguments().toMutableMap()
        arguments["downloadUrl"] = "https://example.com/photobook.apk"

        assertThrows(UpdateException::class.java) {
            UpdateDownloadSpec.fromMap(arguments)
        }
    }

    @Test
    fun `rejects malformed sha256`() {
        val arguments = validArguments().toMutableMap()
        arguments["sha256"] = "abc"

        assertThrows(UpdateException::class.java) {
            UpdateDownloadSpec.fromMap(arguments)
        }
    }

    private fun validArguments(): Map<String, Any> =
        mapOf(
            "downloadUrl" to
                "https://github.com/arsenalxj/PhotoBook/releases/download/" +
                "v1.1.0+2/photobook-v1.1.0+2-arm64-v8a.apk",
            "fileName" to "photobook-v1.1.0+2-arm64-v8a.apk",
            "size" to 123456L,
            "sha256" to "a".repeat(64),
            "versionCode" to 2L,
        )
}
