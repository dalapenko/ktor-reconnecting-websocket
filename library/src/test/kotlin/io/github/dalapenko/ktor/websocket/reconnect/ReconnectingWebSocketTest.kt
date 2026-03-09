package io.github.dalapenko.ktor.websocket.reconnect

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ReconnectingWebSocketTest {

    @Test
    fun `initial state is Disconnected`() {
        val client = HttpClient(MockEngine { respondOk() }) {
            install(WebSockets)
        }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            logger = Logger.EMPTY
        )

        assertEquals(WebSocketConnectionState.Disconnected, ws.connectionState.value)
        assertFalse(ws.isConnected)

        client.close()
    }

    @Test
    fun `connectionState emits Connecting when connect called`() = runTest {
        val mockEngine = MockEngine { request ->
            // Simulate connection failure
            respondError(HttpStatusCode.ServiceUnavailable)
        }

        val client = HttpClient(mockEngine) {
            install(WebSockets)
        }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            retryPolicy = RetryPolicy.NO_RETRY,
            logger = Logger.EMPTY
        )

        val states = mutableListOf<WebSocketConnectionState>()

        // Collect states in background
        val stateJob = launch {
            ws.connectionState
                .take(3)
                .collect { states.add(it) }
        }

        // Try to connect (will fail)
        val connectJob = launch {
            ws.connect().collect { }
        }

        connectJob.join()
        stateJob.cancel()

        // Should have: Disconnected -> Connecting -> Failed
        assertTrue(states.size >= 2, "Expected at least 2 states, got ${states.size}")
        assertEquals(WebSocketConnectionState.Disconnected, states[0])
        assertTrue(states[1] is WebSocketConnectionState.Connecting)

        client.close()
    }

    @Test
    fun `connectionState emits Failed after connection error with NO_RETRY`() = runTest {
        val mockEngine = MockEngine { request ->
            respondError(HttpStatusCode.ServiceUnavailable)
        }

        val client = HttpClient(mockEngine) {
            install(WebSockets)
        }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            retryPolicy = RetryPolicy.NO_RETRY,
            logger = Logger.EMPTY
        )

        val states = mutableListOf<WebSocketConnectionState>()

        val stateJob = launch {
            ws.connectionState.collect { states.add(it) }
        }

        // Try to connect (will fail and not retry)
        val connectJob = launch {
            ws.connect().collect { }
        }

        connectJob.join()
        stateJob.cancel()

        // Should end in Failed state
        val finalState = states.last()
        assertTrue(finalState is WebSocketConnectionState.Failed)
        assertTrue(finalState.reason.contains("Retry disabled"))

        client.close()
    }

    @Test
    fun `connectionState emits Reconnecting after failure with retry policy`() = runTest {
        var attemptCount = 0
        val mockEngine = MockEngine { request ->
            attemptCount++
            // Always fail to trigger reconnection
            respondError(HttpStatusCode.ServiceUnavailable)
        }

        val client = HttpClient(mockEngine) {
            install(WebSockets)
        }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            retryPolicy = RetryPolicy(
                maxRetries = 2,
                initialDelay = 100.milliseconds,
                maxDelay = 200.milliseconds,
                jitterFactor = 0.0
            ),
            logger = Logger.EMPTY
        )

        val states = mutableListOf<WebSocketConnectionState>()

        val stateJob = launch {
            ws.connectionState.collect { states.add(it) }
        }

        val connectJob = launch {
            ws.connect().collect { }
        }

        connectJob.join()
        stateJob.cancel()

        // Should have seen Reconnecting state
        val reconnectingStates = states.filterIsInstance<WebSocketConnectionState.Reconnecting>()
        assertTrue(reconnectingStates.isNotEmpty(), "Expected at least one Reconnecting state")
        assertTrue(attemptCount > 1, "Expected multiple connection attempts")

        client.close()
    }

    @Test
    fun `close sets state to Disconnected`() = runTest {
        val client = HttpClient(MockEngine { respondOk() }) {
            install(WebSockets)
        }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            logger = Logger.EMPTY
        )

        ws.close("Test close")

        assertEquals(WebSocketConnectionState.Disconnected, ws.connectionState.value)
        assertFalse(ws.isConnected)

        client.close()
    }

    @Test
    fun `isConnected returns true only when Connected state`() = runTest {
        val client = HttpClient(MockEngine { respondOk() }) {
            install(WebSockets)
        }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            logger = Logger.EMPTY
        )

        // Initially disconnected
        assertFalse(ws.isConnected)

        // After closing
        ws.close()
        assertFalse(ws.isConnected)

        client.close()
    }

    @Test
    fun `retry policy is respected for max retries`() = runTest {
        var connectionAttempts = 0
        val mockEngine = MockEngine { request ->
            connectionAttempts++
            respondError(HttpStatusCode.ServiceUnavailable)
        }

        val client = HttpClient(mockEngine) {
            install(WebSockets)
        }

        val maxRetries = 3
        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            retryPolicy = RetryPolicy(
                maxRetries = maxRetries,
                initialDelay = 50.milliseconds,
                maxDelay = 100.milliseconds,
                jitterFactor = 0.0
            ),
            logger = Logger.EMPTY
        )

        val connectJob = launch {
            ws.connect().collect { }
        }

        connectJob.join()

        // Should have attempted: 1 initial + maxRetries
        assertEquals(maxRetries + 1, connectionAttempts)

        val finalState = ws.connectionState.value
        assertTrue(finalState is WebSocketConnectionState.Failed)

        client.close()
    }

    @Test
    fun `custom exception filter is respected`() = runTest {
        val mockEngine = MockEngine { request ->
            throw IllegalStateException("Test exception")
        }

        val client = HttpClient(mockEngine) {
            install(WebSockets)
        }

        // Only retry on IllegalArgumentException, not IllegalStateException
        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            retryPolicy = RetryPolicy(
                maxRetries = 5,
                initialDelay = 50.milliseconds,
                retryOnException = { it is IllegalArgumentException }
            ),
            logger = Logger.EMPTY
        )

        val connectJob = launch {
            ws.connect().collect { }
        }

        connectJob.join()

        val finalState = ws.connectionState.value
        assertTrue(finalState is WebSocketConnectionState.Failed)
        assertTrue(finalState.reason.contains("Exception not retryable"))

        client.close()
    }
}
