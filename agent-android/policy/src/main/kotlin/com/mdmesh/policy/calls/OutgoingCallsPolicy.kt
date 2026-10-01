package com.mdmesh.policy.calls

import com.mdmesh.policy.TogglePolicy

/** Allows ordinary outgoing calls; emergency calls remain available. */
interface OutgoingCallsPolicy : TogglePolicy {
    companion object {
        const val CAPABILITY_KEY = "outgoingCalls"
    }
}
