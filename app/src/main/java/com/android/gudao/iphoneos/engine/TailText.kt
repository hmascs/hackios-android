package com.android.gudao.iphoneos.engine

/**
 * The end of a long message.
 *
 * A failed build step says what went wrong in its **last** lines -- a python traceback ends with
 * `SomeError: reason`, 7z ends with `No space left on device`, a compiler with its last error --
 * so a message that has to be shortened must lose its beginning, not its end.
 *
 * Two places in this app shortened from the wrong end and cost a phone session: the build error
 * kept the first 1200 characters of a forty-line job list and cut off the traceback that named
 * the failure, so the card read `build.sh 失败（退出码 1）` and nothing else. See
 * docs/engineering-notes.md 10.1.
 */
object TailText {

    /**
     * [text] unchanged when it fits, otherwise its last [max] characters behind a marker that
     * says how much was dropped. The marker is a line of its own so that the first thing a
     * reader sees is not glued to the fragment before it.
     */
    fun of(text: String, max: Int): String {
        require(max > 0) { "max must be positive: $max" }
        if (text.length <= max) return text
        return "…（前略 ${text.length - max} 字符）\n" + text.takeLast(max)
    }

    /**
     * The one line worth putting in a heading: the last line of [lines] that starts at column
     * zero, since a traceback's `File "...", line N` and `    source` are indented and its
     * `ValueError: ...` is not. Empty when there is nothing of the sort.
     */
    fun reasonOf(lines: List<String>, max: Int): String =
        lines.lastOrNull { it.isNotBlank() && !it.startsWith(" ") && !it.startsWith("…") }
            ?.trim()
            ?.take(max)
            .orEmpty()
}
