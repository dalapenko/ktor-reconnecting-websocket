package io.github.dalapenko.ktor.websocket.reconnect

import io.ktor.client.*
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A WebSocket client wrapper that automatically reconnects on connection loss.
 *
 * This class provides a resilient WebSocket connection that handles:
 * - Initial connection failures with retry
 * - Mid-session disconnections with automatic reconnection
 * - Exponential backoff with configurable delays
 * - Observable connection state via StateFlow
 * - Graceful shutdown
 * - Both plain (`ws://`) and secure (`wss://`) connections
 * - Optional port (omit for standard ports implied by scheme)
 * - Authentication via customizable request builder
 *
 * ## Usage Examples:
 *
 * ### Silent operation (no logging):
 * ```kotlin
 * val client = HttpClient(CIO) { install(WebSockets) }
 *
 * val ws = ReconnectingWebSocket(
 *     client = client,
 *     host = "localhost",
 *     port = 8080,
 *     path = "/events",
 *     retryPolicy = RetryPolicy(maxRetries = 5)
 * )
 *
 * ws.connect().collect { frame ->
 *     println("Received: $frame")
 * }
 * ```
 *
 * ### Domain-only host without explicit port:
 * ```kotlin
 * val ws = ReconnectingWebSocket(
 *     client = client,
 *     host = "api.example.com",
 *     path = "/stream",
 *     secure = true  // Uses wss:// with default port 443
 * )
 * ```
 *
 * ### Secure WebSocket (wss://) with authentication:
 * ```kotlin
 * val ws = ReconnectingWebSocket(
 *     client = client,
 *     host = "example.com",
 *     path = "/events",
 *     secure = true,
 *     requestBuilder = {
 *         headers.append("Authorization", "Bearer $token")
 *     }
 * )
 * ```
 *
 * ### With SLF4J logging:
 * ```kotlin
 * val ws = ReconnectingWebSocket(
 *     client = client,
 *     host = "localhost",
 *     port = 8080,
 *     path = "/events",
 *     retryPolicy = RetryPolicy(maxRetries = 5),
 *     logger = Logger.DEFAULT  // Use SLF4J logging
 * )
 * ```
 *
 * @param client The Ktor HttpClient with WebSockets plugin installed
 * @param host WebSocket server host
 * @param port WebSocket server port. If null, the scheme default is used (80 for `ws://`, 443 for `wss://`).
 * @param path WebSocket endpoint path
 * @param secure If true, uses `wss://` (TLS). Defaults to false (`ws://`).
 * @param retryPolicy Configuration for retry behavior
 * @param requestBuilder Optional lambda applied to the HTTP upgrade request on every connection attempt.
 *                       Use this to set authentication headers, cookies, query parameters, or any
 *                       other request customization (e.g. `headers.append("Authorization", "Bearer $token")`).
 * @param logger Optional Ktor Logger for logging connection events.
 *               If null, no logging is performed.
 *               Use [Logger.Companion.DEFAULT] for SLF4J, [Logger.Companion.SIMPLE] for println,
 *               or provide a custom implementation.
 */
