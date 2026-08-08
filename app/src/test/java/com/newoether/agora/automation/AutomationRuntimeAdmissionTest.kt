package com.newoether.agora.automation

import com.newoether.agora.viewmodel.ConversationGenerationState
import com.newoether.agora.viewmodel.QueuedSend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuntimeAdmissionTest {
    @Test
    fun idleConversation_entersTheExactNormalAcceptedInputContract() = runBlocking {
        val state = ConversationGenerationState("conversation")

        val decision = AutomationRuntimeAdmission.request(state, "run", "automation-send")

        val accepted = decision as AutomationRuntimeAdmission.Decision.Accepted
        assertSame(state, accepted.state)
        assertEquals("conversation", accepted.inputEffect.identity.conversationId)
        assertEquals("run", accepted.inputEffect.identity.runId)
        assertEquals("automation-send", accepted.inputEffect.identity.effectId)
        assertTrue(state.generating.value)
        assertTrue(state.inputPersisted(accepted.inputEffect.identity))
        assertEquals("run", state.currentRunId())
        assertTrue(state.endGeneration(accepted.inputEffect.identity.ownerToken))
    }

    @Test
    fun activeConversation_returnsBusyWithoutChangingTheCurrentRun() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "active-run")

        val decision = AutomationRuntimeAdmission.request(state, "new-run", "automation-send")

        assertSame(AutomationRuntimeAdmission.Decision.Busy, decision)
        assertEquals("active-run", state.currentRunId())
        assertTrue(state.endGeneration(token))
    }

    @Test
    fun uninstalledAutomationClaim_canBeAbandonedByItsExactEffectIdentity() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val accepted = AutomationRuntimeAdmission.request(state, "run", "automation-send")
            as AutomationRuntimeAdmission.Decision.Accepted

        assertTrue(state.abandonSendLaunch(accepted.inputEffect.identity))
        assertFalse(state.generating.value)
        assertFalse(state.inputPersisted(accepted.inputEffect.identity))
    }

    @Test
    fun pendingGuidance_cannotBeLeapfroggedByAnIdleAutomationSend() = runBlocking {
        val state = ConversationGenerationState("conversation")
        state.enqueueSend(
            QueuedSend(
                id = "guidance",
                text = "later",
                modelId = "OpenAI:model",
                attachments = emptyList(),
                runId = "origin-run",
            )
        )

        val decision = AutomationRuntimeAdmission.request(state, "new-run", "automation-send")

        assertSame(AutomationRuntimeAdmission.Decision.Busy, decision)
        assertFalse(state.generating.value)
        assertEquals(listOf("guidance"), state.queuedSends.value.map { it.id })
        state.dispose()
        Unit
    }
}
