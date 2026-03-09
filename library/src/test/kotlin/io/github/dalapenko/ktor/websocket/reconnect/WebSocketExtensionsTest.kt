package io.github.dalapenko.ktor.websocket.reconnect

import io.ktor.websocket.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebSocketExtensionsTest {

    @Test
    fun `mapTextFrames filters and maps text frames`() = runTest {
        val frames = flowOf(
            Frame.Text("Hello"),
            Frame.Text("World"),
            Frame.Text("Test")
        )

        val result = frames.mapTextFrames().toList()

        assertEquals(3, result.size)
        assertEquals("Hello", result[0])
        assertEquals("World", result[1])
        assertEquals("Test", result[2])
    }

    @Test
    fun `mapTextFrames ignores binary frames`() = runTest {
        val frames = flowOf(
            Frame.Text("Text1"),
            Frame.Binary(true, byteArrayOf(1, 2, 3)),
            Frame.Text("Text2"),
            Frame.Binary(true, byteArrayOf(4, 5, 6))
        )

        val result = frames.mapTextFrames().toList()

        assertEquals(2, result.size)
        assertEquals("Text1", result[0])
        assertEquals("Text2", result[1])
    }

    @Test
    fun `mapTextFrames ignores close frames`() = runTest {
        val frames = flowOf(
            Frame.Text("Message"),
            Frame.Close(CloseReason(CloseReason.Codes.NORMAL, "Bye")),
            Frame.Text("Another")
        )

        val result = frames.mapTextFrames().toList()

        assertEquals(2, result.size)
        assertEquals("Message", result[0])
        assertEquals("Another", result[1])
    }

    @Test
    fun `mapTextFrames returns empty flow when no text frames`() = runTest {
        val frames = flowOf(
            Frame.Binary(true, byteArrayOf(1, 2, 3)),
            Frame.Close(CloseReason(CloseReason.Codes.NORMAL, "Bye")),
            Frame.Ping(byteArrayOf()),
            Frame.Pong(byteArrayOf())
        )

        val result = frames.mapTextFrames().toList()

        assertEquals(0, result.size)
    }

    @Test
    fun `filterTextFrames keeps only text frames`() = runTest {
        val frames = flowOf(
            Frame.Text("Text1"),
            Frame.Binary(true, byteArrayOf(1, 2, 3)),
            Frame.Text("Text2"),
            Frame.Close(CloseReason(CloseReason.Codes.NORMAL, "Bye"))
        )

        val result = frames.filterTextFrames().toList()

        assertEquals(2, result.size)
        assertTrue(result[0] is Frame.Text)
        assertTrue(result[1] is Frame.Text)
        assertEquals("Text1", result[0].readText())
        assertEquals("Text2", result[1].readText())
    }

    @Test
    fun `filterTextFrames ignores other frame types`() = runTest {
        val frames = flowOf(
            Frame.Binary(true, byteArrayOf(1, 2, 3)),
            Frame.Ping(byteArrayOf()),
            Frame.Pong(byteArrayOf()),
            Frame.Close(CloseReason(CloseReason.Codes.NORMAL, "Done"))
        )

        val result = frames.filterTextFrames().toList()

        assertEquals(0, result.size)
    }

    @Test
    fun `filterTextFrames preserves all text frames`() = runTest {
        val frames = flowOf(
            Frame.Text("A"),
            Frame.Text("B"),
            Frame.Text("C"),
            Frame.Text("D")
        )

        val result = frames.filterTextFrames().toList()

        assertEquals(4, result.size)
        val texts = result.map { it.readText() }
        assertEquals(listOf("A", "B", "C", "D"), texts)
    }

    @Test
    fun `mapTextFrames handles empty strings`() = runTest {
        val frames = flowOf(
            Frame.Text(""),
            Frame.Text("Not empty"),
            Frame.Text("")
        )

        val result = frames.mapTextFrames().toList()

        assertEquals(3, result.size)
        assertEquals("", result[0])
        assertEquals("Not empty", result[1])
        assertEquals("", result[2])
    }

    @Test
    fun `mapTextFrames handles large text frames`() = runTest {
        val largeText = "x".repeat(10000)
        val frames = flowOf(
            Frame.Text(largeText),
            Frame.Text("small")
        )

        val result = frames.mapTextFrames().toList()

        assertEquals(2, result.size)
        assertEquals(10000, result[0].length)
        assertEquals("small", result[1])
    }
}
