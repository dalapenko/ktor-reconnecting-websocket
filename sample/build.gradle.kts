plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "io.dalapenko.ktor.websocket.reconnect.sample"
version = "1.0.0"

application {
    mainClass = "io.ktor.server.cio.EngineMain"
}

dependencies {
    // Use the reconnecting WebSocket library
    implementation(project(":library"))

    // Server dependencies
    implementation(libs.bundles.ktor.server)

    // Client engine (library only provides interfaces)
    implementation(libs.ktor.client.cio)

    // Tests
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

// Task to run WebSocket client
tasks.register<JavaExec>("runClient") {
    group = "application"
    description = "Run WebSocket client application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.dalapenko.ktor.websocket.reconnect.sample.WebSocketClientKt")
}
