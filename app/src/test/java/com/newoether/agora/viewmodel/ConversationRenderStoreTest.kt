package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationRenderStoreTest {
    @Test
    fun commitGraph_publishesOneSelfConsistentEditSnapshot() {
        val oldUser = message("old-user", null, Participant.USER)
        val oldModel = message("old-model", oldUser.id, Participant.MODEL)
        val newUser = message("new-user", null, Participant.USER)
        val placeholder = message(
            "new-model",
            newUser.id,
            Participant.MODEL,
            MessageStatus.SENDING,
        )
        val store = ConversationRenderStore()
        store.replaceConversation(
            allMessages = listOf(oldUser, oldModel),
            selectedChildren = mapOf(null to oldUser.id, oldUser.id to oldModel.id),
        )

        store.commitGraph(
            committedMessages = listOf(newUser, placeholder),
            selectedChildren = mapOf(null to newUser.id, newUser.id to placeholder.id),
            streamingMessage = placeholder,
        )

        val snapshot = store.snapshot.value
        assertSame(placeholder, snapshot.streamingMessage)
        assertEquals(newUser.id, snapshot.selectedChildren[null])
        assertEquals(
            listOf(newUser.id, placeholder.id),
            ConversationUiState.resolvePath(
                snapshot.allMessages,
                snapshot.streamingMessage,
                snapshot.selectedChildren,
            ).map { it.id },
        )
    }

    @Test
    fun terminalStreamingHandoff_neverExposesTheOlderRoomCheckpoint() {
        val user = message("user", null, Participant.USER)
        val staleCheckpoint = message(
            id = "model",
            parentId = user.id,
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            text = "partial",
        )
        val stopped = message(
            id = staleCheckpoint.id,
            parentId = user.id,
            participant = Participant.MODEL,
            status = MessageStatus.STOPPED,
            text = "partial response with the latest tokens",
        )
        val store = ConversationRenderStore()
        store.replaceConversation(
            allMessages = listOf(user, staleCheckpoint),
            selectedChildren = mapOf(null to user.id, user.id to stopped.id),
            streamingMessage = stopped,
        )

        store.commitTerminalStreamingMessage(stopped)

        val handedOff = store.snapshot.value
        assertNull(handedOff.streamingMessage)
        assertSame(stopped, handedOff.allMessages.single { it.id == stopped.id })
        assertEquals(
            stopped.text,
            ConversationUiState.resolvePath(
                handedOff.allMessages,
                handedOff.streamingMessage,
                handedOff.selectedChildren,
            ).last().text,
        )

        // A combine emission queued before the handoff cannot resurrect the retired overlay.
        store.setStreamingMessage(stopped)
        assertNull(store.snapshot.value.streamingMessage)

        // A different generation remains free to install its own overlay.
        val next = message(
            id = "next-model",
            parentId = stopped.id,
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
        )
        store.setStreamingMessage(next)
        assertSame(next, store.snapshot.value.streamingMessage)
    }

    private fun message(
        id: String,
        parentId: String?,
        participant: Participant,
        status: MessageStatus = MessageStatus.SUCCESS,
        text: String = "",
    ) = ChatMessage(
        id = id,
        parentId = parentId,
        text = text,
        participant = participant,
        status = status,
        timestamp = id.hashCode().toLong(),
        runId = "run-$id",
        runSequence = 0,
    )
}
