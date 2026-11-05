package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression these cover is the phone card that read `build.sh 失败（退出码 1）` over forty
 * lines of `job com.apple...` and nothing else: both truncations kept the front of the message
 * and threw away the traceback at its end (docs/engineering-notes.md 10.1).
 */
class TailTextTest {

    @Test
    fun `a short message is returned untouched`() {
        val text = "build.sh 失败（退出码 1）"
        assertEquals(text, TailText.of(text, 100))
    }

    @Test
    fun `a message of exactly the limit is untouched`() {
        val text = "x".repeat(100)
        assertEquals(text, TailText.of(text, 100))
    }

    @Test
    fun `a long message keeps its END, not its beginning`() {
        // 200 lines: the first 150 are the job list, the last few are the traceback.
        val head = (0 until 150).joinToString("\n") {
            "  job com.apple.daemon$it: /usr/libexec/daemon$it"
        }
        val traceback = """
            Traceback (most recent call last):
              File "mkjobs.py", line 309, in <module>
                job = plistlib.load(f)
            plistlib.InvalidFileException: Invalid file
        """.trimIndent()
        val shown = TailText.of("$head\n$traceback", 300)

        assertTrue("kept the traceback: $shown", shown.contains("InvalidFileException"))
        assertTrue("says how much was dropped: $shown", shown.startsWith("…（前略 "))
        assertTrue("dropped the job list", !shown.contains("com.apple.daemon0:"))
    }

    @Test
    fun `the marker is a line of its own`() {
        val shown = TailText.of("a".repeat(50) + "\nEND", 10)
        assertTrue(shown.lines().first().startsWith("…（前略 "))
        assertEquals("END", shown.lines().last())
    }

    @Test
    fun `reasonOf takes the last line at column zero, not an indented frame`() {
        val lines = listOf(
            "Traceback (most recent call last):",
            "  File \"mkjobs.py\", line 309, in <module>",
            "    job = plistlib.load(f)",
            "plistlib.InvalidFileException: Invalid file",
        )
        assertEquals("plistlib.InvalidFileException: Invalid file", TailText.reasonOf(lines, 140))
    }

    @Test
    fun `reasonOf skips blank lines and the truncation marker`() {
        val lines = listOf("7z: No space left on device", "", "  ", "…（前略 900 字符）")
        assertEquals("7z: No space left on device", TailText.reasonOf(lines, 140))
    }

    @Test
    fun `reasonOf is empty when every line is indented`() {
        assertTrue(TailText.reasonOf(listOf("  a", "   b"), 140).isEmpty())
        assertTrue(TailText.reasonOf(emptyList(), 140).isEmpty())
    }

    @Test
    fun `reasonOf truncates to the budget`() {
        val long = "E" + "x".repeat(500)
        assertEquals(140, TailText.reasonOf(listOf(long), 140).length)
    }
}
