package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.command.handlers.KioskEnterHandler
import com.mdmesh.core.command.handlers.KioskExitHandler
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.CrashLoopGuard
import com.mdmesh.kiosk.InMemoryFaultStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.ProtocolJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class KioskApplierTest {
    private class FakeController(private val enterResult: KioskResult, private val exitResult: KioskResult = KioskResult.Ok) : KioskController {
        var entered: List<String>? = null; var exited = false
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult { entered = allowedPackages; return enterResult }
        override fun exit(): KioskResult { exited = true; return exitResult }
        override fun isLocked(context: android.content.Context): Boolean = false
        override fun allowedPackages(): List<String> = entered ?: emptyList()
    }
    private class FakeHome : KioskHomeSwitch {
        val log = mutableListOf<String>()
        override fun setClaimEnabled(enabled: Boolean) { log += "claim:$enabled" }
        override fun showLauncher() { log += "launcher" }
        override fun showOemHome() { log += "oem" }
    }
    private val home = ComponentName("com.mdmesh.agent", "com.mdmesh.agent.KioskHomeAlias")

    @Test fun `enter ok persists payload and shows launcher`() = runTest {
        val c = FakeController(KioskResult.Ok); val h = FakeHome(); val store = InMemoryKioskStateStore()
        val r = KioskApplier(c, store, h, home, CrashLoopGuard(InMemoryFaultStore()))
            .enter(KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a")))
        assertEquals(KioskResult.Ok, r)
        assertEquals(listOf("com.a"), c.entered)
        assertEquals("com.a", store.load()?.pinPackage)
        assertEquals(listOf("claim:true", "launcher"), h.log)
    }
    @Test fun `enter unsupported reverts the home claim and persists nothing`() = runTest {
        val h = FakeHome(); val store = InMemoryKioskStateStore()
        val a = KioskApplier(
            FakeController(KioskResult.Unsupported), store, h, home, CrashLoopGuard(InMemoryFaultStore()),
        )
        val r = a.enter(KioskApplyPayload())
        assertEquals(KioskResult.Unsupported, r)
        assertNull(store.load())
        assertEquals(listOf("claim:true", "claim:false"), h.log)
    }
    @Test fun `exit clears store and returns to oem home`() = runTest {
        val h = FakeHome(); val store = InMemoryKioskStateStore(); store.save(KioskApplyPayload())
        val a = KioskApplier(FakeController(KioskResult.Ok), store, h, home, CrashLoopGuard(InMemoryFaultStore()))
        assertTrue(a.isPersisted())
        assertEquals(KioskResult.Ok, a.exit())
        assertFalse(a.isPersisted())
        assertEquals(listOf("claim:false", "oem"), h.log)
    }

    private fun trippedGuard(): CrashLoopGuard = CrashLoopGuard(InMemoryFaultStore(), { 0L }).apply {
        repeat(4) { registerFault() }
    }

    @Test fun `same payload boot replay preserves recovery allowlist and HOME`() = runTest {
        val p = KioskApplyPayload(mode = "single", pinPackage = "com.a")
        val store = InMemoryKioskStateStore(p); val guard = trippedGuard()
        val c = FakeController(KioskResult.Ok); val h = FakeHome()
        assertEquals(KioskResult.Ok, KioskApplier(c, store, h, home, guard).enter(p))
        assertTrue(guard.isCrashLoopDetected())
        assertEquals(p, store.load())
        assertFalse(c.exited)
        assertEquals(listOf("com.a"), c.entered)
        assertEquals(listOf("claim:true", "launcher"), h.log)
    }

    @Test fun `failed re-entry does not disable existing HOME or erase recovery`() = runTest {
        val p = KioskApplyPayload(); val store = InMemoryKioskStateStore(p)
        val guard = trippedGuard(); val h = FakeHome()
        val a = KioskApplier(FakeController(KioskResult.Failed("dpm")), store, h, home, guard)
        assertEquals(KioskResult.Failed("dpm"), a.enter(p, retry = true))
        assertTrue(guard.isCrashLoopDetected())
        assertEquals(p, store.load())
        assertEquals(listOf("claim:true"), h.log)
    }

    @Test fun `blank unset wrong and remote-only passwords deny exit and retry`() = runTest {
        for (configured in listOf(null, "", "   ", "secret")) {
            val p = KioskApplyPayload(exitMode = "visible", password = configured)
            val store = InMemoryKioskStateStore(p); val guard = trippedGuard()
            val c = FakeController(KioskResult.Ok); val h = FakeHome()
            val a = KioskApplier(c, store, h, home, guard)
            for (input in listOf("", "   ", "wrong")) {
                assertTrue(a.exitWithPassword(input) is KioskResult.Failed)
                assertTrue(a.retryWithPassword(input) is KioskResult.Failed)
            }
            assertFalse(c.exited); assertNull(c.entered); assertTrue(h.log.isEmpty())
            assertEquals(p, store.load()); assertTrue(guard.isCrashLoopDetected())
        }
        val remote = InMemoryKioskStateStore(KioskApplyPayload(exitMode = "remote", password = "secret"))
        val c = FakeController(KioskResult.Ok)
        val a = KioskApplier(c, remote, FakeHome(), home, trippedGuard())
        assertTrue(a.exitWithPassword("secret") is KioskResult.Failed)
        assertFalse(c.exited)
    }

    @Test fun `authenticated local retry clears faults but retains kiosk`() = runTest {
        val p = KioskApplyPayload(exitMode = "visible", password = "secret", pinPackage = "com.a")
        val store = InMemoryKioskStateStore(p); val guard = trippedGuard()
        val c = FakeController(KioskResult.Ok); val h = FakeHome()
        assertEquals(KioskResult.Ok, KioskApplier(c, store, h, home, guard).retryWithPassword("secret"))
        assertFalse(guard.isCrashLoopDetected()); assertFalse(c.exited)
        assertEquals(p, store.load()); assertEquals(listOf("claim:true", "launcher"), h.log)
    }

    @Test fun `authenticated remote retry of identical payload clears recovery`() = runTest {
        val p = KioskApplyPayload(); val store = InMemoryKioskStateStore(p); val guard = trippedGuard()
        val a = KioskApplier(FakeController(KioskResult.Ok), store, FakeHome(), home, guard)
        assertEquals(KioskResult.Ok, a.enter(p, retry = true))
        assertFalse(guard.isCrashLoopDetected()); assertEquals(p, store.load())
    }

    @Test fun `changed authenticated configuration permits retry without exit`() = runTest {
        val old = KioskApplyPayload(pinPackage = "com.broken"); val guard = trippedGuard()
        val store = InMemoryKioskStateStore(old); val c = FakeController(KioskResult.Ok)
        val changed = old.copy(pinPackage = "com.fixed")
        assertEquals(KioskResult.Ok, KioskApplier(c, store, FakeHome(), home, guard).enter(changed, retry = true))
        assertFalse(guard.isCrashLoopDetected()); assertFalse(c.exited)
        assertEquals(changed, store.load())
    }

    @Test fun `authenticated exit clears recovery only on successful device exit`() = runTest {
        for (outcome in listOf(KioskResult.Ok, KioskResult.Failed("dpm"))) {
            val p = KioskApplyPayload(exitMode = "visible", password = "secret")
            val store = InMemoryKioskStateStore(p); val guard = trippedGuard(); val h = FakeHome()
            val a = KioskApplier(FakeController(KioskResult.Ok, outcome), store, h, home, guard)
            assertEquals(outcome, a.exitWithPassword("secret"))
            if (outcome == KioskResult.Ok) {
                assertNull(store.load()); assertFalse(guard.isCrashLoopDetected())
                assertEquals(listOf("claim:false", "oem"), h.log)
            } else {
                assertEquals(p, store.load()); assertTrue(guard.isCrashLoopDetected()); assertTrue(h.log.isEmpty())
            }
        }
    }

    @Test fun `current password rejects a stale dialog after remote configuration changes`() = runTest {
        val p = KioskApplyPayload(exitMode = "visible", password = "new-password")
        val store = InMemoryKioskStateStore(p); val c = FakeController(KioskResult.Ok)
        val guard = trippedGuard(); val a = KioskApplier(c, store, FakeHome(), home, guard)
        assertTrue(a.exitWithPassword("old-password") is KioskResult.Failed)
        assertTrue(a.retryWithPassword("old-password") is KioskResult.Failed)
        assertFalse(c.exited); assertTrue(guard.isCrashLoopDetected()); assertEquals(p, store.load())
    }

    @Test fun `server command handlers retry identical kiosk and exit passwordless recovery`() = runTest {
        val p = KioskApplyPayload(password = null); val store = InMemoryKioskStateStore(p)
        val guard = trippedGuard(); val c = FakeController(KioskResult.Ok); val h = FakeHome()
        val a = KioskApplier(c, store, h, home, guard)
        val enter = CommandEnvelope(
            commandId = "retry", issuedAt = "2026-01-01T00:00:00Z", type = "kiosk.enter",
            payload = ProtocolJson.json.encodeToJsonElement(KioskApplyPayload.serializer(), p).jsonObject,
        )
        assertEquals(CommandStatus.DONE, KioskEnterHandler(a).handle(enter).status)
        assertFalse(guard.isCrashLoopDetected()); assertEquals(p, store.load()); assertFalse(c.exited)
        repeat(4) { guard.registerFault() }
        val exit = CommandEnvelope(commandId = "exit", issuedAt = enter.issuedAt, type = "kiosk.exit")
        assertEquals(CommandStatus.DONE, KioskExitHandler(a).handle(exit).status)
        assertTrue(c.exited); assertNull(store.load()); assertFalse(guard.isCrashLoopDetected())
        assertEquals(listOf("claim:true", "launcher", "claim:false", "oem"), h.log)
    }

    @Test fun `cleared recovery never renders a queued stale payload after administrator exit`() = runTest {
        val p = KioskApplyPayload(pinPackage = "com.old")
        // Model a persisted write whose flow emission is still queued on the UI thread.
        val store = object : KioskStateStore {
            private var latest: KioskApplyPayload? = p
            override suspend fun save(payload: KioskApplyPayload?) { latest = payload }
            override suspend fun load() = latest
            override fun flow() = flowOf(p)
        }
        val a = KioskApplier(FakeController(KioskResult.Ok), store, FakeHome(), home, trippedGuard())
        assertEquals(p, a.launcherState().first())
        a.exit()
        assertNull(a.launcherState().first())
    }
}
