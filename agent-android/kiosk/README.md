# :kiosk

COSU (Corporate-Owned Single-Use) lock-task / kiosk control, built clean-room on the
native `DevicePolicyManager` lock-task APIs (Headwind's real COSU engine is closed
"Pro"; OSS only ships an overlay hack).

This module does **not** depend on `:policy` — its result type (`KioskResult`) is
defined locally.

## Contents

| Type | Role |
|------|------|
| `KioskController` | interface; never throws — returns `KioskResult` |
| `KioskResult` | `Ok` / `Failed(reason)` / `Unsupported` |
| `LockTaskKioskController` | real impl over `DevicePolicyManager` lock-task APIs |
| `StubKioskController` | no-op fallback for non-Device-Owner / tests (all ops `Unsupported`) |
| `CrashLoopGuard` | crash-loop protection (ported from Headwind `CrashLoopProtection`) |
| `FaultStore` | atomic counter and locked-recovery persistence |
| `SharedPrefsFaultStore` | production store (SharedPreferences, synchronous `commit()`) |
| `InMemoryFaultStore` | Android-free store for unit tests |

## What `LockTaskKioskController` does

Constructed directly with `(dpm: DevicePolicyManager, admin: ComponentName)` — no
`DpmHandle`. Guards `isDeviceOwnerApp` + `SDK_INT` on every call.

- **`enter(homeComponent, allowedPackages)`** — `setLockTaskPackages` (allowlist +
  the agent's own package), `setLockTaskFeatures(HOME | OVERVIEW | GLOBAL_ACTIONS |
  NOTIFICATIONS)` on API 28+, and `addPersistentPreferredActivity` for
  `ACTION_MAIN` + `CATEGORY_HOME` + `CATEGORY_DEFAULT` so the agent owns HOME.
- **`exit()`** — clears the allowlist, `clearPackagePersistentPreferredActivities`,
  resets lock-task features to default (API 28+).
- **`isLocked(context)`** — `ActivityManager.lockTaskModeState == LOCK_TASK_MODE_LOCKED`.
- **`allowedPackages()`** — `getLockTaskPackages(admin)`.

Lock-task base APIs need API 21; `setLockTaskFeatures` needs API 28. The module's
`minSdk` is 24, so the base APIs are always available and only features are guarded.
Real lock-task requires Device Owner (provisioned via `:app`).

## How `:app` wires it

`:kiosk` configures device-level policy; the kiosk activity in `:app` completes the
loop. `:app` is responsible for (done there, not here):

1. **Manifest** — the HOME/launcher activity declares
   `android:lockTaskMode="if_whitelisted"`, plus the launcher intent filter:

   ```xml
   <activity
       android:name=".kiosk.KioskActivity"
       android:lockTaskMode="if_whitelisted"
       android:launchMode="singleTask"
       android:exported="true">
       <intent-filter>
           <action android:name="android.intent.action.MAIN" />
           <category android:name="android.intent.category.HOME" />
           <category android:name="android.intent.category.DEFAULT" />
       </intent-filter>
   </activity>
   ```

2. **Start/stop lock task** — once `LockTaskKioskController.enter(...)` has
   allowlisted the package, the foreground kiosk activity calls
   `startLockTask()` (and `stopLockTask()` on exit).

3. **`DISALLOW_CREATE_WINDOWS`** — set in `onLockTaskModeEntering(...)` and cleared
   in `onLockTaskModeExiting(...)` to block apps from drawing over the kiosk.

4. **Crash-loop wiring** — the single-app launcher registers each launch attempt with
   the singleton `CrashLoopGuard`. On the fourth attempt inside 60 seconds it stops
   launching the app and shows **Kiosk recovery**. It does not call `exit()`, clear
   the allowlist or disable the HOME alias. Recovery calls `startLockTask()` just
   like the normal kiosk screen.

## Locked recovery and administrator actions

Fault count, window start and recovery latch are committed together in
`SharedPrefsFaultStore`. Recovery survives process death, reboot and clock changes;
waiting does not clear it. Reapplying the same desired kiosk on boot also leaves
recovery intact. A changed kiosk configuration or an explicit authenticated
`kiosk.enter` retries the app while retaining kiosk. If it still fails, recovery
latches again. An unchanged configuration replay is not an administrator retry.

`kiosk.exit` and removal of an existing desired kiosk arrive through the authenticated
server command channel. They release device-owner policy and HOME only on an
administrator action. Local exit and **Admin retry** require a configured nonblank
password; blank/unset passwords never allow a local exit. `exitMode = remote`
has no local actions, even if a password is present. The password is checked
against the current persisted payload, so a stale dialog cannot bypass a new
configuration. Failed retry/exit leaves persisted recovery intact.

The threshold/counting, durable latch across guard reconstruction, boot replay,
password denial and administrator retry/exit are exercised by JVM tests in
`:kiosk` and `:core`. Actual Android lock-task/HOME retention still needs a
Device-Owner device or emulator run.
