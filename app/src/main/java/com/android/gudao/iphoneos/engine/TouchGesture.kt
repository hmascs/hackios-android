package com.android.gudao.iphoneos.engine

/**
 * Pure state machine for one Compose pointer gesture on the letterboxed guest display.
 * Coordinates are already mapped by [TouchMapper]; every accepted down gets exactly one up.
 */
internal class TouchGesture {
    internal enum class Kind { Down, Move, Up }
    internal data class Event(val kind: Kind, val point: GuestPoint)

    private enum class State { New, Rejected, Active, Ended }
    private var state = State.New
    private var lastValid: GuestPoint? = null

    val isActive: Boolean get() = state == State.Active

    /** One physical pointer sequence; an invalid down rejects this entire sequence. */
    fun down(point: GuestPoint?): List<Event> {
        if (state != State.New) return emptyList()
        if (point == null) {
            state = State.Rejected
            return emptyList()
        }
        state = State.Active
        lastValid = point
        return listOf(Event(Kind.Down, point))
    }

    /** Leaving the guest viewport releases at the last valid point and ends this sequence. */
    fun move(point: GuestPoint?): List<Event> {
        if (state != State.Active) return emptyList()
        if (point == null) return releaseAtLastValid()
        lastValid = point
        return listOf(Event(Kind.Move, point))
    }

    /** A normal up uses its valid point; an out-of-viewport up uses the last valid one. */
    fun up(point: GuestPoint?): List<Event> {
        if (state != State.Active) return emptyList()
        val release = point ?: lastValid ?: return finishWithoutEvent()
        lastValid = release
        state = State.Ended
        return listOf(Event(Kind.Up, release))
    }

    /** Pointer loss, coroutine cancellation, navigation or running-state change. */
    fun cancel(): List<Event> = if (state == State.Active) releaseAtLastValid() else emptyList()

    private fun releaseAtLastValid(): List<Event> {
        val point = lastValid ?: return finishWithoutEvent()
        state = State.Ended
        return listOf(Event(Kind.Up, point))
    }

    private fun finishWithoutEvent(): List<Event> {
        state = State.Ended
        return emptyList()
    }
}
