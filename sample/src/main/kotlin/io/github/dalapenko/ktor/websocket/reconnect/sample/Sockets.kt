package io.github.dalapenko.ktor.websocket.reconnect.sample

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

@Serializable
data class ServerEvent(
    val id: Long,
    val timestamp: String,
    val uptime: Long,
    val type: String,
    val message: String,
    val data: EventData
)

@Serializable
data class EventData(
    val connections: Int,
    val eventsCount: Long
)

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    // Shared flow for broadcasting events to all connected clients
    val eventFlow = MutableSharedFlow<String>()
    val sharedEventFlow = eventFlow.asSharedFlow()

    // Track server start time and event counter
    val startTime = System.currentTimeMillis()
    var eventCounter = 0L
    var connectionCounter = 0

    // Launch background coroutine for periodic event broadcasting
    launch {
        log.info("Starting periodic event broadcaster...")
        while (true) {
            delay(3000) // Broadcast every 3 seconds
            eventCounter++
            val uptime = (System.currentTimeMillis() - startTime) / 1000
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            val event = ServerEvent(
                id = eventCounter,
                timestamp = timestamp,
                uptime = uptime,
                type = "status",
                message = "Server status OK - Event #$eventCounter",
                data = EventData(
                    connections = connectionCounter,
                    eventsCount = eventCounter
                )
            )

            val eventJson = Json.encodeToString(event)
            log.info("Broadcasting event #$eventCounter to $connectionCounter clients")
            eventFlow.emit(eventJson)
        }
    }

    routing {
        // Original echo endpoint - simple request/response
        webSocket("/ws") {
            log.info("Client connected to /ws (echo endpoint)")
            send("Welcome to echo endpoint! Send me messages and I'll echo them back.")

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        log.info("Echo received: $text")
                        outgoing.send(Frame.Text("YOU SAID: $text"))
                        if (text.equals("bye", ignoreCase = true)) {
                            close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("Error in /ws endpoint: ${e.localizedMessage}")
            } finally {
                log.info("Client disconnected from /ws")
            }
        }

        // New events endpoint - server broadcasts periodic events
        webSocket("/events") {
            connectionCounter++
            val clientId = connectionCounter
            log.info("Client #$clientId connected to /events (${connectionCounter} total connections)")

            send("Welcome to events endpoint! You will receive periodic server events.")

            // Launch coroutine to listen to shared flow and send to this client
            val job = launch {
                sharedEventFlow.collect { event ->
                    try {
                        send(event)
                    } catch (e: Exception) {
                        log.error("Error sending event to client #$clientId: ${e.localizedMessage}")
                    }
                }
            }

            // Keep connection alive and handle incoming frames (though client won't send any)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        log.info("Client #$clientId sent: ${frame.readText()}")
                    }
                }
            } catch (e: Exception) {
                log.error("Error in /events endpoint for client #$clientId: ${e.localizedMessage}")
            } finally {
                job.cancel()
                connectionCounter--
                log.info("Client #$clientId disconnected from /events (${connectionCounter} remaining)")
            }
        }
    }

    log.info("WebSocket server configured with endpoints:")
    log.info("  - /ws (echo mode)")
    log.info("  - /events (broadcast mode)")
}
