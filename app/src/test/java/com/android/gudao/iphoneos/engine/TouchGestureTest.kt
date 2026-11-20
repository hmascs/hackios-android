package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGestureTest {
    private val first = GuestPoint(0.25f, 0.5f)
    private val second = GuestPoint(0.75f, 0.5f)

    @Test
    fun `valid down leaving viewport releases once and ignores reentry and up`() {
        val gesture = TouchGesture()
        assertEquals(listOf(TouchGesture.Event(TouchGesture.Kind.Down, first)), gesture.down(first))
        assertEquals(listOf(TouchGesture.Event(TouchGesture.Kind.Move, second)), gesture.move(second))
        assertEquals(listOf(TouchGesture.Event(TouchGesture.Kind.Up, second)), gesture.move(null))
        assertTrue(gesture.move(first).isEmpty())
        assertTrue(gesture.up(first).isEmpty())
        assertTrue(gesture.cancel().isEmpty())
    }

    @Test
    fun `invalid down rejects entire pointer sequence`() {
        val gesture = TouchGesture()
        assertTrue(gesture.down(null).isEmpty())
        assertTrue(gesture.move(first).isEmpty())
        assertTrue(gesture.up(first).isEmpty())
        assertTrue(gesture.cancel().isEmpty())
    }

    @Test
    fun `normal up emits once and cancellation after it is harmless`() {
        val gesture = TouchGesture()
        gesture.down(first)
        assertEquals(listOf(TouchGesture.Event(TouchGesture.Kind.Up, second)), gesture.up(second))
        assertTrue(gesture.cancel().isEmpty())
    }

    @Test
    fun `normal up outside releases at last valid guest point`() {
        val gesture = TouchGesture()
        gesture.down(first)
        assertEquals(listOf(TouchGesture.Event(TouchGesture.Kind.Up, first)), gesture.up(null))
        assertTrue(gesture.up(second).isEmpty())
    }

    @Test
    fun `pointer loss cancellation emits one last valid release`() {
        val gesture = TouchGesture()
        gesture.down(first)
        gesture.move(second)
        assertEquals(listOf(TouchGesture.Event(TouchGesture.Kind.Up, second)), gesture.cancel())
        assertTrue(gesture.cancel().isEmpty())
    }
}
