package com.mdmesh.policy.mobile

import com.mdmesh.policy.TogglePolicy

/** Allows mobile-network settings changes without disabling service or erasing SIMs. */
interface MobileNetworksConfigPolicy : TogglePolicy {
    companion object {
        const val CAPABILITY_KEY = "mobileNetworksConfig"
    }
}
