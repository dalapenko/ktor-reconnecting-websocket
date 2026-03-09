package io.github.dalapenko.ktor.websocket.reconnect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WebSocketConnectionStateTest {

    @Test
    fun `Disconnected isConnected returns false`() {
        val state = WebSocketConnectionState.Disconnected

        assertFalse(state.isConnected)
        assertFalse(state.isConnecting)
        assertFalse(state.isFailed)
    }

    @Test
    fun `Connected isConnected returns true`() {
        val state = WebSocketConnectionState.Connected("ws://localhost:8080/test")

        assertTrue(state.isConnected)
        assertFalse(state.isConnecting)
        assertFalse(state.isFailed)
        assertEquals("ws://localhost:8080/test", state.url)
    }

    @Test
    fun `Connecting isConnecting returns true`() {
        val state = WebSocketConnectionState.Connecting("ws://localhost:8080/test")

        assertFalse(state.isConnected)
        assertTrue(state.isConnecting)
        assertFalse(state.isFailed)
        assertEquals("ws://localhost:8080/test", state.url)
    }

    @Test
    fun `Reconnecting isConnecting returns true`() {
        val state = WebSocketConnectionState.Reconnecting(
            attempt = 3,
            maxAttempts = 5,
            nextRetryIn = 4.seconds,
            lastError = RuntimeException("Connection lost")
        )

        assertFalse(state.isConnected)
        assertTrue(state.isConnecting)
        assertFalse(state.isFailed)
        assertEquals(3, state.attempt)
        assertEquals(5, state.maxAttempts)
        assertEquals(4.seconds, state.nextRetryIn)
        assertNotNull(state.lastError)
    }

    @Test
    fun `Failed isFailed returns true`() {
        val state = WebSocketConnectionState.Failed(
            reason = "Max retries exhausted",
            lastError = RuntimeException("Connection refused"),
            totalAttempts = 5
        )

        assertFalse(state.isConnected)
        assertFalse(state.isConnecting)
        assertTrue(state.isFailed)
        assertEquals("Max retries exhausted", state.reason)
        assertEquals(5, state.totalAttempts)
        assertNotNull(state.lastError)
    }

    @Test
    fun `Reconnecting isInfinite with null maxAttempts`() {
        val state = WebSocketConnectionState.Reconnecting(
            attempt = 10,
            maxAttempts = null,
            nextRetryIn = 2.seconds
        )

        assertTrue(state.isInfinite)
        assertNull(state.maxAttempts)
    }

    @Test
    fun `Reconnecting isInfinite with positive maxAttempts`() {
        val state = WebSocketConnectionState.Reconnecting(
            attempt = 3,
            maxAttempts = 5,
            nextRetryIn = 2.seconds
        )

        assertFalse(state.isInfinite)
        assertEquals(5, state.maxAttempts)
    }

    @Test
    fun `Reconnecting toString formats correctly for finite retries`() {
        val state = WebSocketConnectionState.Reconnecting(
            attempt = 3,
            maxAttempts = 5,
            nextRetryIn = 4.seconds
        )

        val str = state.toString()
        assertTrue(str.contains("3/5"))
        assertTrue(str.contains("nextRetryIn=4s"))
    }

    @Test
    fun `Reconnecting toString formats correctly for infinite retries`() {
        val state = WebSocketConnectionState.Reconnecting(
            attempt = 10,
            maxAttempts = null,
            nextRetryIn = 2.seconds
        )

        val str = state.toString()
        assertTrue(str.contains("10/∞"))
        assertTrue(str.contains("nextRetryIn=2s"))
    }

    @Test
    fun `Reconnecting with null lastError is allowed`() {
        val state = WebSocketConnectionState.Reconnecting(
            attempt = 1,
            maxAttempts = 5,
            nextRetryIn = 2.seconds,
            lastError = null
        )

        assertNull(state.lastError)
        assertEquals(1, state.attempt)
    }

    @Test
    fun `Failed with null lastError is allowed`() {
        val state = WebSocketConnectionState.Failed(
            reason = "Retry disabled",
            lastError = null,
            totalAttempts = 0
        )

        assertNull(state.lastError)
        assertEquals("Retry disabled", state.reason)
        assertEquals(0, state.totalAttempts)
    }
}
