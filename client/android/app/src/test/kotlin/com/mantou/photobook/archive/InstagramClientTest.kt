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
    fun `non login anonymous error never reads or retries session`() {
        val sessions = FakeSessions(session())
        val gateway = FakeGateway { throw ArchiveException("NETWORK_ERROR", "网络失败") }

        val error = assertThrows(ArchiveException::class.java) {
            InstagramClient(gateway, sessions).fetchPost("Post123")
        }

        assertEquals("NETWORK_ERROR", error.code)
        assertEquals(0, sessions.readCount)
        assertEquals(1, gateway.calls.size)
    }

    @Test
    fun `login required retries once and saves refreshed session`() {
        val original = session(sessionId = "old-session", validatedAt = 100)
        val refreshed = session(sessionId = "new-session", validatedAt = 100)
        val sessions = FakeSessions(original)
        val gateway =
            FakeGateway { supplied ->
                if (supplied == null) throw ArchiveException("LOGIN_REQUIRED", "需要登录")
                InstagramFetchResult(post(), refreshed)
            }

        val result = InstagramClient(gateway, sessions) { 200 }.fetchPost("Post123")

        assertEquals("Post123", result.sourcePostId)
        assertEquals(2, gateway.calls.size)
        assertTrue(gateway.calls[1] === original)
        assertEquals(1, sessions.saved.size)
        assertEquals(200, sessions.saved.single().validatedAt)
        assertEquals(InstagramSessionStatus.READY, sessions.saved.single().status)
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
            FakeGateway { supplied ->
                if (supplied == null) throw ArchiveException("LOGIN_REQUIRED", "需要登录")
                throw ArchiveException("LOGIN_REQUIRED", "Session 失效")
            }

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
            FakeGateway { supplied ->
                if (supplied == null) throw ArchiveException("LOGIN_REQUIRED", "需要登录")
                throw ArchiveException("RATE_LIMITED", "请求受限")
            }

        val error = assertThrows(ArchiveException::class.java) {
            InstagramClient(gateway, sessions).fetchPost("Post123")
        }

        assertEquals("RATE_LIMITED", error.code)
        assertEquals(0, sessions.markCount)
        assertEquals(InstagramSessionStatus.READY, sessions.current?.status)
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
        private val fetch: (InstagramSession?) -> InstagramFetchResult,
    ) : InstagramGateway {
        val calls = mutableListOf<InstagramSession?>()

        override fun validateSession(cookieHeader: String, validatedAt: Long): InstagramSession =
            throw UnsupportedOperationException()

        override fun fetchPost(
            shortcode: String,
            session: InstagramSession?,
        ): InstagramFetchResult {
            calls += session
            return fetch(session)
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
