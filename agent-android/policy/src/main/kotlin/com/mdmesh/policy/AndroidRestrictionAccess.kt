package com.mdmesh.policy

import android.os.Build
import android.os.UserManager
import com.mdmesh.policy.wifi.DpmHandle

internal class AndroidRestrictionAccess(
    private val handle: DpmHandle,
    private val users: UserManager? = null,
) : RestrictionAccess {
    override val sdk: Int get() = Build.VERSION.SDK_INT
    override fun isDeviceOwner(): Boolean = handle.dpm.isDeviceOwnerApp(handle.admin.packageName)
    override fun setRestricted(key: String, restricted: Boolean) {
        if (restricted) handle.dpm.addUserRestriction(handle.admin, key)
        else handle.dpm.clearUserRestriction(handle.admin, key)
    }

    // UserManager returns effective restrictions (including other admins), unlike DPM's own bundle.
    override fun isRestricted(key: String): Boolean = requireNotNull(users).hasUserRestriction(key)
}

