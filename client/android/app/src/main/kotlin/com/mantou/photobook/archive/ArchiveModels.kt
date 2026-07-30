package com.mantou.photobook.archive

import org.json.JSONArray
import org.json.JSONObject

data class CaptureJob(
    val id: String,
    val sourcePostId: String,
    val status: String,
    val attemptCount: Int,
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
            require(media.isNotEmpty()) { "Instagram 帖子没有媒体" }
            return RemotePost(
                sourcePostId = json.getString("sourcePostId"),
                sourceUrl = json.getString("sourceUrl"),
                authorUsername = json.getString("authorUsername"),
                authorDisplayName = json.getString("authorDisplayName"),
                authorProfileUrl = json.getString("authorProfileUrl"),
                authorAvatarUrl = json.optionalString("authorAvatarUrl"),
                caption = json.optString("caption"),
                publishedAt = json.getLong("publishedAt"),
                locationName = json.optionalString("locationName"),
                media = media.sortedBy { it.sortIndex },
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
) {
    companion object {
        fun fromJson(json: JSONObject): RemoteMedia =
            RemoteMedia(
                sortIndex = json.getInt("sortIndex"),
                mediaType = json.getString("mediaType"),
                url = json.getString("url"),
                width = json.optionalPositiveInt("width"),
                height = json.optionalPositiveInt("height"),
                durationMs = json.optionalPositiveLong("durationMs"),
            )
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
) {
    val id: String = "instagram:$sourcePostId"

    fun operationJson(
        deviceId: String,
        seq: Long,
        now: Long,
        version: Long = now,
    ): String {
        val post =
            JSONObject()
                .put("id", id)
                .put("sourcePostId", sourcePostId)
                .put("sourceUrl", sourceUrl)
                .put("authorUsername", authorUsername)
                .put("authorDisplayName", authorDisplayName)
                .put("authorProfileUrl", authorProfileUrl)
                .put("hasAuthorAvatar", authorAvatarSha256 != null)
                .put("authorAvatarSha256", authorAvatarSha256)
                .put("caption", caption)
                .put("publishedAt", publishedAt)
                .put("locationName", locationName)
                .put("coverMediaId", media.first().id(id))
                .put("mediaCount", media.size)
                .put("savedAt", now)
                .put("updatedAt", now)
        val mediaArray = JSONArray()
        media.forEach { mediaArray.put(it.toSyncJson(id)) }
        return JSONObject()
            .put("deviceId", deviceId)
            .put("seq", seq)
            .put("entityVersion", EntityVersion(version, deviceId, seq).toJson())
            .put("operation", "upsert_post")
            .put("entityId", id)
            .put("createdAt", now)
            .put("payload", JSONObject().put("post", post).put("media", mediaArray))
            .toString()
    }
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
) {
    fun id(postId: String): String = "$postId:$sortIndex"

    fun toSyncJson(postId: String): JSONObject =
        JSONObject()
            .put("id", id(postId))
            .put("postId", postId)
            .put("sortIndex", sortIndex)
            .put("mediaType", mediaType)
            .put("mimeType", mimeType)
            .put("width", width)
            .put("height", height)
            .put("durationMs", durationMs)
            .put("originalSize", originalSize)
            .put("originalSha256", originalSha256)
            .put("thumbnailSha256", thumbnailSha256)
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
