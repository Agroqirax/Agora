package com.newoether.agora.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendBoundaryPolicyTest {
    @Test
    fun `idle pending guidance drains before a newer direct send`() {
        assertTrue(mustDrainPendingQueueBeforeDirect(isGenerating = false, pendingQueueSize = 1))
        assertFalse(mustDrainPendingQueueBeforeDirect(isGenerating = false, pendingQueueSize = 0))
        assertFalse(mustDrainPendingQueueBeforeDirect(isGenerating = true, pendingQueueSize = 1))
    }

    @Test
    fun `queued acceptance keeps attachment ownership until durable drain`() {
        assertFalse(SendAcceptance.Queued("queued", "conversation").hasDurableAttachmentOwner())
        assertTrue(SendAcceptance.Direct("message", "conversation").hasDurableAttachmentOwner())
    }
}
