package io.github.dalapenko.ktor.websocket.reconnect

import kotlin.time.Duration

/**
 * Represents the connection state of a reconnecting WebSocket.
 * 
 * This sealed class allows consumers to reactively observe connection state changes
 * and respond appropriately (e.g., update UI, trigger alerts, etc.).
 */
sealed class WebSocketConnectionState {

    /**
     * Initial state or state after graceful disconnection.
     * The WebSocket is not connected and not attempting to connect.
     */
    data object Disconnected : WebSocketConnectionState()

    /**
     * Actively attempting to establish a connection.
     */
    data class Connecting(val url: String) : WebSocketConnectionState()

    /**
     * Successfully connected to the WebSocket server.
     * Ready to send/receive messages.
     */
    data class Connected(val url: String) : WebSocketConnectionState()

    /**
     * Connection was lost and a reconnection attempt is scheduled.
     * 
     * @param attempt Current retry attempt number (1-based)
     * @param maxAttempts Maximum number of retry attempts, or `null` for infinite
     * @param nextRetryIn Duration until the next retry attempt
     * @param lastError The exception that caused the disconnection
     */
    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int?,
        val nextRetryIn: Duration,
        val lastError: Throwable? = null
    ) : WebSocketConnectionState() {
        val isInfinite: Boolean get() = maxAttempts == null

        override fun toString(): String {
            val attemptsStr = if (isInfinite) "$attempt/∞" else "$attempt/$maxAttempts"
            return "Reconnecting(attempt=$attemptsStr, nextRetryIn=$nextRetryIn)"
        }
    }

    /**
     * Connection failed permanently after exhausting all retry attempts.
     * No further reconnection attempts will be made.
     * 
     * @param reason Human-readable description of the failure
     * @param lastError The last exception that occurred
     * @param totalAttempts Total number of reconnection attempts made
     */
    data class Failed(
        val reason: String,
        val lastError: Throwable? = null,
        val totalAttempts: Int = 0
    ) : WebSocketConnectionState()

    /**
     * Check if the WebSocket is currently connected.
     */
    val isConnected: Boolean get() = this is Connected

    /**
     * Check if the WebSocket is actively trying to connect or reconnect.
     */
    val isConnecting: Boolean get() = this is Connecting || this is Reconnecting

    /**
     * Check if the WebSocket has permanently failed.
     */
    val isFailed: Boolean get() = this is Failed
}
