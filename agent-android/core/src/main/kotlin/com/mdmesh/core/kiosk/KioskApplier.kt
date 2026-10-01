package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.CrashLoopGuard
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.kiosk.KioskToggles
import com.mdmesh.kiosk.lockTaskFeatures
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one implementation of "put this device in kiosk with payload P" / "leave kiosk", shared by the
 * `kiosk.enter` / `kiosk.exit` commands and by `config.apply`. Idempotent: entering with the same payload
 * re-asserts the allowlist and features; exiting when not in kiosk is harmless.
 */
class KioskApplier(
    private val kiosk: KioskController,
    private val store: KioskStateStore,
    private val home: KioskHomeSwitch,
    private val homeComponent: ComponentName,
    private val crashGuard: CrashLoopGuard,
) {
    private val mutex = Mutex()

    suspend fun enter(p: KioskApplyPayload, retry: Boolean = false): KioskResult = mutex.withLock {
        enterLocked(p, retry)
    }

    private suspend fun enterLocked(p: KioskApplyPayload, retry: Boolean): KioskResult {
        val previous = store.load()
        val features = lockTaskFeatures(
            KioskToggles(
                home = p.features.home, recents = p.features.recents, notifications = p.features.notifications,
                systemInfo = p.features.systemInfo, keyguard = p.features.keyguard, lockButtons = p.features.lockButtons,
            ),
        )
        val allowed = (p.allowedPackages + listOfNotNull(p.pinPackage)).distinct()
        home.setClaimEnabled(true)
        return when (val r = kiosk.enter(homeComponent, allowed, features)) {
            KioskResult.Ok -> {
                store.save(p)
                if (retry) crashGuard.reset()
                home.showLauncher()
                r
            }
            else -> {
                // Failed re-entry must keep an already-kiosked device's HOME claim.
                if (previous == null) home.setClaimEnabled(false)
                r
            }
        }
    }

    /** Remote command/config paths have already authenticated the administrator on the server. */
    suspend fun exit(): KioskResult = mutex.withLock { exitLocked() }

    private suspend fun exitLocked(): KioskResult = when (val r = kiosk.exit()) {
        KioskResult.Ok -> {
            store.save(null)
            crashGuard.reset()
            home.setClaimEnabled(false)
            home.showOemHome()
            r
        }
        else -> r
    }

    /** Re-check the current persisted password, so an old dialog cannot authenticate a new config. */
    suspend fun exitWithPassword(password: String): KioskResult = mutex.withLock {
        if (authenticates(store.load(), password)) exitLocked() else KioskResult.Failed("Admin password required")
    }

    suspend fun retryWithPassword(password: String): KioskResult = mutex.withLock {
        val p = store.load()
        if (p != null && authenticates(p, password)) enterLocked(p, retry = true)
        else KioskResult.Failed("Admin password required")
    }

    private fun authenticates(p: KioskApplyPayload?, password: String): Boolean =
        p?.exitMode != "remote" && !p?.password.isNullOrBlank() && p?.password == password

    /** Read the latest payload when either store changes; never combine an old payload with a cleared latch. */
    fun launcherState(): Flow<KioskApplyPayload?> =
        combine(store.flow().distinctUntilChanged(), crashGuard.recovery) { _, _ -> store.load() }

    /** True when a kiosk payload is persisted (device believes it is / should be in kiosk). */
    suspend fun isPersisted(): Boolean = store.load() != null
}
