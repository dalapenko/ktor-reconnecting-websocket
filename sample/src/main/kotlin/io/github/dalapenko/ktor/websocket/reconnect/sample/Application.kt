package io.github.dalapenko.ktor.websocket.reconnect.sample

import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.cio.EngineMain.main(args)
}

fun Application.module() {
    configureSerialization()
    configureSockets()
    configureRouting()
}
