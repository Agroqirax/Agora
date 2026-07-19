package com.newoether.agora.viewmodel

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ToolConfirmationControllerTest {

    @Test
    fun confirm_whenDisabled_returnsTrueWithoutPrompting() = runTest {
        val controller = ToolConfirmationController<Unit>(
            confirmEnabled = { false },
            setConfirmEnabled = {}
        )
        assertTrue(controller.confirm("do the thing"))
        assertNull(controller.pending.value)
    }

    @Test
    fun confirm_whenEnabled_publishesPendingAndSuspendsUntilResolved() = runTest {
        val controller = ToolConfirmationController<Unit>(
            confirmEnabled = { true },
            setConfirmEnabled = {}
        )
        val result = async { controller.confirm("do the thing") }
        // Give the coroutine a chance to publish the pending state.
        kotlinx.coroutines.yield()
        val pending = controller.pending.value
        assertNotNull(pending)
        assertEquals("do the thing", pending!!.summary)

        controller.resolve(allow = true)
        assertTrue(result.await())
        assertNull(controller.pending.value)
    }

    @Test
    fun resolve_deny_returnsFalseAndClearsPending() = runTest {
        val controller = ToolConfirmationController<Unit>(
            confirmEnabled = { true },
            setConfirmEnabled = {}
        )
        val result = async { controller.confirm("do the thing") }
        kotlinx.coroutines.yield()
        controller.resolve(allow = false)
        assertFalse(result.await())
        assertNull(controller.pending.value)
    }

    @Test
    fun resolve_alwaysAllow_flipsGlobalKillSwitch() = runTest {
        var confirmEnabled = true
        val controller = ToolConfirmationController<Unit>(
            confirmEnabled = { confirmEnabled },
            setConfirmEnabled = { confirmEnabled = it }
        )
        val result = async { controller.confirm("do the thing") }
        kotlinx.coroutines.yield()
        controller.resolve(allow = true, alwaysAllow = true)
        assertTrue(result.await())
        assertFalse(confirmEnabled)
    }

    @Test
    fun confirm_withAlwaysAllowedKey_shortCircuitsWithoutPrompting() = runTest {
        val trusted = mutableSetOf("server-a")
        val controller = ToolConfirmationController<String>(
            confirmEnabled = { true },
            setConfirmEnabled = {},
            isKeyAlwaysAllowed = { trusted.contains(it) },
            setKeyAlwaysAllowed = { key, allowed -> if (allowed) trusted.add(key) else trusted.remove(key) }
        )
        // Trusted key: no dialog at all.
        assertTrue(controller.confirm("run command", key = "server-a", keyLabel = "Server A"))
        assertNull(controller.pending.value)

        // Untrusted key: prompts as usual.
        val result = async { controller.confirm("run command", key = "server-b", keyLabel = "Server B") }
        kotlinx.coroutines.yield()
        assertNotNull(controller.pending.value)
        assertEquals("server-b", controller.pending.value!!.key)
        controller.resolve(allow = true, alwaysAllowKey = true)
        assertTrue(result.await())
        assertTrue(trusted.contains("server-b"))

        // Now that server-b is trusted too, a second confirm() for it doesn't prompt.
        assertTrue(controller.confirm("run another command", key = "server-b", keyLabel = "Server B"))
        assertNull(controller.pending.value)
    }

    @Test
    fun resolve_withNoPendingConfirmation_isANoOp() = runBlocking {
        val controller = ToolConfirmationController<Unit>(
            confirmEnabled = { true },
            setConfirmEnabled = {}
        )
        // Should not throw even though nothing is pending.
        controller.resolve(allow = true)
        assertNull(controller.pending.value)
    }

    @Test
    fun resolve_alwaysAllowKey_withNoKeyOnPending_doesNotThrow() = runTest {
        val controller = ToolConfirmationController<String>(
            confirmEnabled = { true },
            setConfirmEnabled = {}
        )
        val result = async { controller.confirm("do the thing") } // no key passed
        kotlinx.coroutines.yield()
        controller.resolve(allow = true, alwaysAllowKey = true)
        assertTrue(result.await())
    }
}
