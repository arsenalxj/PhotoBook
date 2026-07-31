package com.mantou.photobook.archive

internal data class ArchiveImportRequest(
    val sourceUrl: String,
    val sourcePlatform: String,
    val requestKey: String,
    val sourcePostId: String?,
)

internal class AutomaticClipboardImportGate {
    private var skipNext = false

    fun markSystemShareReceived() {
        skipNext = true
    }

    fun consumeSkip(): Boolean = skipNext.also { skipNext = false }
}

internal fun isRecentClipboardTimestamp(copiedAt: Long, now: Long): Boolean =
    copiedAt > 0 && copiedAt <= now && now - copiedAt <= AUTO_CLIPBOARD_MAX_AGE_MS

private const val AUTO_CLIPBOARD_MAX_AGE_MS = 10 * 60 * 1000L

internal object ArchiveLinkImporter {
    fun parse(sharedText: String): ArchiveImportRequest? {
        InstagramShareParser.parse(sharedText)?.let { instagram ->
            return ArchiveImportRequest(
                sourceUrl = instagram.canonicalUrl,
                sourcePlatform = SOURCE_PLATFORM_INSTAGRAM,
                requestKey = instagram.sourcePostId,
                sourcePostId = instagram.sourcePostId,
            )
        }
        XiaohongshuShareParser.parse(sharedText)?.let { xiaohongshu ->
            return ArchiveImportRequest(
                sourceUrl = xiaohongshu.requestUrl,
                sourcePlatform = SOURCE_PLATFORM_XIAOHONGSHU,
                requestKey = xiaohongshu.requestKey,
                sourcePostId = xiaohongshu.sourcePostId,
            )
        }
        return null
    }

    fun enqueue(database: ArchiveDatabase, request: ArchiveImportRequest): CaptureJob =
        database.enqueue(
            sourceUrl = request.sourceUrl,
            sourcePlatform = request.sourcePlatform,
            requestKey = request.requestKey,
            sourcePostId = request.sourcePostId,
        )
}
