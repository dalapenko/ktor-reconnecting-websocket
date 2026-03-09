package io.github.dalapenko.ktor.websocket.reconnect

import io.ktor.client.*
import io.ktor.client.plugins.logging.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * Extension functions for HttpClient to easily create reconnecting WebSocket connections.
 * 
 * These extensions provide convenient ways to establish resilient WebSocket connections
 * that automatically reconnect on connection loss.
 */

/**
 * Creates a reconnecting WebSocket connection and returns a Flow of incoming frames.
 * 
 * This is a convenience extension that creates a [ReconnectingWebSocket] internally
 * and returns the frame flow directly. Use this when you don't need to observe
 * connection state separately.
 * 
 * ## Example:
 * ```kotlin
 * val client = HttpClient(CIO) { install(WebSockets) }
 * 
 * client.reconnectingWebSocket(
 *     host = "localhost",
 *     port = 8080,
 *     path = "/events",
 *     retryPolicy = RetryPolicy(maxRetries = 5),
 *     logger = Logger.DEFAULT
 * ).collect { frame ->
 *     println("Received: $frame")
 * }
 * ```
 * 
 * @param host WebSocket server host
 * @param port WebSocket server port
 * @param path WebSocket endpoint path (must start with "/")
 * @param retryPolicy Configuration for retry behavior
 * @param logger Optional Ktor Logger for logging connection events
 * @return Flow of incoming WebSocket frames
 */
fun HttpClient.reconnectingWebSocket(
    host: String,
    port: Int,
    path: String,
    retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    logger: Logger? = null
): Flow<Frame> {
    val ws = ReconnectingWebSocket(
        client = this,
        host = host,
        port = port,
        path = path,
        retryPolicy = retryPolicy,
        logger = logger
    )
    return ws.connect()
}

/**
 * Creates a reconnecting WebSocket connection that returns only text messages.
 * 
 * This is a convenience extension that filters for text frames and extracts
 * the text content directly.
 * 
 * ## Example:
 * ```kotlin
 * client.reconnectingWebSocketText(
 *     host = "localhost",
 *     port = 8080,
 *     path = "/events",
 *     logger = Logger.DEFAULT
 * ).collect { text ->
 *     println("Message: $text")
 * }
 * ```
 * 
 * @param host WebSocket server host
 * @param port WebSocket server port
 * @param path WebSocket endpoint path
 * @param retryPolicy Configuration for retry behavior
 * @param logger Optional Ktor Logger for logging connection events
 * @return Flow of incoming text messages as Strings
 */
fun HttpClient.reconnectingWebSocketText(
    host: String,
    port: Int,
    path: String,
    retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    logger: Logger? = null
): Flow<String> {
    return reconnectingWebSocket(
        host = host,
        port = port,
        path = path,
        retryPolicy = retryPolicy,
        logger = logger
    ).mapTextFrames()
}

/**
 * Creates a [ReconnectingWebSocket] instance for full control over the connection.
 * 
 * Use this when you need to:
 * - Observe connection state changes via [ReconnectingWebSocket.connectionState]
 * - Manually close the connection via [ReconnectingWebSocket.close]
 * - Access the [ReconnectingWebSocket.isConnected] property
 * 
 * ## Example:
 * ```kotlin
 * val ws = client.createReconnectingWebSocket(
 *     host = "localhost",
 *     port = 8080,
 *     path = "/events",
 *     logger = Logger.DEFAULT
 * )
 * 
 * // Observe state changes
 * launch {
 *     ws.connectionState.collect { state ->
 *         when (state) {
 *             is WebSocketConnectionState.Connected -> println("Connected!")
 *             is WebSocketConnectionState.Reconnecting -> println("Reconnecting...")
 *             is WebSocketConnectionState.Failed -> println("Failed: ${state.reason}")
 *             else -> {}
 *         }
 *     }
 * }
 * 
 * // Connect and receive messages
 * ws.connect().collect { frame ->
 *     println("Frame: $frame")
 * }
 * ```
 * 
 * @param host WebSocket server host
 * @param port WebSocket server port
 * @param path WebSocket endpoint path
 * @param retryPolicy Configuration for retry behavior
 * @param logger Optional Ktor Logger for logging connection events
 * @return A [ReconnectingWebSocket] instance
 */
fun HttpClient.createReconnectingWebSocket(
    host: String,
    port: Int,
    path: String,
    retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    logger: Logger? = null
): ReconnectingWebSocket {
    return ReconnectingWebSocket(
        client = this,
        host = host,
        port = port,
        path = path,
        retryPolicy = retryPolicy,
        logger = logger
    )
}

/**
 * Filters a Flow of Frames to only include text frames and extracts the text content.
 * 
 * This is a convenience operator that filters for [Frame.Text] instances and
 * automatically extracts their text content. Non-text frames are ignored.
 * 
 * ## Example:
 * ```kotlin
 * val client = HttpClient(CIO) { install(WebSockets) }
 * 
 * client.reconnectingWebSocket(
 *     host = "localhost",
 *     port = 8080,
 *     path = "/events"
 * ).mapTextFrames().collect { text ->
 *     println("Text message: $text")
 * }
 * ```
 * 
 * @return Flow of text content from text frames only
 * @see filterTextFrames for getting Frame.Text instances instead of raw strings
 */
fun Flow<Frame>.mapTextFrames(): Flow<String> =
    filterIsInstance<Frame.Text>().map(Frame.Text::readText)

/**
 * Filters a Flow of Frames to only include text frames.
 * 
 * This operator filters the flow to contain only [Frame.Text] instances,
 * ignoring binary, close, ping, and pong frames.
 * 
 * ## Example:
 * ```kotlin
 * ws.connect()
 *     .filterTextFrames()
 *     .collect { textFrame ->
 *         val content = textFrame.readText()
 *         println("Received text: $content")
 *     }
 * ```
 * 
 * @return Flow of Frame.Text instances only
 * @see mapTextFrames for directly extracting text content as strings
 */
fun Flow<Frame>.filterTextFrames(): Flow<Frame.Text> =
    filterIsInstance<Frame.Text>()