class ReconnectingWebSocket(
    private val client: HttpClient,
    private val host: String,
    private val port: Int? = null,
    private val path: String,
    private val secure: Boolean = false,
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
    private val logger: Logger? = null
) {
    private val url = buildString {
        append(if (secure) "wss" else "ws")
        append("://")
        append(host)
        if (port != null) {
            append(":")
            append(port)
        }
        append(path)
    }

    private val _connectionState = MutableStateFlow<WebSocketConnectionState>(
        WebSocketConnectionState.Disconnected
    )

    /**
     * Observable connection state. Collect this flow to react to state changes.
     */
    val connectionState: StateFlow<WebSocketConnectionState> = _connectionState.asStateFlow()

    /**
     * Returns true if currently connected to the WebSocket server.
     */
    val isConnected: Boolean get() = _connectionState.value.isConnected

    private val shouldStop = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private val currentSession = AtomicReference<DefaultClientWebSocketSession?>(null)

    /**
     * Connects to the WebSocket server and returns a Flow of incoming frames.
     *
     * The connection will automatically reconnect according to the retry policy
     * if the connection is lost. The flow will complete when:
     * - [close] is called
     * - Max retries are exhausted (if not infinite)
     * - An unrecoverable error occurs
     *
     * @return Flow of incoming WebSocket frames
     * @throws IllegalStateException if called while already connected. Call [close] first.
     */
    fun connect(): Flow<Frame> = callbackFlow {
        check(isRunning.compareAndSet(false, true)) {
            "connect() called while already running. Call close() before reconnecting."
        }
        shouldStop.set(false)
        var attempt = 0
        val outputChannel = channel

        log("Starting with retry policy: $retryPolicy")

        try {
            while (!shouldStop.get()) {
                try {
                    updateState(WebSocketConnectionState.Connecting(url))
                    log("Connecting to $url")

                    client.webSocket(urlString = url, request = requestBuilder) {
                        currentSession.set(this)
                        attempt = 0 // Reset attempt counter on successful connection

                        updateState(WebSocketConnectionState.Connected(url))
                        log("Connected successfully!")
                        log("Listening for events...")

                        try {
                            for (frame in incoming) {
                                if (shouldStop.get()) break
                                outputChannel.send(frame)
                            }

                            // Normal close - check if we should reconnect
                            if (!shouldStop.get()) {
                                log("Connection closed by server")
                            }
                        } finally {
                            currentSession.compareAndSet(this, null)
                        }
                    }

                    // If we reach here without error and shouldStop is false,
                    // the server closed the connection - attempt reconnect
                    if (!shouldStop.get() && !retryPolicy.isNoRetry) {
                        throw ConnectionLostException("Server closed connection")
                    } else if (!shouldStop.get()) {
                        // No retry policy - exit gracefully
                        updateState(WebSocketConnectionState.Disconnected)
                        break
                    }

                } catch (e: CancellationException) {
                    log("Connection cancelled")
                    currentSession.getAndSet(null)?.let { session ->
                        try {
                            session.close(CloseReason(CloseReason.Codes.NORMAL, "Cancelled"))
                        } catch (_: Exception) {
                        }
                    }
                    updateState(WebSocketConnectionState.Disconnected)
                    throw e

                } catch (e: Exception) {
                    if (shouldStop.get()) {
                        updateState(WebSocketConnectionState.Disconnected)
                        break
                    }

                    // Check if we should retry
                    if (!retryPolicy.shouldRetry(attempt, e)) {
                        val reason = if (retryPolicy.isNoRetry) {
                            "Retry disabled"
                        } else if (!retryPolicy.retryOnException(e)) {
                            "Exception not retryable: ${e::class.simpleName}"
                        } else {
                            "Max retries ($attempt/${retryPolicy.maxRetries}) exhausted"
                        }

                        log("Connection failed permanently: $reason")
                        updateState(
                            WebSocketConnectionState.Failed(
                                reason = reason,
                                lastError = e,
                                totalAttempts = attempt
                            )
                        )
                        break
                    }

                    // Calculate delay and wait
                    val retryDelay = retryPolicy.calculateDelay(attempt)
                    attempt++

                    log("Connection failed: ${e.message}")
                    log("Reconnecting (attempt $attempt/${if (retryPolicy.isInfinite) "∞" else retryPolicy.maxRetries}, retry in $retryDelay)")

                    updateState(
                        WebSocketConnectionState.Reconnecting(
                            attempt = attempt,
                            maxAttempts = if (retryPolicy.isInfinite) null else retryPolicy.maxRetries,
                            nextRetryIn = retryDelay,
                            lastError = e
                        )
                    )

                    delay(retryDelay)
                }
            }
        } finally {
            isRunning.set(false)
        }

        log("Connection loop ended")
        close()
    }

    /**
     * Gracefully closes the WebSocket connection.
     * No reconnection will be attempted after calling this method.
     */
    suspend fun close(reason: String = "Client requested close") {
        log("Closing connection: $reason")
        shouldStop.set(true)

        try {
            currentSession.getAndSet(null)?.close(CloseReason(CloseReason.Codes.NORMAL, reason))
        } catch (e: Exception) {
            log("Error during close: ${e.message}")
        }

        updateState(WebSocketConnectionState.Disconnected)
    }

    /**
     * Sends a WebSocket frame to the server.
     *
     * @param frame The frame to send
     * @throws IllegalStateException if not currently connected
     */
    suspend fun send(frame: Frame) {
        currentSession.get()?.send(frame)
            ?: throw IllegalStateException("Not connected: cannot send frame while disconnected")
    }

    /**
     * Sends a text message to the server.
     *
     * @param text The text to send
     * @throws IllegalStateException if not currently connected
     */
    suspend fun sendText(text: String) = send(Frame.Text(text))

    private fun updateState(newState: WebSocketConnectionState) {
        _connectionState.value = newState
    }

    private fun log(message: String) {
        logger?.log("[ReconnectingWebSocket] $message")
    }

    /**
     * Internal exception to signal connection loss for retry logic.
     */
    private class ConnectionLostException(message: String) : Exception(message)
}
