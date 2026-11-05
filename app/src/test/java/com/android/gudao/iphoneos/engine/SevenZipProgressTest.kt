package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lines fetch.sh produces come out of `7z -bsp1` through `tr '\r' '\n'`. */
class SevenZipProgressTest {

    @Test
    fun `reads a padded percentage from a real progress line`() {
        assertEquals(0, SevenZipProgress.percent("  0% 3 - rootfs/System/Library/Fonts/A.ttc"))
        assertEquals(47, SevenZipProgress.percent(" 47% 812 - rootfs/usr/lib/libobjc.dylib"))
        assertEquals(100, SevenZipProgress.percent("100% 1 - rootfs/private/var"))
    }

    @Test
    fun `accepts a bare percentage without a file name`() {
        assertEquals(12, SevenZipProgress.percent(" 12%"))
    }

    @Test
    fun `ignores anything that is not a progress line`() {
        assertNull(SevenZipProgress.percent("Everything is Ok"))
        assertNull(SevenZipProgress.percent("ERROR: Unsupported Method : rootfs/x"))
        // A path that merely contains a percent sign mid-line is not progress.
        assertNull(SevenZipProgress.percent("extract: 50% of the tree"))
        assertNull(SevenZipProgress.percent(""))
    }

    @Test
    fun `rejects out of range values instead of clamping`() {
        assertNull(SevenZipProgress.percent("999% 1 - x"))
        assertNull(SevenZipProgress.percent("101% 1 - x"))
    }

    @Test
    fun `isProgress matches percent`() {
        assertTrue(SevenZipProgress.isProgress("   5% 4 - a"))
        assertFalse(SevenZipProgress.isProgress("7-Zip 23.01 (aarch64)"))
    }

    @Test
    fun `a carriage-return split line parses after the first segment`() {
        // What tr '\r' '\n' turns one refresh into: several independent lines.
        val lines = "  3% 2 - a\r 31% 9 - b\r 99% 90 - c\r".split('\r')
        assertEquals(listOf(3, 31, 99), lines.mapNotNull { SevenZipProgress.percent(it) })
    }
}
