package com.mantou.photobook.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class R2ObjectStoreTest {
    @Test
    fun `same size object with another checksum is rejected`() {
        val error =
            assertThrows(ArchiveException::class.java) {
                validateExistingBackupMediaObject(
                    existingSize = 4,
                    existingSha256 = "b".repeat(64),
                    expectedSize = 4,
                    expectedSha256 = "a".repeat(64),
                )
            }

        assertEquals("BACKUP_CONFLICT", error.code)
    }

    @Test
    fun `object without checksum metadata is rejected`() {
        val error =
            assertThrows(ArchiveException::class.java) {
                validateExistingBackupMediaObject(
                    existingSize = 4,
                    existingSha256 = null,
                    expectedSize = 4,
                    expectedSha256 = "a".repeat(64),
                )
            }

        assertEquals("BACKUP_CONFLICT", error.code)
    }

    @Test
    fun `matching size and checksum metadata is accepted`() {
        val sha256 = "a".repeat(64)

        validateExistingBackupMediaObject(
            existingSize = 4,
            existingSha256 = sha256,
            expectedSize = 4,
            expectedSha256 = sha256,
        )
    }
}
