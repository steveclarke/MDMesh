package com.mdmesh.policy

import android.content.Context
import android.os.UserManager
import com.mdmesh.policy.wifi.DpmHandle

/** Live OS readback of the two call/network capabilities, on the current user. */
class CallNetworkStateReader(
    context: Context,
    handle: DpmHandle,
) {
    private val access = AndroidRestrictionAccess(handle, context.getSystemService(UserManager::class.java))
    fun outgoingCallsAllowed(): Boolean? = RestrictionTogglePolicy("outgoingCalls", access).effectiveEnabled()
    fun mobileNetworksConfigAllowed(): Boolean? =
        RestrictionTogglePolicy("mobileNetworksConfig", access).effectiveEnabled()
}
