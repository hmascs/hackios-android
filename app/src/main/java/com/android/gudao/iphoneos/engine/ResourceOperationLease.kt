package com.android.gudao.iphoneos.engine

/**
 * Synchronous single-owner gate for operations that deploy or mutate shared run assets.
 * Acquire this before launching a coroutine so UI and MCP calls cannot race during dispatch.
 */
internal class ResourceOperationLease {
    enum class Operation { Start, Compile, Diagnostics, DeployAssets, ResetVars }

    class Token internal constructor(internal val id: Long, val operation: Operation)

    private var nextId = 0L
    private var owner: Token? = null

    /** The external busy snapshot is evaluated under the same monitor as the reservation. */
    @Synchronized
    fun tryAcquire(operation: Operation, externallyBusy: () -> Boolean): Token? {
        if (owner != null || externallyBusy()) return null
        val token = Token(++nextId, operation)
        owner = token
        return token
    }

    /** Only the current owner can release; stale finally/completion handlers are harmless. */
    @Synchronized
    fun release(token: Token): Boolean {
        if (owner !== token) return false
        owner = null
        return true
    }

    /** Cleanup and release are atomic with respect to a later reservation. Stale callbacks do nothing. */
    @Synchronized
    fun finish(token: Token, cleanup: () -> Unit): Boolean {
        if (owner !== token) return false
        try {
            cleanup()
        } finally {
            owner = null
        }
        return true
    }

    @Synchronized
    fun currentOperation(): Operation? = owner?.operation
}
