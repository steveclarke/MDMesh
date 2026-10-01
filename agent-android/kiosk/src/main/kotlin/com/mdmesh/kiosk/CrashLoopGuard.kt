package com.mdmesh.kiosk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stops relaunching a repeatedly crashing kiosk app without releasing lock-task or HOME.
 * Recovery is persisted synchronously with the fault count and survives process death/reboot.
 * Only an authenticated administrator retry or exit calls [reset].
 */
class CrashLoopGuard(
    private val store: FaultStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    // Also latch an existing installation's tripped counter when upgrading.
    private val recoveryState = MutableStateFlow(store.recovery || store.counter > LOOP_CRASHES)
    val recovery: StateFlow<Boolean> = recoveryState

    init {
        if (recoveryState.value && !store.recovery) {
            store.write(store.counter, store.lastFaultTime, recovery = true)
        }
    }

    /** Count a launch/return inside the window; a tripped guard never expires. */
    @Synchronized
    fun registerFault() {
        if (isCrashLoopDetected()) return
        val time = now()
        val first = store.lastFaultTime
        val fresh = first < 0L || time < first || time - first > LOOP_TIME_SPAN
        val count = if (fresh) 1 else store.counter + 1
        val tripped = count > LOOP_CRASHES
        store.write(count, if (fresh) time else first, tripped)
        recoveryState.value = tripped
    }

    fun isCrashLoopDetected(): Boolean = recoveryState.value

    /** Called only after a successful administrator retry/exit, never by boot or a timer. */
    @Synchronized
    fun reset() {
        store.write(counter = 0, lastFaultTime = -1L, recovery = false)
        recoveryState.value = false
    }

    companion object {
        const val LOOP_TIME_SPAN = 60_000L
        const val LOOP_CRASHES = 3
        const val FAULT_PREFERENCE_NAME = "com.mdmesh.fault"
    }
}

/** Durable, atomic persistence for [CrashLoopGuard]; production writes commit synchronously. */
interface FaultStore {
    val counter: Int
    val lastFaultTime: Long
    val recovery: Boolean
    fun write(counter: Int, lastFaultTime: Long, recovery: Boolean)
}
