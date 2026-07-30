package com.newoether.agora.viewmodel

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AskUserControllerTest {

    @Test
    fun ask_publishesPendingAndSuspendsUntilResolved() = runTest {
        val controller = AskUserController()
        val result = async { controller.ask("What's your favorite color?", listOf("red", "green", "blue")) }
        kotlinx.coroutines.yield()
        val pending = controller.pending.value
        assertNotNull(pending)
        assertEquals("What's your favorite color?", pending!!.question)
        assertEquals(listOf("red", "green", "blue"), pending.options)

        controller.resolve("blue")
        assertEquals("blue", result.await())
        assertNull(controller.pending.value)
    }

    @Test
    fun resolve_withNull_returnsNullAndClearsPending() = runTest {
        val controller = AskUserController()
        val result = async { controller.ask("Continue?") }
        kotlinx.coroutines.yield()
        controller.resolve(null)
        assertNull(result.await())
        assertNull(controller.pending.value)
    }

    @Test
    fun resolve_withBlankAnswer_isTreatedAsDismiss() = runTest {
        val controller = AskUserController()
        val result = async { controller.ask("Continue?") }
        kotlinx.coroutines.yield()
        controller.resolve("   ")
        assertNull(result.await())
    }

    @Test
    fun resolve_withNoPendingQuestion_isANoOp() = runBlocking {
        val controller = AskUserController()
        controller.resolve("answer")
        assertNull(controller.pending.value)
    }
}
