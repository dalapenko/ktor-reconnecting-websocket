package io.github.dalapenko.ktor.websocket.reconnect.sample

import io.github.dalapenko.ktor.websocket.reconnect.ReconnectingWebSocket
import io.github.dalapenko.ktor.websocket.reconnect.RetryPolicy
import io.github.dalapenko.ktor.websocket.reconnect.WebSocketConnectionState
import io.github.dalapenko.ktor.websocket.reconnect.createReconnectingWebSocket
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

/**
 * Standalone WebSocket client with auto-reconnection capability.
 * 
 * This client demonstrates the usage of the reusable [ReconnectingWebSocket] extension
 * that provides resilient WebSocket connections with:
 * - Automatic reconnection on connection loss
 * - Exponential backoff with configurable delays
 * - Observable connection state
 * - Graceful shutdown
 * 
 * Usage:
 *   ./gradlew runClient
 * 
 * The client will:
 * 1. Connect to ws://localhost:8080/events
 * 2. Listen for server events and log them
 * 3. Automatically reconnect if connection is lost
 * 4. Stop after max retries are exhausted (or run forever with infinite retries)
 */
fun main() {
    val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 20.seconds
        }
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }
    }

    println("=".repeat(80))
    println("WebSocket Client with Auto-Reconnect")
    println("=".repeat(80))

    // Configure retry policy
    // Options:
    //   RetryPolicy.DEFAULT       - 5 retries with exponential backoff
    //   RetryPolicy.INFINITE      - Never give up (-1)
    //   RetryPolicy.NO_RETRY      - No reconnection (0)
    //   RetryPolicy.AGGRESSIVE    - 10 retries with shorter delays
    //   RetryPolicy.CONSERVATIVE  - 3 retries with longer delays
    //   RetryPolicy(custom...)    - Custom configuration
    val retryPolicy = RetryPolicy(
        maxRetries = 5,              // -1 = infinite, 0 = no retry, N = max attempts
        initialDelay = 2.seconds,    // First retry delay
        maxDelay = 30.seconds,       // Maximum delay cap
        delayMultiplier = 2.0,       // Exponential backoff multiplier
        jitterFactor = 0.1           // Random jitter to prevent thundering herd
    )

    // Create reconnecting WebSocket with full control
    val reconnectingWs = client.createReconnectingWebSocket(
        host = "localhost",
        port = 8080,
        path = "/events",
        retryPolicy = retryPolicy,
        logger = Logger.DEFAULT  // Use SLF4J logging via Logback
    )

    runBlocking {
        // Launch a coroutine to observe connection state changes
        val stateObserver = launch {
            reconnectingWs.connectionState.collect { state ->
                when (state) {
                    is WebSocketConnectionState.Disconnected -> {
                        logClient("State: Disconnected")
                    }

                    is WebSocketConnectionState.Connecting -> {
                        logClient("State: Connecting to ${state.url}")
                    }

                    is WebSocketConnectionState.Connected -> {
                        logClient("State: ✓ Connected to ${state.url}")
                        println("-".repeat(80))
                    }

                    is WebSocketConnectionState.Reconnecting -> {
                        logClient("State: Reconnecting (attempt ${state.attempt}/${if (state.isInfinite) "∞" else state.maxAttempts}, next retry in ${state.nextRetryIn})")
                    }

                    is WebSocketConnectionState.Failed -> {
                        logClient("State: ✗ Failed - ${state.reason}")
                        state.lastError?.let { error ->
                            logClient("       Last error: ${error.message}")
                        }
                    }
                }
            }
        }

        // Handle graceful shutdown on Ctrl+C
        Runtime.getRuntime().addShutdownHook(Thread {
            println()
            println("-".repeat(80))
            println("Shutdown signal received...")
            runBlocking {
                reconnectingWs.close("User requested shutdown")
            }
            client.close()
            println("Client closed")
            println("=".repeat(80))
        })

        try {
            // Connect and listen to messages
            reconnectingWs.connect()
                .catch { e ->
                    logClient("✗ Fatal error: ${e.message}")
                }
                .collect { frame ->
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            logClient("Received: $text")
                        }

                        is Frame.Binary -> {
                            logClient("Received binary frame (${frame.readBytes().size} bytes)")
                        }

                        is Frame.Close -> {
                            val reason = frame.readReason()
                            logClient("Server closed connection: ${reason?.message ?: "No reason"}")
                        }

                        else -> {
                            logClient("Received frame: ${frame.frameType}")
                        }
                    }
                }

            // Flow completed - check final state
            val finalState = reconnectingWs.connectionState.value
            if (finalState is WebSocketConnectionState.Failed) {
                logClient("Connection failed after ${finalState.totalAttempts} attempts")
                logClient("Reason: ${finalState.reason}")
            } else {
                logClient("Connection ended gracefully")
            }

        } catch (e: CancellationException) {
            logClient("Client cancelled")
        } catch (e: Exception) {
            logClient("Unexpected error: ${e.message}")
            e.printStackTrace()
        } finally {
            stateObserver.cancel()
            client.close()
            logClient("Client resources released")
        }
    }

    println("=".repeat(80))
}

/**
 * Helper function to log messages with timestamp prefix.
 */
private fun logClient(message: String) {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
    println("[$timestamp] [Client] $message")
}
