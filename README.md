# Ktor Reconnecting WebSocket

[![Maven Central](https://img.shields.io/maven-central/v/io.github.dalapenko/ktor-reconnecting-websocket.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.dalapenko/ktor-reconnecting-websocket)
[![Build Status](https://github.com/dalapenko/ktor-reconnecting-websocket/actions/workflows/build.yml/badge.svg)](https://github.com/dalapenko/ktor-reconnecting-websocket/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A resilient WebSocket client library for Ktor with automatic reconnection, exponential backoff, and observable connection states.

---

## The Problem

WebSocket connections are inherently fragile:
- Network interruptions cause instant disconnection
- Server restarts break active connections
- Mobile apps lose connectivity when switching networks
- Manual reconnection logic is complex and error-prone

Ktor's WebSocket client provides basic connectivity, but **doesn't handle reconnections automatically**. Developers must implement reconnection logic manually in every project.

## The Solution

**ktor-reconnecting-websocket** is a library that wraps Ktor's WebSocket client with:

✅ **Automatic reconnection** - Transparently reconnects on connection loss  
✅ **Smart retry policies** - Exponential backoff with jitter to prevent thundering herd  
✅ **Observable states** - Track connection status via Kotlin StateFlow  
✅ **Configurable behavior** - Pre-defined policies or custom retry strategies  
✅ **Zero boilerplate** - Simple extension functions on `HttpClient`  
✅ **Flow-based API** - Native Kotlin coroutines integration  

---

## Quick Start

### Installation

**Gradle (Kotlin DSL)**
```kotlin
dependencies {
    implementation("io.github.dalapenko:ktor-reconnecting-websocket:1.0.0")
    implementation("io.ktor:ktor-client-cio:$ktor_version") // Or any other engine
}
```

**Gradle (Groovy DSL)**
```groovy
dependencies {
    implementation 'io.github.dalapenko:ktor-reconnecting-websocket:1.0.0'
    implementation "io.ktor:ktor-client-cio:$ktor_version"
}
```

**Maven**
```xml
<dependency>
    <groupId>io.github.dalapenko</groupId>
    <artifactId>ktor-reconnecting-websocket</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Basic Usage

```kotlin
import io.github.dalapenko.ktor.websocket.reconnect.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*

val client = HttpClient(CIO) {
    install(WebSockets)
}

// Connect with auto-reconnect
client.reconnectingWebSocket(
    host = "api.example.com",
    port = 443,
    path = "/stream",
    retryPolicy = RetryPolicy.DEFAULT
).collect { frame ->
    when (frame) {
        is Frame.Text -> println("Received: ${frame.readText()}")
        is Frame.Binary -> println("Received binary data")
        else -> {}
    }
}
```

**That's it!** The library handles all reconnection logic automatically.

---

## Features

### 1. Automatic Reconnection

When the connection drops, the library automatically:
1. Detects the disconnection
2. Waits for the configured delay (with exponential backoff)
3. Attempts to reconnect
4. Resumes message flow transparently

**No manual intervention needed.**

### 2. Configurable Retry Policies

Choose from pre-configured policies:

```kotlin
// Never give up - retry forever (default for long-lived connections)
RetryPolicy.INFINITE

// Try a few times, then give up (default)
RetryPolicy.DEFAULT       // 5 retries, 2s initial delay, 30s max

// Aggressive reconnection (for time-sensitive apps)
RetryPolicy.AGGRESSIVE    // 10 retries, 500ms initial, 10s max

// Conservative approach (to avoid overwhelming the server)
RetryPolicy.CONSERVATIVE  // 3 retries, 5s initial, 60s max

// No reconnection at all
RetryPolicy.NO_RETRY
```

Or create your own:

```kotlin
val customPolicy = RetryPolicy(
    maxRetries = 10,            // -1 = infinite, 0 = no retry
    initialDelay = 2.seconds,   // First retry delay
    maxDelay = 60.seconds,      // Maximum delay cap
    delayMultiplier = 2.0,      // Exponential backoff multiplier
    jitterFactor = 0.1          // Random jitter (0.0 - 1.0)
)
```

### 3. Observable Connection States

Track connection status in real-time:

```kotlin
val ws = client.createReconnectingWebSocket(
    host = "api.example.com",
    path = "/stream",
    retryPolicy = RetryPolicy.INFINITE
)

// Observe connection state
launch {
    ws.connectionState.collect { state ->
        when (state) {
            is WebSocketConnectionState.Idle -> 
                println("Not connected yet")
            
            is WebSocketConnectionState.Connecting -> 
                println("Connecting to ${state.url}...")
            
            is WebSocketConnectionState.Connected -> 
                println("✓ Connected to ${state.url}")
            
            is WebSocketConnectionState.Reconnecting -> 
                println("⟳ Reconnecting (attempt ${state.attempt}/${state.maxRetries ?: "∞"}, retry in ${state.nextRetryDelay})")
            
            is WebSocketConnectionState.Disconnected -> 
                println("Disconnected: ${state.reason}")
            
            is WebSocketConnectionState.Failed -> 
                println("✗ Failed after ${state.attempts} attempts: ${state.reason}")
        }
    }
}

// Connect and receive messages
ws.connect().collect { frame ->
    // Process frames
}
```

### 4. Integration with Ktor Logger

Leverage Ktor's native logging for debugging:

```kotlin
import io.ktor.client.plugins.logging.*

val client = HttpClient(CIO) {
    install(WebSockets)
    install(Logging) {
        level = LogLevel.INFO
    }
}

// Pass logger to reconnecting WebSocket
client.reconnectingWebSocket(
    host = "localhost",
    port = 8080,
    path = "/events",
    logger = Logger.DEFAULT  // Uses Ktor's configured logger
).collect { frame ->
    // Logs connection attempts, errors, and reconnections
}
```

---

## API Reference

### Extension Functions

#### 1. `reconnectingWebSocket` (Simple Flow-based)

```kotlin
suspend fun HttpClient.reconnectingWebSocket(
    host: String,
    port: Int = 80,
    path: String = "/",
    retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    logger: Logger? = null
): Flow<Frame>
```

**Use when:** You just want a simple Flow of WebSocket frames with auto-reconnection.

#### 2. `reconnectingWebSocketText` (Text-only messages)

```kotlin
suspend fun HttpClient.reconnectingWebSocketText(
    host: String,
    port: Int = 80,
    path: String = "/",
    retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    logger: Logger? = null
): Flow<String>
```

**Use when:** You only care about text messages (filters out binary/control frames).

#### 3. `createReconnectingWebSocket` (Full control)

```kotlin
fun HttpClient.createReconnectingWebSocket(
    host: String,
    port: Int = 80,
    path: String = "/",
    retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    logger: Logger? = null
): ReconnectingWebSocket
```

**Use when:** You need access to connection state and lifecycle methods.

### Classes

#### `RetryPolicy`

Defines reconnection behavior:

```kotlin
data class RetryPolicy(
    val maxRetries: Int?,         // null = infinite
    val initialDelay: Duration,   // First retry delay
    val maxDelay: Duration,       // Maximum delay cap
    val delayMultiplier: Double,  // Exponential growth factor
    val jitterFactor: Double      // Random jitter (0.0 - 1.0)
)
```

**Pre-configured policies:**
- `RetryPolicy.DEFAULT` - Balanced (5 retries, 2s-30s)
- `RetryPolicy.INFINITE` - Never give up
- `RetryPolicy.NO_RETRY` - Fail immediately
- `RetryPolicy.AGGRESSIVE` - Fast reconnection (10 retries, 500ms-10s)
- `RetryPolicy.CONSERVATIVE` - Slow reconnection (3 retries, 5s-60s)

#### `WebSocketConnectionState`

Sealed class representing connection states:

```kotlin
sealed class WebSocketConnectionState {
    data object Idle : WebSocketConnectionState()
    data class Connecting(val url: String) : WebSocketConnectionState()
    data class Connected(val url: String) : WebSocketConnectionState()
    data class Reconnecting(
        val url: String,
        val attempt: Int,
        val maxRetries: Int?,
        val nextRetryDelay: Duration
    ) : WebSocketConnectionState()
    data class Disconnected(val reason: String?) : WebSocketConnectionState()
    data class Failed(val reason: String, val attempts: Int) : WebSocketConnectionState()
}
```

---

## Usage Examples

### Example 1: Streaming Real-Time Data

```kotlin
val client = HttpClient(CIO) { install(WebSockets) }

// Connect to a streaming API
client.reconnectingWebSocketText(
    host = "api.crypto.com",
    port = 443,
    path = "/prices",
    retryPolicy = RetryPolicy.INFINITE  // Never stop trying
).collect { message ->
    val price = Json.decodeFromString<CryptoPrice>(message)
    updateUI(price)
}
```

### Example 2: Mobile Chat Application

```kotlin
// Create reconnecting WebSocket with state tracking
val chatSocket = client.createReconnectingWebSocket(
    host = "chat.example.com",
    port = 443,
    path = "/chat",
    retryPolicy = RetryPolicy.AGGRESSIVE
)

// Show connection status in UI
launch {
    chatSocket.connectionState.collect { state ->
        when (state) {
            is Connected -> showOnlineIndicator()
            is Reconnecting -> showReconnectingIndicator(state.attempt)
            is Failed -> showOfflineDialog()
            else -> {}
        }
    }
}

// Receive messages
launch {
    chatSocket.connect().collect { frame ->
        if (frame is Frame.Text) {
            val message = Json.decodeFromString<ChatMessage>(frame.readText())
            displayMessage(message)
        }
    }
}

// Send messages
suspend fun sendMessage(text: String) {
    chatSocket.send(Frame.Text(text))
}
```

### Example 3: IoT Device with Logging

```kotlin
val client = HttpClient(CIO) {
    install(WebSockets)
    install(Logging) {
        logger = Logger.DEFAULT
        level = LogLevel.INFO
    }
}

client.reconnectingWebSocket(
    host = "iot.platform.com",
    port = 8883,
    path = "/device/sensor-123",
    retryPolicy = RetryPolicy.CONSERVATIVE,
    logger = Logger.DEFAULT  // Logs all connection events
).collect { frame ->
    when (frame) {
        is Frame.Text -> {
            val command = Json.decodeFromString<DeviceCommand>(frame.readText())
            executeCommand(command)
        }
        else -> {}
    }
}
```

---

## How It Works

### Reconnection Algorithm

1. **Initial Connection**: Attempts to connect to the WebSocket endpoint
2. **Connection Lost**: Detects disconnection (network error, server close, timeout)
3. **Calculate Delay**: Uses exponential backoff with jitter:
   ```
   delay = min(initialDelay × multiplier^attempt, maxDelay)
   jittered_delay = delay ± (delay × jitterFactor × random())
   ```
4. **Wait**: Pauses for the calculated delay
5. **Retry**: Attempts to reconnect
6. **Repeat**: Steps 3-5 until:
   - Connection succeeds, OR
   - Max retries exhausted, OR
   - Manual cancellation

### Thread Safety

All operations are thread-safe and coroutine-safe:
- Connection state updates are synchronized
- Multiple coroutines can safely observe `connectionState`
- Frame collection is single-subscriber (standard Flow behavior)

---

## Sample Application

This repository includes a `sample/` module demonstrating:
- **WebSocket server** broadcasting periodic events
- **WebSocket client** using the library with auto-reconnection
- **Two endpoints**: `/ws` (echo) and `/events` (broadcast)

See the [Running the Sample](#running-the-sample) section below for instructions.

---

## Running the Sample

The `sample` module demonstrates the library with a working server and client.

### 1. Start the Server

```bash
./gradlew :sample:run
```

The server starts on `http://localhost:8080` with two endpoints:
- `/ws` - Echo endpoint (repeats messages back)
- `/events` - Broadcast endpoint (sends periodic status updates)

### 2. Start the Client

In another terminal:

```bash
./gradlew :sample:runClient
```

The client connects to `/events` and displays:
- Connection status changes
- Received messages with timestamps
- Automatic reconnection attempts

### 3. Test Reconnection

**Scenario A: Server restarts**
1. Stop the server (Ctrl+C)
2. Client detects disconnection and starts retrying
3. Restart the server
4. Client automatically reconnects and resumes

**Scenario B: Network interruption**
1. Server and client running
2. Simulate network issue (close/reopen terminal, etc.)
3. Client automatically reconnects when network recovers

### Sample Output

```
================================================================================
WebSocket Client with Auto-Reconnect
================================================================================
[10:30:00.123] [WebSocket] Starting with retry policy: RetryPolicy(maxRetries=5, initialDelay=2s, maxDelay=30s, multiplier=2.0)
[10:30:00.124] [WebSocket] Connecting to ws://localhost:8080/events
[10:30:00.125] [Client] State: Connecting to ws://localhost:8080/events
[10:30:00.250] [WebSocket] ✓ Connected successfully!
[10:30:00.251] [Client] State: ✓ Connected to ws://localhost:8080/events
--------------------------------------------------------------------------------
[10:30:03.001] [Client] Received: {"id":1,"timestamp":"...","message":"Server status OK"}
[10:30:06.002] [Client] Received: {"id":2,"timestamp":"...","message":"Server status OK"}

# Server stops here...

[10:30:09.003] [WebSocket] ✗ Error during session: Connection reset
[10:30:09.004] [WebSocket] Reconnecting (attempt 1/5, retry in 2.0s)
[10:30:09.005] [Client] State: Reconnecting (attempt 1/5, next retry in 2s)
[10:30:11.010] [WebSocket] Connecting to ws://localhost:8080/events
[10:30:11.011] [WebSocket] ✗ Connection failed: Connection refused
[10:30:11.012] [WebSocket] Reconnecting (attempt 2/5, retry in 4.0s)

# Server restarts here...

[10:30:15.020] [WebSocket] Connecting to ws://localhost:8080/events
[10:30:15.150] [WebSocket] ✓ Connected successfully!
[10:30:15.151] [Client] State: ✓ Connected to ws://localhost:8080/events
--------------------------------------------------------------------------------
[10:30:18.005] [Client] Received: {"id":1,"timestamp":"...","message":"Server status OK"}
```

---

## Project Structure

```
ktor-reconnecting-websocket/            # Root project
├── library/                            # 📦 The published library
│   ├── build.gradle.kts                # Maven publishing configuration
│   └── src/
│       ├── main/kotlin/io/github/dalapenko/ktor/websocket/reconnect/
│       │   ├── ReconnectingWebSocket.kt        # Main client class
│       │   ├── RetryPolicy.kt                   # Retry configuration
│       │   ├── WebSocketConnectionState.kt      # Connection state sealed class
│       │   └── WebSocketExtensions.kt           # HttpClient extension functions
│       └── test/kotlin/                         # 49 unit tests
│
├── sample/                             # 🎯 Demo application (not published)
│   ├── build.gradle.kts
│   └── src/main/kotlin/io/github/dalapenko/ktor/websocket/reconnect/sample/
│       ├── Application.kt              # Server entry point
│       ├── Sockets.kt                  # WebSocket endpoints
│       ├── Routing.kt                  # HTTP routing
│       ├── Serialization.kt            # JSON serialization
│       └── WebSocketClient.kt          # Client demo using the library
│
├── .github/workflows/
│   ├── build.yml                       # CI on push/PR
│   └── release.yml                     # Publish to Maven Central on tag push
│
├── gradle/libs.versions.toml           # Dependency management
├── README.md                           # This file
└── SETUP.md                            # Maven Central publishing guide
```

---

## Building & Testing

### Build All Modules

```bash
./gradlew build
```

### Build Library Only

```bash
./gradlew :library:build
```

### Run Tests

```bash
./gradlew test
```

---

## Requirements

- **Kotlin**: 2.3.0+
- **Ktor**: 3.4.0+
- **Java**: 17+ (runtime)
- **Coroutines**: 1.10.1+

---

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

---

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Submit a pull request

---

## Links

- [Ktor Documentation](https://ktor.io/docs/home.html)
- [Ktor WebSocket Client](https://ktor.io/docs/client-websockets.html)
- [Kotlin Coroutines Flow](https://kotlinlang.org/docs/flow.html)
- [Maven Central](https://central.sonatype.com/artifact/io.github.dalapenko/ktor-reconnecting-websocket)

---

## Support

- **Issues**: [GitHub Issues](https://github.com/dalapenko/ktor-reconnecting-websocket/issues)
- **Discussions**: [GitHub Discussions](https://github.com/dalapenko/ktor-reconnecting-websocket/discussions)
