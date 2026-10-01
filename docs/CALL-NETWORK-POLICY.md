# Outgoing calls and mobile-network settings

The configuration editor has independent **Outgoing calls** and **Mobile-network settings**
controls. Each is **Unmanaged** (JSON `null`), **Allow** (`true`) or **Block** (`false`).
Assign a configuration to a phone; to enable calls on only one phone, copy its configuration,
change Outgoing calls to Allow, leave Mobile-network settings at Block, and assign the copy
only to that phone. Configuration saves, copies and API responses preserve explicit false.

These policies require Device Owner and are advertised as `outgoingCalls` and
`mobileNetworksConfig`. They apply Android's `DISALLOW_OUTGOING_CALLS` and
`DISALLOW_CONFIG_MOBILE_NETWORKS` user restrictions respectively. They do not erase SIMs,
change telephone numbers, disable mobile data or block emergency calls. Physical-device
voice service and OEM-specific SIM settings still need device testing.

The additive `/agent/v1` desired policies are omitted for agents that do not advertise them.
The current revision and sync status are computed for that device's capabilities. Null is
unmanaged: no request to change the OS restriction, and no automatic undo of an earlier block.
To remove a block, explicitly select Allow.

The device detail page shows **Outgoing calls (OS)** and **Mobile-network settings (OS)**
from the current user's effective Android `UserManager` restrictions at each check-in, not
from command acknowledgement or the saved configuration. The check-in state and authenticated
`GET /rest/private/agent/v1/devices/{deviceId}/state` carry nullable `outgoingCallsAllowed` and
`mobileNetworksConfigAllowed`. Unknown means the agent is old, not Device Owner, or cannot
read the restriction; it never implies that calls/settings were allowed. A new snapshot with
unknown state replaces prior readback so stale successes are not retained.
