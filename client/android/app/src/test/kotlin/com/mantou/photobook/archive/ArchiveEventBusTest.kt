package com.mantou.photobook.archive

import android.os.Looper
import io.flutter.plugin.common.EventChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArchiveEventBusTest {
    @After
    fun tearDown() {
        ArchiveEventBus.onCancel(null)
    }

    @Test
    fun `job changed event is only an invalidation notification`() {
        var emitted: Any? = null
        ArchiveEventBus.onListen(
            null,
            object : EventChannel.EventSink {
                override fun success(event: Any?) {
                    emitted = event
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) = Unit

                override fun endOfStream() = Unit
            },
        )

        ArchiveEventBus.emitJobChanged()
        shadowOf(Looper.getMainLooper()).idle()

        val event = emitted as Map<*, *>
        assertEquals("jobChanged", event["type"])
        assertTrue(event["timestamp"] is Long)
        assertFalse(event.containsKey("job"))
        assertFalse(event.containsKey("progress"))
    }
}
