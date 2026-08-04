package com.newoether.agora.ui.chat.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageItemSegmentsTest {

    @Test
    fun streamingSegmentAnimatesOnlyOnItsFirstSessionAppearance() {
        val registry = SegmentAppearanceRegistry()
        val key = "message:timeline:0"

        assertTrue(registry.shouldAnimate(key, isStreaming = true))
        registry.markSeen(key)
        assertFalse(registry.shouldAnimate(key, isStreaming = true))
    }

    @Test
    fun historicalSegmentNeverReplaysAnEntrance() {
        val registry = SegmentAppearanceRegistry()

        assertFalse(
            registry.shouldAnimate(
                key = "message:timeline:0",
                isStreaming = false,
            )
        )
    }

    @Test
    fun segmentContainerAndCardBodyHaveIndependentFirstAppearances() {
        val registry = SegmentAppearanceRegistry()
        val segmentKey = "message:timeline:0"
        val cardKey = "$segmentKey:card"

        registry.markSeen(segmentKey)

        assertFalse(registry.shouldAnimate(segmentKey, isStreaming = true))
        assertTrue(registry.shouldAnimate(cardKey, isStreaming = true))
    }
}
