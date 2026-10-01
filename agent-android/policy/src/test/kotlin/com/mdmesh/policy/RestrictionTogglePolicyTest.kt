package com.mdmesh.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionTogglePolicyTest {
    private class FakeAccess : RestrictionAccess {
        override var sdk = 24
        var owner = true
        var fail = false
        var ownerCheckFails = false
        val restrictions = mutableSetOf<String>()
        override fun isDeviceOwner(): Boolean {
            check(!ownerCheckFails) { "OS owner check failed" }
            return owner
        }
        override fun setRestricted(key: String, restricted: Boolean) {
            check(!fail) { "OS rejected change" }
            if (restricted) restrictions.add(key) else restrictions.remove(key)
        }
        override fun isRestricted(key: String): Boolean {
            check(!fail) { "OS read failed" }
            return key in restrictions
        }
    }

    @Test
    fun `calls can be allowed while network settings remain locked and readback is live`() {
        val os = FakeAccess()
        val calls = RestrictionTogglePolicy("outgoingCalls", os)
        val networks = RestrictionTogglePolicy("mobileNetworksConfig", os)
        assertEquals(PolicyOutcome.Applied, calls.setEnabled(false))
        assertEquals(PolicyOutcome.Applied, networks.setEnabled(false))
        assertEquals(false, calls.effectiveEnabled())
        assertEquals(false, networks.effectiveEnabled())
        assertEquals(PolicyOutcome.Applied, calls.setEnabled(true))
        assertEquals(true, calls.effectiveEnabled())
        assertEquals(false, networks.effectiveEnabled())
        assertEquals(setOf("no_config_mobile_networks"), os.restrictions)
        os.restrictions.add("no_outgoing_calls")
        assertEquals(false, calls.effectiveEnabled())
    }

    @Test
    fun `nonowner and unsupported SDK cannot apply or claim state`() {
        val os = FakeAccess()
        val policy = RestrictionTogglePolicy("outgoingCalls", os)
        os.owner = false
        assertFalse(policy.isSupported())
        assertEquals(PolicyOutcome.Unsupported, policy.setEnabled(false))
        assertNull(policy.effectiveEnabled())
        assertTrue(os.restrictions.isEmpty())
        os.owner = true
        os.sdk = 20
        assertEquals(PolicyOutcome.Unsupported, policy.setEnabled(false))
        assertNull(policy.effectiveEnabled())
        os.sdk = 21
        assertTrue(policy.isSupported())
    }

    @Test
    fun `OS failures report failure and unknown readback`() {
        val os = FakeAccess()
        val policy = RestrictionTogglePolicy("mobileNetworksConfig", os)
        os.fail = true
        assertTrue(policy.setEnabled(false) is PolicyOutcome.Failed)
        assertNull(policy.effectiveEnabled())
    }
    @Test
    fun `unreadable ownership never breaks a checkin or claims support`() {
        val os = FakeAccess()
        os.ownerCheckFails = true
        val policy = RestrictionTogglePolicy("outgoingCalls", os)
        assertFalse(policy.isSupported())
        assertEquals(PolicyOutcome.Unsupported, policy.setEnabled(false))
        assertNull(policy.effectiveEnabled())
    }

}
