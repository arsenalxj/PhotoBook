package com.mantou.photobook.archive

import java.net.URI
import org.json.JSONObject

data class CaptureJob(
    val id: String,
    val sourcePostId: String?,
    val status: String,
    val attemptCount: Int,
    val sourcePlatform: String = SOURCE_PLATFORM_INSTAGRAM,
    val requestKey: String = sourcePostId.orEmpty(),
)

enum class JobCancellationResult {
    QUEUED,
    RUNNING,
}

data class RemotePost(
    val sourcePostId: String,
    val sourceUrl: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val authorProfileUrl: String,
    val authorAvatarUrl: String?,
    val caption: String,
    val publishedAt: Long,
    val locationName: String?,
    val media: List<RemoteMedia>,
    val sourcePlatform: String = SOURCE_PLATFORM_INSTAGRAM,
) {
    companion object {
        fun fromJson(raw: String): RemotePost {
            val json = JSONObject(raw)
            val mediaJson = json.getJSONArray("media")
            val media =
                buildList {
                    for (index in 0 until mediaJson.length()) {
                        add(RemoteMedia.fromJson(mediaJson.getJSONObject(index)))
                    }
                }
            require(media.isNotEmpty()) { "帖子没有媒体" }
            require(media.map(RemoteMedia::sortIndex).distinct().size == media.size) {
                "媒体排序编号重复"
            }
            media.groupBy(RemoteMedia::logicalIndex).values.forEach { group ->
                val roles = group.map(RemoteMedia::mediaRole).toSet()
                require(roles.size == group.size) { "逻辑媒体角色重复" }
                require(
                    roles == setOf(MEDIA_ROLE_PRIMARY) ||
                        roles == setOf(MEDIA_ROLE_LIVE_STILL) ||
                        roles == setOf(MEDIA_ROLE_LIVE_STILL, MEDIA_ROLE_LIVE_MOTION),
                ) { "逻辑媒体分组无效" }
            }
            val sourcePlatform = json.getString("sourcePlatform")
            require(sourcePlatform in SUPPORTED_SOURCE_PLATFORMS) { "帖子来源平台无效" }
            val sourcePostId = json.getString("sourcePostId")
            require(SOURCE_POST_ID_PATTERN.matches(sourcePostId)) { "帖子来源编号无效" }
            val sourceUrl = json.getString("sourceUrl")
            requireCanonicalSourceUrl(sourcePlatform, sourcePostId, sourceUrl)
            return RemotePost(
                sourcePostId = sourcePostId,
                sourceUrl = sourceUrl,
                authorUsername = json.getString("authorUsername"),
                authorDisplayName = json.getString("authorDisplayName"),
                authorProfileUrl = json.getString("authorProfileUrl"),
                authorAvatarUrl = json.optionalString("authorAvatarUrl"),
                caption = json.optString("caption"),
                publishedAt = json.getLong("publishedAt"),
                locationName = json.optionalString("locationName"),
                media = media.sortedBy { it.sortIndex },
                sourcePlatform = sourcePlatform,
            )
        }
    }
}

data class RemoteMedia(
    val sortIndex: Int,
    val mediaType: String,
    val url: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val logicalIndex: Int = sortIndex,
    val mediaRole: String = MEDIA_ROLE_PRIMARY,
    val fallbackUrl: String? = null,
) {
    val downloadUrls: List<String>
        get() = listOfNotNull(url, fallbackUrl?.takeIf(String::isNotBlank)).distinct()

    companion object {
        fun fromJson(json: JSONObject): RemoteMedia {
            val sortIndex = json.getInt("sortIndex")
            val logicalIndex = json.getInt("logicalIndex")
            val mediaType = json.getString("mediaType")
            val mediaRole = json.getString("mediaRole")
            require(sortIndex >= 0 && logicalIndex >= 0) { "媒体编号无效" }
            require(mediaType == "image" || mediaType == "video") { "媒体类型无效" }
            require(mediaRole in SUPPORTED_MEDIA_ROLES) { "媒体角色无效" }
            require(mediaRole != MEDIA_ROLE_LIVE_STILL || mediaType == "image") {
                "Live Photo 静态部分必须是图片"
            }
            require(mediaRole != MEDIA_ROLE_LIVE_MOTION || mediaType == "video") {
                "Live Photo 动态部分必须是视频"
            }
            return RemoteMedia(
                sortIndex = sortIndex,
                mediaType = mediaType,
                url = json.getString("url"),
                width = json.optionalPositiveInt("width"),
                height = json.optionalPositiveInt("height"),
                durationMs = json.optionalPositiveLong("durationMs"),
                logicalIndex = logicalIndex,
                mediaRole = mediaRole,
                fallbackUrl = json.optionalString("fallbackUrl"),
            )
        }
    }
}

