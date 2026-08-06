package com.newoether.agora.api.ollama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OllamaTokenUsageTest {
    @Test
    fun promptAndOutputAreRecordedWithoutInventingCacheBreakdown() {
        val usage = OllamaStreamResponse(
            done = true,
            promptEvalCount = 12,
            evalCount = 8,
        ).toTokenUsage()

        assertEquals(20, usage.totalTokenCount)
        assertEquals(12, usage.inputTokenCount)
        assertNull(usage.cachedInputTokenCount)
        assertNull(usage.uncachedInputTokenCount)
        assertEquals(8, usage.outputTokenCount)
    }
}
