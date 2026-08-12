package com.mantou.photobook.archive

import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class R2Connection(
    val endpoint: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
) {
    val connectionId: String by lazy { sha256("$endpoint\n$bucket") }

    fun resolve(prefix: String): R2Config =
        R2Config(
            endpoint = endpoint,
            bucket = bucket,
            prefix = normalizePrefix(prefix),
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
        )

    fun toJsonObject(): JSONObject =
        JSONObject()
            .put("endpoint", endpoint)
            .put("bucket", bucket)
            .put("accessKeyId", accessKeyId)
            .put("secretAccessKey", secretAccessKey)

    fun summary(targetCount: Int): Map<String, Any> =
        mapOf(
            "connectionId" to connectionId,
            "endpoint" to endpoint,
            "bucket" to bucket,
            "accessKeyIdHint" to maskAccessKey(accessKeyId),
            "targetCount" to targetCount,
        )

    companion object {
        fun fromMap(raw: Map<*, *>): R2Connection =
            normalize(
                endpoint = raw["endpoint"]?.toString().orEmpty(),
                bucket = raw["bucket"]?.toString().orEmpty(),
                accessKeyId = raw["accessKeyId"]?.toString().orEmpty(),
                secretAccessKey = raw["secretAccessKey"]?.toString().orEmpty(),
            )

        fun fromJsonObject(json: JSONObject): R2Connection =
            normalize(
                endpoint = json.getString("endpoint"),
                bucket = json.getString("bucket"),
                accessKeyId = json.getString("accessKeyId"),
                secretAccessKey = json.getString("secretAccessKey"),
            )

        private fun normalize(
            endpoint: String,
            bucket: String,
            accessKeyId: String,
            secretAccessKey: String,
        ): R2Connection {
            val normalizedEndpoint = normalizeEndpoint(endpoint)
            val normalizedBucket = normalizeBucket(bucket)
            require(accessKeyId.isNotBlank()) { "Access Key ID 不能为空" }
            require(secretAccessKey.isNotBlank()) { "Secret Access Key 不能为空" }
            return R2Connection(
                endpoint = normalizedEndpoint,
                bucket = normalizedBucket,
                accessKeyId = accessKeyId.trim(),
                secretAccessKey = secretAccessKey,
            )
        }
    }
}

data class R2BackupTarget(
    val targetId: String,
    val connectionId: String,
    val name: String,
    val prefix: String,
) {
    fun toJsonObject(): JSONObject =
        JSONObject()
            .put("connectionId", connectionId)
            .put("name", name)
            .put("prefix", prefix)

    fun summary(connection: R2Connection): Map<String, Any> =
        mapOf(
            "targetId" to targetId,
            "backupTargetId" to targetId,
            "connectionId" to connectionId,
            "name" to name,
            "endpoint" to connection.endpoint,
            "bucket" to connection.bucket,
            "prefix" to prefix,
        )

    companion object {
        fun create(connection: R2Connection, name: String, prefix: String): R2BackupTarget {
            val normalizedName = name.trim()
            require(normalizedName.isNotEmpty() && normalizedName.length <= 40) {
                "备份位置名称必须为 1 到 40 个字符"
            }
            require(normalizedName.none(Char::isISOControl)) { "备份位置名称无效" }
            val normalizedPrefix = normalizePrefix(prefix)
            return R2BackupTarget(
                targetId = backupTargetId(connection.endpoint, connection.bucket, normalizedPrefix),
                connectionId = connection.connectionId,
                name = normalizedName,
                prefix = normalizedPrefix,
            )
        }

        fun fromJsonObject(json: JSONObject, connection: R2Connection): R2BackupTarget {
            require(json.getString("connectionId") == connection.connectionId) {
                "R2 备份位置所属连接无效"
            }
            return create(
                connection = connection,
                name = json.getString("name"),
                prefix = json.getString("prefix"),
            )
        }
    }
}

