package com.mdmesh.policy

/** OS access for Device-Owner user restriction strategies. */
internal interface RestrictionAccess {
    val sdk: Int
    fun isDeviceOwner(): Boolean
    fun setRestricted(key: String, restricted: Boolean)
    fun isRestricted(key: String): Boolean
}

/** true allows the feature; false adds its restrictions. Readback comes from the OS. */
internal open class RestrictionTogglePolicy(
    override val capabilityKey: String,
    private val access: RestrictionAccess,
) : TogglePolicy {
    private val restrictions = requireNotNull(UserRestrictions.forKey(capabilityKey))
    private val minSdk = requireNotNull(UserRestrictions.minSdkForKey(capabilityKey))

    override fun isSupported(): Boolean = runCatching {
        access.sdk >= minSdk && access.isDeviceOwner()
    }.getOrDefault(false)

    override fun setEnabled(enabled: Boolean): PolicyOutcome {
        if (!isSupported()) return PolicyOutcome.Unsupported
        return runCatching {
            restrictions.forEach { access.setRestricted(it, !enabled) }
            PolicyOutcome.Applied
        }.getOrElse { PolicyOutcome.Failed(it.message ?: "$capabilityKey setEnabled failed") }
    }

    /** null means unsupported or unreadable, never an assumed success. */
    fun effectiveEnabled(): Boolean? {
        if (!isSupported()) return null
        return runCatching { restrictions.none(access::isRestricted) }.getOrNull()
    }
}
