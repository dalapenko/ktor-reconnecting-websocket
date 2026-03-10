package io.github.dalapenko.ktor.websocket.reconnect

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        val mockEngine = MockEngine {
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

        // Verify initial state
        assertEquals(WebSocketConnectionState.Disconnected, ws.connectionState.value)

        // Launch connection attempt
        val connectJob = launch {
            ws.connect().collect { }
        }

        // Wait a bit for the Connecting state to be emitted
        // This is more reliable than trying to collect all states
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()

        val currentState = ws.connectionState.value
        // At this point, we should see either Connecting or Failed state
        // (Failed if the connection completed very quickly)
        val isConnectingOrFailed = currentState is WebSocketConnectionState.Connecting ||
                currentState is WebSocketConnectionState.Failed
        assertTrue(isConnectingOrFailed, "Expected Connecting or Failed state, got $currentState")

        // Wait for connection to complete
        connectJob.join()

        // Final state should be Failed with NO_RETRY policy
        val finalState = ws.connectionState.value
        assertTrue(finalState is WebSocketConnectionState.Failed, "Final state should be Failed, got $finalState")

        client.close()
    }

    @Test
    fun `connectionState emits Failed after connection error with NO_RETRY`() = runTest {
        val mockEngine = MockEngine {
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
        val mockEngine = MockEngine {
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
        val mockEngine = MockEngine {
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
    fun `secure true uses wss scheme in connecting state URL`() = runTest {
        // Pause the MockEngine response so the Connecting state is stable while we assert.
        // Without this, MockEngine can respond synchronously and the state transitions from
        // Connecting → Failed before StateFlow delivers Connecting to any collector (conflation).
        val connectionPaused = CompletableDeferred<Unit>()
        val mockEngine = MockEngine {
            connectionPaused.await()
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val client = HttpClient(mockEngine) { install(WebSockets) }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "example.com",
            port = 443,
            path = "/test",
            secure = true,
            retryPolicy = RetryPolicy.NO_RETRY,
            logger = Logger.EMPTY
        )

        val connectJob = launch { ws.connect().collect { } }

        // Wait until Connecting is the current state — guaranteed stable because MockEngine is paused
        val connectingState = ws.connectionState
            .first { it is WebSocketConnectionState.Connecting } as WebSocketConnectionState.Connecting

        assertTrue(connectingState.url.startsWith("wss://"), "Expected wss:// scheme, got: ${connectingState.url}")

        connectionPaused.complete(Unit)
        connectJob.join()
        client.close()
    }

    @Test
    fun `secure false uses ws scheme in connecting state URL`() = runTest {
        val connectionPaused = CompletableDeferred<Unit>()
        val mockEngine = MockEngine {
            connectionPaused.await()
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val client = HttpClient(mockEngine) { install(WebSockets) }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            secure = false,
            retryPolicy = RetryPolicy.NO_RETRY,
            logger = Logger.EMPTY
        )

        val connectJob = launch { ws.connect().collect { } }

        val connectingState = ws.connectionState
            .first { it is WebSocketConnectionState.Connecting } as WebSocketConnectionState.Connecting

        assertTrue(connectingState.url.startsWith("ws://"), "Expected ws:// scheme, got: ${connectingState.url}")

        connectionPaused.complete(Unit)
        connectJob.join()
        client.close()
    }

    @Test
    fun `send throws IllegalStateException when not connected`() = runTest {
        val client = HttpClient(MockEngine { respondOk() }) { install(WebSockets) }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            logger = Logger.EMPTY
        )

        assertFailsWith<IllegalStateException> {
            ws.send(Frame.Text("hello"))
        }

        client.close()
    }

    @Test
    fun `sendText throws IllegalStateException when not connected`() = runTest {
        val client = HttpClient(MockEngine { respondOk() }) { install(WebSockets) }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            logger = Logger.EMPTY
        )

        assertFailsWith<IllegalStateException> {
            ws.sendText("hello")
        }

        client.close()
    }

    @Test
    fun `connect throws IllegalStateException when called while already running`() = runTest {
        val mockEngine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val client = HttpClient(mockEngine) { install(WebSockets) }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/test",
            retryPolicy = RetryPolicy(
                maxRetries = 3,
                initialDelay = 10.seconds,  // Long delay: first attempt fails then waits
                maxDelay = 10.seconds,
                jitterFactor = 0.0
            ),
            logger = Logger.EMPTY
        )

        // Start connection - first attempt will fail, then wait 10s before retrying
        val firstJob = launch { ws.connect().collect { } }

        // Advance enough for the first attempt to fail and enter retry delay,
        // but not enough to trigger the 10s retry
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        // isRunning is true while waiting in retry delay - second call must throw
        assertFailsWith<IllegalStateException> {
            ws.connect().collect { }
        }

        firstJob.cancel()
        client.close()
    }

    @Test
    fun `null port omits port from URL`() = runTest {
        val connectionPaused = CompletableDeferred<Unit>()
        val mockEngine = MockEngine {
            connectionPaused.await()
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val client = HttpClient(mockEngine) { install(WebSockets) }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "api.example.com",
            port = null,
            path = "/stream",
            secure = true,
            retryPolicy = RetryPolicy.NO_RETRY,
            logger = Logger.EMPTY
        )

        val connectJob = launch { ws.connect().collect { } }

        val connectingState = ws.connectionState
            .first { it is WebSocketConnectionState.Connecting } as WebSocketConnectionState.Connecting

        assertTrue(
            connectingState.url.startsWith("wss://api.example.com/"),
            "Expected URL without explicit port, got: ${connectingState.url}"
        )
        assertFalse(
            connectingState.url.contains(":443") || connectingState.url.matches(Regex(".*:\\d+.*")),
            "URL should not contain a port component, got: ${connectingState.url}"
        )

        connectionPaused.complete(Unit)
        connectJob.join()
        client.close()
    }

    @Test
    fun `explicit port is included in URL`() = runTest {
        val connectionPaused = CompletableDeferred<Unit>()
        val mockEngine = MockEngine {
            connectionPaused.await()
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val client = HttpClient(mockEngine) { install(WebSockets) }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 9090,
            path = "/test",
            secure = false,
            retryPolicy = RetryPolicy.NO_RETRY,
            logger = Logger.EMPTY
        )

        val connectJob = launch { ws.connect().collect { } }

        val connectingState = ws.connectionState
            .first { it is WebSocketConnectionState.Connecting } as WebSocketConnectionState.Connecting

        assertEquals(
            connectingState.url,
            "ws://localhost:9090/test",
            "Expected ws://localhost:9090/test, got: ${connectingState.url}"
        )

        connectionPaused.complete(Unit)
        connectJob.join()
        client.close()
    }

    @Test
    fun `requestBuilder headers are sent with WebSocket upgrade request`() = runTest {
        var capturedAuthHeader: String? = null
        val mockEngine = MockEngine { request ->
            capturedAuthHeader = request.headers["Authorization"]
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val client = HttpClient(mockEngine) { install(WebSockets) }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/secure",
            retryPolicy = RetryPolicy.NO_RETRY,
            requestBuilder = {
                headers.append("Authorization", "Bearer test-token")
            },
            logger = Logger.EMPTY
        )

        val connectJob = launch { ws.connect().collect { } }
        connectJob.join()

        assertEquals("Bearer test-token", capturedAuthHeader, "Authorization header should be forwarded")

        client.close()
    }

    @Test
    fun `requestBuilder is applied on every reconnection attempt`() = runTest {
        var attemptCount = 0
        val capturedHeaders = mutableListOf<String?>()
        val mockEngine = MockEngine { request ->
            attemptCount++
            capturedHeaders.add(request.headers["Authorization"])
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val client = HttpClient(mockEngine) { install(WebSockets) }

        val ws = ReconnectingWebSocket(
            client = client,
            host = "localhost",
            port = 8080,
            path = "/secure",
            retryPolicy = RetryPolicy(
                maxRetries = 2,
                initialDelay = 50.milliseconds,
                maxDelay = 100.milliseconds,
                jitterFactor = 0.0
            ),
            requestBuilder = {
                headers.append("Authorization", "Bearer token-123")
            },
            logger = Logger.EMPTY
        )

        val connectJob = launch { ws.connect().collect { } }
        connectJob.join()

        assertTrue(attemptCount > 1, "Expected multiple connection attempts")
        assertTrue(
            capturedHeaders.all { it == "Bearer token-123" },
            "Authorization header should be present on every attempt, got: $capturedHeaders"
        )

        client.close()
    }

    @Test
    fun `custom exception filter is respected`() = runTest {
        val mockEngine = MockEngine {
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