data class R2Settings(
    val connections: List<R2Connection>,
    val targets: List<R2BackupTarget>,
) {
    init {
        require(connections.map { it.connectionId }.toSet().size == connections.size) {
            "R2 连接重复"
        }
        require(targets.map { it.targetId }.toSet().size == targets.size) {
            "R2 备份位置重复"
        }
        val byId = connections.associateBy(R2Connection::connectionId)
        targets.forEach { target ->
            val connection = byId[target.connectionId]
                ?: throw IllegalArgumentException("R2 备份位置所属连接不存在")
            require(
                target.targetId == backupTargetId(connection.endpoint, connection.bucket, target.prefix),
            ) { "R2 备份位置标识无效" }
        }
    }

    fun resolve(targetId: String): R2Config? {
        val target = targets.firstOrNull { it.targetId == targetId } ?: return null
        val connection = connections.firstOrNull { it.connectionId == target.connectionId } ?: return null
        return connection.resolve(target.prefix)
    }

    fun connection(connectionId: String): R2Connection? =
        connections.firstOrNull { it.connectionId == connectionId }

    fun target(targetId: String): R2BackupTarget? = targets.firstOrNull { it.targetId == targetId }

    fun summary(): Map<String, Any> {
        val byId = connections.associateBy(R2Connection::connectionId)
        return mapOf(
            "connections" to connections.map { connection ->
                connection.summary(targets.count { it.connectionId == connection.connectionId })
            },
            "targets" to targets.map { target ->
                target.summary(checkNotNull(byId[target.connectionId]))
            },
        )
    }

    fun toJson(): String =
        JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("connections", JSONArray().apply { connections.forEach { put(it.toJsonObject()) } })
            .put("targets", JSONArray().apply { targets.forEach { put(it.toJsonObject()) } })
            .toString()

    companion object {
        const val SCHEMA_VERSION = 2
        val EMPTY = R2Settings(emptyList(), emptyList())

        fun fromJson(raw: String): R2Settings {
            val json = JSONObject(raw)
            if (!json.has("schemaVersion") && json.has("endpoint")) {
                val legacy = R2Config.fromJson(raw)
                val connection =
                    R2Connection(
                        endpoint = legacy.endpoint,
                        bucket = legacy.bucket,
                        accessKeyId = legacy.accessKeyId,
                        secretAccessKey = legacy.secretAccessKey,
                    )
                return R2Settings(
                    connections = listOf(connection),
                    targets = listOf(R2BackupTarget.create(connection, "默认备份", legacy.prefix)),
                )
            }
            require(json.getInt("schemaVersion") == SCHEMA_VERSION) { "R2 配置版本不受支持" }
            val connectionsJson = json.getJSONArray("connections")
            val connections =
                List(connectionsJson.length()) { index ->
                    R2Connection.fromJsonObject(connectionsJson.getJSONObject(index))
                }
            val byId = connections.associateBy(R2Connection::connectionId)
            val targetsJson = json.getJSONArray("targets")
            val targets =
                List(targetsJson.length()) { index ->
                    val targetJson = targetsJson.getJSONObject(index)
                    val connectionId = targetJson.getString("connectionId")
                    R2BackupTarget.fromJsonObject(
                        targetJson,
                        byId[connectionId]
                            ?: throw IllegalArgumentException("R2 备份位置所属连接不存在"),
                    )
                }
            return R2Settings(connections, targets)
        }
    }
}

data class R2Config(
    val endpoint: String,
    val bucket: String,
    val prefix: String,
    val accessKeyId: String,
    val secretAccessKey: String,
) {
    val backupTargetId: String by lazy { backupTargetId(endpoint, bucket, prefix) }
    val basePrefix: String get() = prefix

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
            "backupTargetId" to backupTargetId,
        )

    companion object {
        fun fromMap(raw: Map<*, *>): R2Config {
            val connection = R2Connection.fromMap(raw)
            return connection.resolve(raw["prefix"]?.toString().orEmpty())
        }

        fun fromJson(raw: String): R2Config = fromMap(JSONObject(raw).toMap())
    }
}

private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith(::opt)

private fun normalizeEndpoint(value: String): String {
    val uri = runCatching { URI(value.trim()) }.getOrElse {
        throw IllegalArgumentException("R2 endpoint 格式无效")
    }
    require(uri.scheme.equals("https", ignoreCase = true)) { "R2 endpoint 必须使用 HTTPS" }
    require(
        !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null,
    ) { "R2 endpoint 格式无效" }
    require(uri.path.isNullOrEmpty() || uri.path == "/") { "R2 endpoint 不能包含路径" }
    return buildString {
        append("https://")
        append(uri.host.lowercase())
        if (uri.port > 0 && uri.port != 443) append(":${uri.port}")
    }
}

private fun normalizeBucket(value: String): String {
    val normalized = value.trim().lowercase()
    require(BUCKET_PATTERN.matches(normalized) && !normalized.contains("..")) {
        "R2 bucket 名称无效"
    }
    return normalized
}

private fun normalizePrefix(value: String): String {
    val normalized = value.trim().trim('/').ifEmpty { "photobook" }
    require(
        normalized.length <= 256 &&
            normalized.split('/').all { it.isNotBlank() && it != "." && it != ".." } &&
            !normalized.contains('\\'),
    ) { "R2 prefix 无效" }
    return normalized
}

private fun backupTargetId(endpoint: String, bucket: String, prefix: String): String =
    sha256("$endpoint\n$bucket\n$prefix")

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun maskAccessKey(value: String): String =
    if (value.length <= 4) "****" else "${value.take(2)}****${value.takeLast(2)}"

private val BUCKET_PATTERN = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
