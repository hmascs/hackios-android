package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogTailerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `only new complete lines are returned`() {
        val f = tmp.newFile("serial.log")
        val tailer = LogTailer(f)
        f.appendText("boot: one\nboot: two\npartial")
        assertEquals(listOf("boot: one", "boot: two"), tailer.poll())

        f.appendText(" line done\nnext\n")
        assertEquals(listOf("partial line done", "next"), tailer.poll())
        assertTrue(tailer.poll().isEmpty())
    }

    @Test
    fun `truncation restarts the reader`() {
        val f = tmp.newFile("serial.log")
        val tailer = LogTailer(f)
        f.appendText("old\nold2\n")
        assertEquals(2, tailer.poll().size)

        // QEMU restarts with `-serial file:` and truncates the log.
        f.writeText("fresh\n")
        assertEquals(listOf("fresh"), tailer.poll())
    }

    @Test
    fun `line limit bounds one poll`() {
        val f = tmp.newFile("serial.log")
        f.writeText((1..50).joinToString("\n") { "l$it" } + "\n")
        val tailer = LogTailer(f)
        val first = tailer.poll(maxLines = 10)
        assertEquals(10, first.size)
        assertEquals("l1", first.first())
        // The rest is still delivered on the next poll.
        assertEquals("l11", tailer.poll(maxLines = 10).first())
    }
}
