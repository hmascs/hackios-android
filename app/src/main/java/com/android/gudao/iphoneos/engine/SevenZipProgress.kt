package com.android.gudao.iphoneos.engine

/**
 * The only progress signal the on-device image build can give while it unpacks.
 *
 * `fetch.sh` unpacks the two Apple disk images with 7-Zip, which refreshes a single line with `\r`
 * twenty times a second; the script converts that to real lines (`tr '\r' '\n'`) so the app can read
 * them. The lines look like
 *
 * ```
 *   0% 3 - rootfs/System/Library/Fonts/...
 *  47% 812 - rootfs/usr/lib/...
 * 100% 1 - rootfs/private/var/...
 * ```
 *
 * and the percentage is the only thing here that matters. Parsing it in a tiny, testable object is
 * deliberate: the previous version of the card froze at the *previous* stage's 100 % because nothing
 * in the pipeline ever turned these lines into progress at all.
 */
object SevenZipProgress {

    /** Leading integer percentage. 7z pads it with spaces and always ends it with '%'. */
    private val PERCENT = Regex("""^\s*(\d{1,3})%""")

    /**
     * Percentage in `line`, or null when the line is not a progress line.
     *
     * Anything above 100 is rejected rather than clamped: a filename that happens to start with
     * "999%" would otherwise pin the bar.
     */
    fun percent(line: String): Int? {
        val raw = PERCENT.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (raw in 0..100) raw else null
    }

    /** True when [line] is 7z reporting progress rather than saying something (a warning, a name). */
    fun isProgress(line: String): Boolean = percent(line) != null
}
