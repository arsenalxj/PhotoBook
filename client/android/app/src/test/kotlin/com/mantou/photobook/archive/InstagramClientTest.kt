package com.mantou.photobook.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstagramClientTest {
    @Test
    fun `anonymous success never reads session`() {
        val sessions = FakeSessions(session())
        val gateway = FakeGateway { InstagramFetchResult(post(), null) }

        val result = InstagramClient(gateway, sessions).fetchPost("Post123")

        assertEquals("Post123", result.sourcePostId)
        assertEquals(0, sessions.readCount)
        assertEquals(1, gateway.calls.size)
        assertNull(gateway.calls.single())
    }

    @Test
    fun `cancelled attempt does not start anonymous request`() {
        val sessions = FakeSessions(session())
        val gateway = FakeGateway { InstagramFetchResult(post(), null) }

        assertThrows(ArchiveAttemptStoppedException::class.java) {
            InstagramClient(gateway, sessions).fetchPost("Post123") { false }
        }

        assertTrue(gateway.calls.isEmpty())
        assertEquals(0, sessions.readCount)
    }

    @Test
    fun `cancellation after anonymous login wall prevents session retry`() {
        var active = true
        val sessions = FakeSessions(session())
        val gateway =
            FakeGateway {
                active = false
                throw ArchiveException("LOGIN_REQUIRED", "需要登录")
            }

        assertThrows(ArchiveAttemptStoppedException::class.java) {
            InstagramClient(gateway, sessions).fetchPost("Post123") { active }
        }

        assertEquals(1, gateway.calls.size)
        assertEquals(0, sessions.readCount)
    }

    @Test
    fun `retryable and unsupported anonymous errors never read session`() {
        for (code in listOf("NETWORK_ERROR", "RATE_LIMITED", "UNSUPPORTED_RESPONSE")) {
            val sessions = FakeSessions(session())
            val gateway = FakeGateway { throw ArchiveException(code, "匿名解析失败") }

            val error = assertThrows(ArchiveException::class.java) {
                InstagramClient(gateway, sessions).fetchPost("Post123")
            }

            assertEquals(code, error.code)
            assertEquals(0, sessions.readCount)
            assertEquals(1, gateway.calls.size)
        }
    }

    @Test
    fun `login required uses ready session media info directly and saves refreshed session`() {
        val original = session(sessionId = "old-session", validatedAt = 100)
        val refreshed = session(sessionId = "new-session", validatedAt = 100)
        val sessions = FakeSessions(original)
        val gateway =
            FakeGateway(
                fetch = { supplied ->
                    assertNull(supplied)
                    throw ArchiveException("LOGIN_REQUIRED", "需要登录")
                },
                fetchMediaInfo = { InstagramFetchResult(post(), refreshed) },
            )

        val result = InstagramClient(gateway, sessions) { 200 }.fetchPost("Post123")

        assertEquals("Post123", result.sourcePostId)
        assertEquals(1, gateway.calls.size)
        assertNull(gateway.calls.single())
        assertEquals(1, gateway.mediaInfoCalls.size)
        assertTrue(gateway.mediaInfoCalls.single() === original)
        assertEquals(1, sessions.saved.size)
        assertEquals(200, sessions.saved.single().validatedAt)
        assertEquals(InstagramSessionStatus.READY, sessions.saved.single().status)
    }

    @Test
    fun `login required media info failure never performs authenticated metadata request`() {
        val sessions = FakeSessions(session())
        val gateway =
            FakeGateway(
                fetch = { supplied ->
                    assertNull(supplied)
                    throw ArchiveException("LOGIN_REQUIRED", "需要登录")
                },
                fetchMediaInfo = {
                    throw ArchiveException("POST_INACCESSIBLE", "登录后仍无法访问")
                },
            )

        val error = assertThrows(ArchiveException::class.java) {
            InstagramClient(gateway, sessions).fetchPost("Post123")
        }

        assertEquals("POST_INACCESSIBLE", error.code)
        assertEquals(1, gateway.calls.size)
        assertNull(gateway.calls.single())
        assertEquals(1, gateway.mediaInfoCalls.size)
        assertEquals(0, sessions.markCount)
    }

    @Test
    fun `ambiguous anonymous response uses ready session media info directly`() {
        val original = session(sessionId = "old-session", validatedAt = 100)
        val refreshed = session(sessionId = "new-session", validatedAt = 100)
        val sessions = FakeSessions(original)
        val gateway =
            FakeGateway(
                fetch = { supplied ->
                    assertNull(supplied)
                    InstagramSessionProbeRequired
                },
                fetchMediaInfo = { InstagramFetchResult(post(), refreshed) },
            )

        val result = InstagramClient(gateway, sessions) { 200 }.fetchPost("Post123")

        assertEquals("Post123", result.sourcePostId)
        assertEquals(1, gateway.calls.size)
        assertNull(gateway.calls.single())
        assertEquals(1, gateway.mediaInfoCalls.size)
        assertTrue(gateway.mediaInfoCalls.single() === original)
        assertEquals(1, sessions.saved.size)
    }

    @Test
    fun `ambiguous session probe network failure identifies authenticated stage`() {
        val sessions = FakeSessions(session())
        val gateway =
            FakeGateway(
                fetch = { InstagramSessionProbeRequired },
                fetchMediaInfo = {
                    throw ArchiveException("NETWORK_ERROR", "连接 Instagram 失败")
                },
            )

        val error = assertThrows(ArchiveException::class.java) {
            InstagramClient(gateway, sessions).fetchPost("Post123")
        }

        assertEquals("NETWORK_ERROR", error.code)
        assertTrue(error.message.contains("已使用 Instagram 登录状态请求帖子详情"))
        assertEquals(1, gateway.mediaInfoCalls.size)
        assertEquals(0, sessions.markCount)
    }

    @Test
    fun `ambiguous anonymous response without ready session explains required login`() {
        val noSessionGateway = FakeGateway { InstagramSessionProbeRequired }
        val noSessionError = assertThrows(ArchiveException::class.java) {
            InstagramClient(noSessionGateway, FakeSessions(null)).fetchPost("Post123")
        }

        assertEquals("LOGIN_REQUIRED", noSessionError.code)
        assertTrue(noSessionError.message.contains("确认帖子状态"))
        assertEquals(1, noSessionGateway.calls.size)
        assertTrue(noSessionGateway.mediaInfoCalls.isEmpty())

        val staleGateway = FakeGateway { InstagramSessionProbeRequired }
        val staleError = assertThrows(ArchiveException::class.java) {
            InstagramClient(staleGateway, FakeSessions(session().needsRefresh())).fetchPost("Post123")
        }

        assertEquals("LOGIN_REQUIRED", staleError.code)
        assertTrue(staleError.message.contains("已失效"))
        assertEquals(1, staleGateway.calls.size)
        assertTrue(staleGateway.mediaInfoCalls.isEmpty())
    }

    @Test
    fun `cancellation after ambiguous response prevents session probe`() {
        var active = true
        val sessions = FakeSessions(session())
        val gateway =
            FakeGateway {
                active = false
                InstagramSessionProbeRequired
            }

        assertThrows(ArchiveAttemptStoppedException::class.java) {
            InstagramClient(gateway, sessions).fetchPost("Post123") { active }
        }

        assertEquals(1, gateway.calls.size)
        assertTrue(gateway.mediaInfoCalls.isEmpty())
        assertEquals(0, sessions.readCount)
    }

    @Test
    fun `cancellation after authenticated response does not save refreshed session`() {
        var active = true
        val sessions = FakeSessions(session())
        val refreshed = session(sessionId = "new-session")
        val gateway =
            FakeGateway(
                fetch = { throw ArchiveException("LOGIN_REQUIRED", "需要登录") },
                fetchMediaInfo = {
                    active = false
                    InstagramFetchResult(post(), refreshed)
                },
            )

        assertThrows(ArchiveAttemptStoppedException::class.java) {
            InstagramClient(gateway, sessions).fetchPost("Post123") { active }
        }

        assertEquals(1, gateway.calls.size)
        assertEquals(1, gateway.mediaInfoCalls.size)
        assertTrue(sessions.saved.isEmpty())
        assertEquals(0, sessions.markCount)
    }

    @Test
    fun `cancellation after authenticated login error preserves session state`() {
        var active = true
        val sessions = FakeSessions(session())
        val gateway =
            FakeGateway(
                fetch = { throw ArchiveException("LOGIN_REQUIRED", "需要登录") },
                fetchMediaInfo = {
                    active = false
                    throw ArchiveException("LOGIN_REQUIRED", "Session 失效")
                },
            )

        assertThrows(ArchiveAttemptStoppedException::class.java) {
            InstagramClient(gateway, sessions).fetchPost("Post123") { active }
        }

        assertEquals(0, sessions.markCount)
        assertEquals(InstagramSessionStatus.READY, sessions.current?.status)
    }

    @Test
    fun `missing or stale session does not run authenticated request`() {
        val noSessionGateway = loginRequiredGateway()
        val noSessionError = assertThrows(ArchiveException::class.java) {
            InstagramClient(noSessionGateway, FakeSessions(null)).fetchPost("Post123")
        }
        assertEquals("LOGIN_REQUIRED", noSessionError.code)
        assertEquals(1, noSessionGateway.calls.size)

        val staleGateway = loginRequiredGateway()
        val staleSessions = FakeSessions(session().needsRefresh())
        val staleError = assertThrows(ArchiveException::class.java) {
            InstagramClient(staleGateway, staleSessions).fetchPost("Post123")
        }
        assertEquals("LOGIN_REQUIRED", staleError.code)
        assertTrue(staleError.message.contains("已失效"))
        assertEquals(1, staleGateway.calls.size)
    }

    @Test
    fun `authenticated login error marks session stale`() {
        val sessions = FakeSessions(session())
        val gateway =
            FakeGateway(
                fetch = { throw ArchiveException("LOGIN_REQUIRED", "需要登录") },
                fetchMediaInfo = {
                    throw ArchiveException("LOGIN_REQUIRED", "Session 失效")
                },
            )

        val error = assertThrows(ArchiveException::class.java) {
            InstagramClient(gateway, sessions).fetchPost("Post123")
        }

        assertEquals("LOGIN_REQUIRED", error.code)
        assertEquals(1, sessions.markCount)
        assertEquals(InstagramSessionStatus.NEEDS_REFRESH, sessions.current?.status)
    }

    @Test
    fun `authenticated non login error preserves session`() {
        val sessions = FakeSessions(session())
        val gateway =
            FakeGateway(
                fetch = { throw ArchiveException("LOGIN_REQUIRED", "需要登录") },
                fetchMediaInfo = {
                    throw ArchiveException("RATE_LIMITED", "请求受限")
                },
            )

        val error = assertThrows(ArchiveException::class.java) {
            InstagramClient(gateway, sessions).fetchPost("Post123")
        }

        assertEquals("RATE_LIMITED", error.code)
        assertEquals(0, sessions.markCount)
        assertEquals(InstagramSessionStatus.READY, sessions.current?.status)
    }

    @Test
    fun `validation does not overwrite session before explicit save`() {
        val oldSession = session(sessionId = "old-session")
        val validatedSession = session(sessionId = "validated-session")
        val sessions = FakeSessions(oldSession)
        val gateway =
            object : InstagramGateway {
                override fun validateSession(
                    cookieHeader: String,
                    validatedAt: Long,
                ): InstagramSession = validatedSession

                override fun fetchPost(
                    shortcode: String,
                    session: InstagramSession?,
                ): InstagramFetchOutcome = throw UnsupportedOperationException()

                override fun fetchPostMediaInfo(
                    shortcode: String,
                    session: InstagramSession,
                ): InstagramFetchResult = throw UnsupportedOperationException()
            }
        val client = InstagramClient(gateway, sessions)

        val validated = client.validateSession("sessionid=secret; csrftoken=csrf")

        assertTrue(validated === validatedSession)
        assertTrue(sessions.current === oldSession)
        assertTrue(sessions.saved.isEmpty())

        client.saveSession(validated)

        assertTrue(sessions.current === validatedSession)
        assertEquals(1, sessions.saved.size)
    }

    @Test
    fun `manual cookie import validates before replacing previous session`() {
        val oldSession = session(sessionId = "old-session")
        val validatedSession = session(username = "manual_user", sessionId = "new-session")
        val sessions = FakeSessions(oldSession)
        val gateway =
            object : InstagramGateway {
                override fun validateSession(
                    cookieHeader: String,
                    validatedAt: Long,
                ): InstagramSession {
                    if (cookieHeader.startsWith("invalid")) {
                        throw ArchiveException("LOGIN_VALIDATION_FAILED", "Cookie 无效")
                    }
                    return validatedSession
                }

                override fun fetchPost(
                    shortcode: String,
                    session: InstagramSession?,
                ): InstagramFetchOutcome = throw UnsupportedOperationException()

                override fun fetchPostMediaInfo(
                    shortcode: String,
                    session: InstagramSession,
                ): InstagramFetchResult = throw UnsupportedOperationException()
            }
        val client = InstagramClient(gateway, sessions)

        assertThrows(ArchiveException::class.java) {
            client.validateAndSaveSession("invalid-cookie")
        }
        assertTrue(sessions.current === oldSession)
        assertTrue(sessions.saved.isEmpty())

        val imported = client.validateAndSaveSession(
            "sessionid=session-secret; csrftoken=csrf-value",
        )

        assertTrue(imported === validatedSession)
        assertTrue(sessions.current === validatedSession)
        assertEquals(1, sessions.saved.size)
    }

    @Test
    fun `ready session exports sorted cookie header`() {
        val sessions = FakeSessions(session(sessionId = "session-secret"))

        val header = InstagramClient(FakeGateway { throw UnsupportedOperationException() }, sessions)
            .copyableCookieHeader()

        assertEquals("csrftoken=csrf-value; sessionid=session-secret", header)
        assertEquals(1, sessions.readCount)
    }

    @Test
    fun `missing or stale session cannot export cookies`() {
        val missing = assertThrows(ArchiveException::class.java) {
            InstagramClient(FakeGateway { throw UnsupportedOperationException() }, FakeSessions(null))
                .copyableCookieHeader()
        }
        val stale = assertThrows(ArchiveException::class.java) {
            InstagramClient(
                FakeGateway { throw UnsupportedOperationException() },
                FakeSessions(session().needsRefresh()),
            ).copyableCookieHeader()
        }

        assertEquals("LOGIN_REQUIRED", missing.code)
        assertEquals("LOGIN_REQUIRED", stale.code)
    }

    @Test
    fun `session string representation redacts cookies`() {
        val value = session(sessionId = "must-not-appear")

        assertFalse(value.toString().contains("must-not-appear"))
        assertTrue(value.toString().contains("REDACTED"))
    }

    private fun loginRequiredGateway(): FakeGateway =
        FakeGateway { throw ArchiveException("LOGIN_REQUIRED", "需要登录") }

    private fun session(
        username: String = "archive_user",
        sessionId: String = "session-value",
        validatedAt: Long = 100,
    ): InstagramSession =
        InstagramSession.fromPythonJson(
            """{"username":"$username","cookies":{"sessionid":"$sessionId","csrftoken":"csrf-value"}}""",
            validatedAt,
        )

    private fun post(): RemotePost =
        RemotePost(
            sourcePostId = "Post123",
            sourceUrl = "https://www.instagram.com/p/Post123/",
            authorUsername = "author",
            authorDisplayName = "Author",
            authorProfileUrl = "https://www.instagram.com/author/",
            authorAvatarUrl = null,
            caption = "",
            publishedAt = 1,
            locationName = null,
            media =
                listOf(
                    RemoteMedia(
                        sortIndex = 0,
                        mediaType = "image",
                        url = "https://cdn.example/image.jpg",
                        width = 100,
                        height = 100,
                        durationMs = null,
                    ),
                ),
        )

    private class FakeGateway(
        private val fetchMediaInfo: (InstagramSession) -> InstagramFetchResult = {
            throw UnsupportedOperationException()
        },
        private val fetch: (InstagramSession?) -> InstagramFetchOutcome,
    ) : InstagramGateway {
        val calls = mutableListOf<InstagramSession?>()
        val mediaInfoCalls = mutableListOf<InstagramSession>()

        override fun validateSession(cookieHeader: String, validatedAt: Long): InstagramSession =
            throw UnsupportedOperationException()

        override fun fetchPost(
            shortcode: String,
            session: InstagramSession?,
        ): InstagramFetchOutcome {
            calls += session
            return fetch(session)
        }

        override fun fetchPostMediaInfo(
            shortcode: String,
            session: InstagramSession,
        ): InstagramFetchResult {
            mediaInfoCalls += session
            return fetchMediaInfo(session)
        }
    }

    private class FakeSessions(initial: InstagramSession?) : InstagramSessionRepository {
        var current = initial
        var readCount = 0
        var markCount = 0
        val saved = mutableListOf<InstagramSession>()

        override fun read(): InstagramSession? {
            readCount += 1
            return current
        }

        override fun save(session: InstagramSession) {
            current = session
            saved += session
        }

        override fun markNeedsRefresh() {
            markCount += 1
            current = current?.needsRefresh()
        }

        override fun clear() {
            current = null
        }
    }
}
