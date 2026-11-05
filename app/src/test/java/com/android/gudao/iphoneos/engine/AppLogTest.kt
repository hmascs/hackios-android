package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 应用日志是"发消息闪退"这类问题的唯一现场，所以它自己的行为也要被钉住：
 * 落盘、末尾快照、以及换行不会把一条日志撑成两行（导出的每一行都应该是完整的一条）。
 */
class AppLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writes to app log and keeps a tail for the crash handler`() {
        AppLog.attach(tmp.root)
        AppLog.i("Test", "first line")
        AppLog.e("Test", "second line", IllegalStateException("boom"))

        val text = File(tmp.root, "app.log").readText()
        assertTrue(text.contains("I/Test: first line"))
        assertTrue(text.contains("E/Test: second line"))
        assertTrue(text.contains("IllegalStateException: boom"))

        val tail = AppLog.tailLines(2)
        assertEquals(2, tail.size)
        assertTrue(tail.last().contains("second line"))
    }

    @Test
    fun `multi line messages stay on one line`() {
        AppLog.attach(tmp.root)
        AppLog.w("Test", "a\nb\r\nc")
        val line = AppLog.tailLines(1).single()
        assertTrue(line.contains("a b  c"))
        assertEquals(1, line.lines().size)
    }
}
