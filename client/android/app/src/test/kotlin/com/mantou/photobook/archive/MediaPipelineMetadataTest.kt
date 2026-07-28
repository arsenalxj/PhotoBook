package com.mantou.photobook.archive

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPipelineMetadataTest {
    @Test
    fun `quarter turn swaps encoded video dimensions`() {
        assertEquals(
            Pair(1080, 1920),
            MediaPipeline.displayVideoDimensions(1920, 1080, 90),
        )
        assertEquals(
            Pair(1080, 1920),
            MediaPipeline.displayVideoDimensions(1920, 1080, 270),
        )
        assertEquals(
            Pair(1080, 1920),
            MediaPipeline.displayVideoDimensions(1920, 1080, -90),
        )
    }

    @Test
    fun `zero and half turn keep encoded video dimensions`() {
        assertEquals(
            Pair(1920, 1080),
            MediaPipeline.displayVideoDimensions(1920, 1080, 0),
        )
        assertEquals(
            Pair(1920, 1080),
            MediaPipeline.displayVideoDimensions(1920, 1080, 180),
        )
    }
}
