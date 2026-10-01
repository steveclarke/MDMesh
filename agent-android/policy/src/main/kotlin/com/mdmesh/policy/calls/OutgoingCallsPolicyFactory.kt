package com.mdmesh.policy.calls

import com.mdmesh.policy.AndroidRestrictionAccess
import com.mdmesh.policy.RestrictionTogglePolicy
import com.mdmesh.policy.wifi.DpmHandle

/** Device Owner only; absent capability on an unsupported device. */
object OutgoingCallsPolicyFactory {
    fun create(handle: DpmHandle): OutgoingCallsPolicy? =
        OutgoingCallsRestrictionPolicy(handle).takeIf { it.isSupported() }
}

internal class OutgoingCallsRestrictionPolicy(handle: DpmHandle) :
    RestrictionTogglePolicy(OutgoingCallsPolicy.CAPABILITY_KEY, AndroidRestrictionAccess(handle)),
    OutgoingCallsPolicy
