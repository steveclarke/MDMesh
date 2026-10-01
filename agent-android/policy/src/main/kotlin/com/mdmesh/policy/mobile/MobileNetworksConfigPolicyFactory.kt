package com.mdmesh.policy.mobile

import com.mdmesh.policy.AndroidRestrictionAccess
import com.mdmesh.policy.RestrictionTogglePolicy
import com.mdmesh.policy.wifi.DpmHandle

/** Device Owner only; absent capability on an unsupported device. */
object MobileNetworksConfigPolicyFactory {
    fun create(handle: DpmHandle): MobileNetworksConfigPolicy? =
        MobileNetworksConfigRestrictionPolicy(handle).takeIf { it.isSupported() }
}

internal class MobileNetworksConfigRestrictionPolicy(handle: DpmHandle) :
    RestrictionTogglePolicy(MobileNetworksConfigPolicy.CAPABILITY_KEY, AndroidRestrictionAccess(handle)),
    MobileNetworksConfigPolicy
