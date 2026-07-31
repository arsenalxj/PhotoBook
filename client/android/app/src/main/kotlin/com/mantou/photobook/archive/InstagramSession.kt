package com.mantou.photobook.archive

import org.json.JSONObject

internal enum class InstagramSessionStatus(val wireValue: String) {
    READY("ready"),
    NEEDS_REFRESH("needs_refresh"),
    ;

    companion object {
        fun fromWire(value: String): InstagramSessionStatus =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Instagram Session 状态无效")
    }
}

internal class InstagramSession private constructor(
    val username: String,
    private val cookies: Map<String, String>,
    val validatedAt: Long,
    val status: InstagramSessionStatus,
) {
    init {
        require(username.isNotBlank()) { "Instagram 用户名为空" }
        require(validatedAt > 0) { "Instagram Session 验证时间无效" }
        require(cookies["sessionid"].orEmpty().isNotBlank()) { "Instagram Session 缺少 sessionid" }
        require(cookies["csrftoken"].orEmpty().isNotBlank()) { "Instagram Session 缺少 csrftoken" }
    }

    fun summary(): Map<String, Any> =
        mapOf(
            "status" to status.wireValue,
            "username" to username,
            "validatedAt" to validatedAt,
        )

    fun toStoredJson(): String =
        JSONObject()
            .put("username", username)
            .put("cookies", cookiesJson())
            .put("validatedAt", validatedAt)
            .put("status", status.wireValue)
            .toString()

    fun toPythonJson(): String =
        JSONObject()
            .put("username", username)
            .put("cookies", cookiesJson())
            .toString()

    fun cookieHeader(): String =
        cookies.toSortedMap().entries.joinToString("; ") { (name, value) -> "$name=$value" }

    fun needsRefresh(): InstagramSession =
        InstagramSession(username, cookies, validatedAt, InstagramSessionStatus.NEEDS_REFRESH)

    fun refreshedAt(timestamp: Long): InstagramSession =
        InstagramSession(username, cookies, timestamp, InstagramSessionStatus.READY)

    fun hasSameAccount(other: InstagramSession): Boolean = username == other.username

    override fun toString(): String =
        "InstagramSession(username=$username, status=${status.wireValue}, " +
            "validatedAt=$validatedAt, cookies=[REDACTED])"

    private fun cookiesJson(): JSONObject =
        JSONObject().apply {
            cookies.toSortedMap().forEach { (name, value) -> put(name, value) }
        }

    companion object {
        fun fromStoredJson(raw: String): InstagramSession {
            val json = JSONObject(raw)
            return InstagramSession(
                username = json.getString("username").trim(),
                cookies = cookiesFromJson(json.getJSONObject("cookies")),
                validatedAt = json.getLong("validatedAt"),
                status = InstagramSessionStatus.fromWire(json.getString("status")),
            )
        }

        fun fromPythonJson(raw: String, validatedAt: Long): InstagramSession {
            val json = JSONObject(raw)
            return InstagramSession(
                username = json.getString("username").trim(),
                cookies = cookiesFromJson(json.getJSONObject("cookies")),
                validatedAt = validatedAt,
                status = InstagramSessionStatus.READY,
            )
        }

        private fun cookiesFromJson(json: JSONObject): Map<String, String> =
            buildMap {
                json.keys().forEach { name ->
                    require(name.isNotBlank()) { "Instagram Cookie 名称为空" }
                    put(name, json.getString(name))
                }
            }
    }
}