data class PreparedPost(
    val sourcePostId: String,
    val sourceUrl: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val authorProfileUrl: String,
    val authorAvatarSha256: String?,
    val localAvatarPath: String?,
    val caption: String,
    val publishedAt: Long,
    val locationName: String?,
    val media: List<PreparedMedia>,
    val createdFilePaths: List<String> = emptyList(),
    val sourcePlatform: String = SOURCE_PLATFORM_INSTAGRAM,
) {
    val id: String = "$sourcePlatform:$sourcePostId"
}

data class PreparedMedia(
    val sortIndex: Int,
    val mediaType: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val originalSize: Long,
    val originalSha256: String,
    val thumbnailSha256: String,
    val localOriginalPath: String,
    val localThumbnailPath: String,
    val logicalIndex: Int = sortIndex,
    val mediaRole: String = MEDIA_ROLE_PRIMARY,
) {
    fun id(postId: String): String = "$postId:$sortIndex"
}

const val SOURCE_PLATFORM_INSTAGRAM = "instagram"
const val SOURCE_PLATFORM_XIAOHONGSHU = "xiaohongshu"
const val MEDIA_ROLE_PRIMARY = "primary"
const val MEDIA_ROLE_LIVE_STILL = "live_still"
const val MEDIA_ROLE_LIVE_MOTION = "live_motion"
internal val SUPPORTED_SOURCE_PLATFORMS =
    setOf(SOURCE_PLATFORM_INSTAGRAM, SOURCE_PLATFORM_XIAOHONGSHU)
internal val SUPPORTED_MEDIA_ROLES =
    setOf(MEDIA_ROLE_PRIMARY, MEDIA_ROLE_LIVE_STILL, MEDIA_ROLE_LIVE_MOTION)
internal val SOURCE_POST_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")

internal fun requireCanonicalSourceUrl(
    sourcePlatform: String,
    sourcePostId: String,
    value: String,
) {
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443)) {
        "帖子来源链接无效"
    }
    val host = uri.host?.lowercase()
    when (sourcePlatform) {
        SOURCE_PLATFORM_INSTAGRAM ->
            require(
                host == "www.instagram.com" &&
                    Regex("^/(?:p|reel|tv)/${Regex.escape(sourcePostId)}/?$").matches(uri.path) &&
                    uri.query == null &&
                    uri.fragment == null,
            ) {
                "Instagram 规范链接无效"
            }
        SOURCE_PLATFORM_XIAOHONGSHU ->
            require(
                host == "www.xiaohongshu.com" &&
                    uri.path == "/explore/$sourcePostId" &&
                    uri.query == null &&
                    uri.fragment == null,
            ) {
                "小红书规范链接无效"
            }
        else -> error("帖子来源平台无效")
    }
}

class ArchiveException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class ArchiveAttemptStoppedException : Exception()

private fun JSONObject.optionalString(key: String): String? {
    if (isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() }
}

private fun JSONObject.optionalPositiveInt(key: String): Int? {
    if (isNull(key)) return null
    return optInt(key).takeIf { it > 0 }
}

private fun JSONObject.optionalPositiveLong(key: String): Long? {
    if (isNull(key)) return null
    return optLong(key).takeIf { it > 0 }
}
