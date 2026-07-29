package com.newoether.agora.api.openai

import android.content.Context
import android.content.pm.ApplicationInfo
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BaseOpenAiProviderTerminationTest {

    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    @Test
    fun finishReasonWithoutDone_completesWithinGraceWindow() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"content":"complete"},"finish_reason":"stop"}]}"""
            )
            release.await()
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        assertEquals(
            "complete",
            events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text },
        )
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun finishReason_stillAcceptsTrailingUsageAndDone() = withServer(
        terminalGraceMillis = 500L,
        response = { socket, _ ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"content":"complete"},"finish_reason":"stop"}]}"""
            )
            Thread.sleep(25L)
            socket.writeSse(
                """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":7,"total_tokens":17}}"""
            )
            socket.writeSse("[DONE]")
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        assertEquals(17, events.filterIsInstance<StreamEvent.UsageUpdate>().single().tokenCount)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun toolCallFinishReason_emitsCallAndDoesNotRequireDone() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
            )
            release.await()
        },
    ) { provider, config ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("call_1", call.id)
        assertEquals("lookup", call.name)
        assertEquals("{}", call.arguments)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    private fun messages() = listOf(
        ChatMessage(
            text = "hello",
            participant = Participant.USER,
        )
    )

    private fun withServer(
        terminalGraceMillis: Long,
        response: (Socket, CountDownLatch) -> Unit,
        test: (BaseOpenAiProvider, ProviderConfig) -> Unit,
    ) {
        SseServer(response).use { server ->
            val provider = object : BaseOpenAiProvider() {
                override val name: String = "test"
                override val defaultBaseUrl: String = server.baseUrl
                override val terminalSseGraceMillis: Long = terminalGraceMillis
            }
            try {
                test(
                    provider,
                    ProviderConfig(
                        apiKey = "",
                        modelId = "test-model",
                        baseUrl = server.baseUrl,
                        thinkingEnabled = false,
                    ),
                )
            } finally {
                server.throwIfFailed()
            }
        }
    }

    private class SseServer(
        private val response: (Socket, CountDownLatch) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        private val release = CountDownLatch(1)
        private val accepted = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>(null)
        @Volatile
        private var client: Socket? = null
        private val worker = thread(
            name = "openai-sse-test-server",
            isDaemon = true,
        ) {
            try {
                server.accept().use { socket ->
                    client = socket
                    accepted.countDown()
                    socket.tcpNoDelay = true
                    readRequestHeaders(socket)
                    val headers = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/event-stream\r\n")
                        append("Cache-Control: no-cache\r\n")
                        append("Transfer-Encoding: chunked\r\n")
                        append("Connection: keep-alive\r\n")
                        append("\r\n")
                    }
                    socket.getOutputStream().write(headers.toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().flush()
                    response(socket, release)
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        val baseUrl: String = "http://127.0.0.1:${server.localPort}/v1"

        fun throwIfFailed() {
            failure.get()?.let { throw AssertionError("SSE test server failed", it) }
            check(accepted.await(1L, TimeUnit.SECONDS)) {
                "SSE test server never received the request"
            }
        }

        override fun close() {
            release.countDown()
            runCatching { client?.close() }
            runCatching { server.close() }
            worker.join(1_000L)
        }

        private fun readRequestHeaders(socket: Socket) {
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
            )
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) return
            }
        }
    }

    private fun Socket.writeSse(data: String) {
        val payload = "data: $data\n\n".toByteArray(StandardCharsets.UTF_8)
        val output = getOutputStream()
        output.write("${payload.size.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(payload)
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }
}
