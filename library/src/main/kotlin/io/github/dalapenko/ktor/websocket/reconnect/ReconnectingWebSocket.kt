package io.github.dalapenko.ktor.websocket.reconnect

import io.ktor.client.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/**
 * A WebSocket client wrapper that automatically reconnects on connection loss.
 * 
 * This class provides a resilient WebSocket connection that handles:
 * - Initial connection failures with retry
 * - Mid-session disconnections with automatic reconnection
 * - Exponential backoff with configurable delays
 * - Observable connection state via StateFlow
 * - Graceful shutdown
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
 * ### With SLF4J logging:
 * ```kotlin
 * val client = HttpClient(CIO) {
 *     install(WebSockets)
 *     install(Logging) {
 *         logger = Logger.DEFAULT
 *         level = LogLevel.INFO
 *     }
 * }
 * 
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
 * ### With custom logger:
 * ```kotlin
 * val customLogger = object : Logger {
 *     override fun log(message: String) {
 *         Napier.d(message)  // Custom logging framework
 *     }
 * }
 * 
 * val ws = ReconnectingWebSocket(
 *     client = client,
 *     host = "localhost",
 *     port = 8080,
 *     path = "/events",
 *     logger = customLogger
 * )
 * ```
 * 
 * @param client The Ktor HttpClient with WebSockets plugin installed
 * @param host WebSocket server host
 * @param port WebSocket server port
 * @param path WebSocket endpoint path
 * @param retryPolicy Configuration for retry behavior
 * @param logger Optional Ktor Logger for logging connection events.
 *               If null, no logging is performed.
 *               Use [Logger.Companion.DEFAULT] for SLF4J, [Logger.Companion.SIMPLE] for println,
 *               or provide a custom implementation.
 */
class ReconnectingWebSocket(
    private val client: HttpClient,
    private val host: String,
    private val port: Int,
    private val path: String,
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    private val logger: Logger? = null
) {
    private val url = "ws://$host:$port$path"

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

    @Volatile
    private var shouldStop = false

    @Volatile
    private var currentSession: DefaultClientWebSocketSession? = null

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
     */
    fun connect(): Flow<Frame> = callbackFlow {
        shouldStop = false
        var attempt = 0

        log("Starting with retry policy: $retryPolicy")

        while (!shouldStop) {
            try {
                updateState(WebSocketConnectionState.Connecting(url))
                log("Connecting to $url")

                client.webSocket(host = host, port = port, path = path) {
                    currentSession = this
                    attempt = 0 // Reset attempt counter on successful connection

                    updateState(WebSocketConnectionState.Connected(url))
                    log("✓ Connected successfully!")
                    log("Listening for events...")

                    try {
                        for (frame in incoming) {
                            if (shouldStop) break
                            trySend(frame)
                        }

                        // Normal close - check if we should reconnect
                        if (!shouldStop) {
                            log("Connection closed by server")
                        }
                    } finally {
                        currentSession = null
                    }
                }

                // If we reach here without error and shouldStop is false,
                // the server closed the connection - attempt reconnect
                if (!shouldStop && !retryPolicy.isNoRetry) {
                    throw ConnectionLostException("Server closed connection")
                } else if (!shouldStop) {
                    // No retry policy - exit gracefully
                    updateState(WebSocketConnectionState.Disconnected)
                    break
                }

            } catch (e: CancellationException) {
                log("Connection cancelled")
                updateState(WebSocketConnectionState.Disconnected)
                throw e

            } catch (e: Exception) {
                if (shouldStop) {
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

                    log("✗ Connection failed permanently: $reason")
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
                val delay = retryPolicy.calculateDelay(attempt)
                attempt++

                val maxAttemptsDisplay = retryPolicy.maxRetries
                log("✗ Connection failed: ${e.message}")
                log("Reconnecting (attempt $attempt/${if (retryPolicy.isInfinite) "∞" else maxAttemptsDisplay}, retry in $delay)")

                updateState(
                    WebSocketConnectionState.Reconnecting(
                        attempt = attempt,
                        maxAttempts = maxAttemptsDisplay,
                        nextRetryIn = delay,
                        lastError = e
                    )
                )

                delay(delay)
            }
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
        shouldStop = true

        try {
            currentSession?.close(CloseReason(CloseReason.Codes.NORMAL, reason))
        } catch (e: Exception) {
            log("Error during close: ${e.message}")
        }

        updateState(WebSocketConnectionState.Disconnected)
    }

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
