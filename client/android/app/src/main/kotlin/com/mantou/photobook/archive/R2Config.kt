package com.mantou.photobook.archive

import java.net.URI
import java.security.MessageDigest
import org.json.JSONObject

data class R2Config(
    val endpoint: String,
    val bucket: String,
    val prefix: String,
    val accessKeyId: String,
    val secretAccessKey: String,
) {
    val repositoryId: String by lazy {
        sha256("$endpoint\n$bucket\n$prefix")
    }

    val basePrefix: String
        get() = prefix

    fun toJson(): String =
        JSONObject()
            .put("endpoint", endpoint)
            .put("bucket", bucket)
            .put("prefix", prefix)
            .put("accessKeyId", accessKeyId)
            .put("secretAccessKey", secretAccessKey)
            .toString()

    fun summary(): Map<String, Any> =
        mapOf(
            "endpoint" to endpoint,
            "bucket" to bucket,
            "prefix" to prefix,
            "accessKeyIdHint" to maskAccessKey(accessKeyId),
        )

    companion object {
        private val bucketPattern = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")

        fun fromMap(raw: Map<*, *>): R2Config =
            normalize(
                endpoint = raw["endpoint"]?.toString().orEmpty(),
                bucket = raw["bucket"]?.toString().orEmpty(),
                prefix = raw["prefix"]?.toString().orEmpty(),
                accessKeyId = raw["accessKeyId"]?.toString().orEmpty(),
                secretAccessKey = raw["secretAccessKey"]?.toString().orEmpty(),
            )

        fun fromJson(raw: String): R2Config {
            val json = JSONObject(raw)
            return normalize(
                endpoint = json.getString("endpoint"),
                bucket = json.getString("bucket"),
                prefix = json.getString("prefix"),
                accessKeyId = json.getString("accessKeyId"),
                secretAccessKey = json.getString("secretAccessKey"),
            )
        }

        private fun normalize(
            endpoint: String,
            bucket: String,
            prefix: String,
            accessKeyId: String,
            secretAccessKey: String,
        ): R2Config {
            val uri =
                runCatching { URI(endpoint.trim()) }.getOrElse {
                    throw IllegalArgumentException("R2 endpoint 格式无效")
                }
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "R2 endpoint 必须使用 HTTPS"
            }
            require(
                !uri.host.isNullOrBlank() &&
                    uri.userInfo == null &&
                    uri.query == null &&
                    uri.fragment == null,
            ) {
                "R2 endpoint 格式无效"
            }
            require(uri.path.isNullOrEmpty() || uri.path == "/") {
                "R2 endpoint 不能包含路径"
            }
            val normalizedEndpoint =
                buildString {
                    append("https://")
                    append(uri.host.lowercase())
                    if (uri.port > 0 && uri.port != 443) append(":${uri.port}")
                }
            val normalizedBucket = bucket.trim().lowercase()
            require(bucketPattern.matches(normalizedBucket) && !normalizedBucket.contains("..")) {
                "R2 bucket 名称无效"
            }
            val normalizedPrefix = prefix.trim().trim('/').ifEmpty { "photobook" }
            require(
                normalizedPrefix.length <= 256 &&
                    normalizedPrefix.split('/').all { it.isNotBlank() && it != "." && it != ".." } &&
                    !normalizedPrefix.contains('\\'),
            ) {
                "R2 prefix 无效"
            }
            require(accessKeyId.isNotBlank()) { "Access Key ID 不能为空" }
            require(secretAccessKey.isNotBlank()) { "Secret Access Key 不能为空" }
            return R2Config(
                endpoint = normalizedEndpoint,
                bucket = normalizedBucket,
                prefix = normalizedPrefix,
                accessKeyId = accessKeyId.trim(),
                secretAccessKey = secretAccessKey,
            )
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun maskAccessKey(value: String): String =
            when {
                value.length <= 4 -> "****"
                else -> "${value.take(2)}****${value.takeLast(2)}"
            }
    }
}
