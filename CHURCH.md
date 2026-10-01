# The church's MDMesh fork

This fork manages six church-owned phones while keeping their existing eSIMs and numbers. Physical Nubia enrollment, carrier behavior and QMix still require a one-phone pilot. No production installation is implied by an emulator build.

## Branches and differences

`main` stays the upstream reference. `church/main` is the supported church integration branch and the fork's default branch. Three focused branches start from upstream `main` and may be offered upstream only after Steve reviews their draft PRs:

- `feat/call-and-mobile-network-policy`: optional configuration controls for outgoing calls and mobile-network settings, console tri-state fields, capability-gated desired state, Android owner restrictions and effective-state readback. Null means unmanaged; true allows; false restricts. Assign a separate configuration to the phone permitted to call. No eSIM deletion or emergency-call blocking.
- `fix/kiosk-locked-recovery`: repeated kiosk app crashes/exits show locked recovery rather than unlocking. Recovery persists across reboot. Remote authenticated commands or a configured nonempty administrator password are the exit paths.
- `fix/apk-download-client`: APK requests use a separate HTTP client, preserve external HTTPS origins, and carry no MDM API interception or credentials. SHA-256 verification remains mandatory when supplied by the install command.

`church/main` adds only this runbook and the church sync automation beyond the topic merges. Merge topic branches with `--no-ff`: the sync script identifies church-only commits from the first-parent non-merge history. Do not squash/cherry-pick topic changes onto the integration spine.

## Weekly upstream sync

`.github/workflows/church-sync.yml` runs Monday at 05:23 UTC and can be run manually. GitHub scheduled workflows run only on the default branch, so `church/main` must remain the fork's default. Enable GitHub Actions and Issues on this fork so failed runs can open their tracking issue. The workflow runs only on `steveclarke/MDMesh`, uses its short-lived repository token, and never opens anything on upstream.

`scripts/church/sync.py` fetches upstream `main`, rebases all three topic branches in disposable worktrees, reconstructs the integration through merges, and replays church-only commits. It runs upstream T0 server, console and agent commands on every candidate, plus sync tests and the edge check on integration. All checks must pass before one atomic, lease-protected push updates the four branches. A rebase, test or publication failure leaves the remote branch set unchanged and opens a fork issue linking the failed run. If issue creation itself fails, the Actions run remains the failure record.

Branches are intentionally rewritten by sync. After a successful run, save local work and fetch, then rebase your work onto the new origin branch. Never force-push a stale clone. A conflict requires a human to reconcile the affected topic patch against upstream, run its touched tests, push that focused fix, and rerun the workflow. Inspect every failure; do not skip checks to make sync green.

Local dry run (JDK 17, Maven, Node 24, Android SDK and Docker installed):

```sh
git clone git@github.com:steveclarke/MDMesh.git
cd MDMesh
git switch church/main
python3 scripts/church/sync.py --dry-run
```

Disposable candidate directories are retained with their path printed so failures can be examined. On Mac remove them with `trash`, not `rm -rf`. Do not run the script inside an uncommitted worktree or delete another run's worktrees. Before merging future changes, also run the upstream real-server T1 suite described in CONTRIBUTING.md; weekly T0 does not replace it.

## Build a signed church agent

Never enroll the real fleet with the debug build or debug key. Use one stable release keystore kept outside this repository with an encrypted backup and named custodian; every update must use the same signing certificate. Loss of the key can require resetting/re-enrolling every phone. This task did not create a production signing key.

Follow RELEASING.md for the upstream build conventions, but do not push upstream-style `v*` tags from this fork: the inherited release workflow targets upstream image names. Make church releases manually until separate fork image/release automation is reviewed.

Supply the existing signing key from the external secret store into a protected temporary file. Set `MDM_RELEASE_STORE_FILE`, `MDM_RELEASE_STORE_PASSWORD`, `MDM_RELEASE_KEY_ALIAS`, and `MDM_RELEASE_KEY_PASSWORD` in the shell from the secret store without printing them or writing a repository `.env`. Inspect the installed/released APK version codes and choose a code greater than every one. The example below uses 302 (above upstream 0.3.1's 301); increase it if any deployed build is already newer:

```sh
cd agent-android
./gradlew --no-daemon detekt testDebugUnitTest lintVitalRelease assembleRelease \
  -PversionName=0.3.1-church.1 -PversionCode=302
```

Use `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` and record the APK SHA-256 and signing-certificate checksum in the release notes. Create a draft release on **steveclarke/MDMesh** using a `church-*` tag; this does not trigger inherited `v*` release publishing. Match the QR's agent package, downloadable URL and checksum to the exact signed artifact. Prove factory-welcome QR/Play Protect on one Nubia, install a same-key update and observe server check-in before enrolling the remaining five. Keep release secrets out of logs, commits and reports.

## Volunteer operation and recovery

Label P01–P06 and record intended kiosk/QMix role. Default to calls blocked and mobile-network settings restricted. An administrator clones/assigns a calls-allowed profile to only the intended phone and verifies its state after check-in. Null/unmanaged does not undo an existing restriction; explicitly allow it when removing the restriction. Incoming calls, carrier/eSIM/OEM menus and actual emergency-call behavior are physical-device checks.

A crashed app remains in locked recovery, including offline/reboot. A borrower reports the phone label; an authenticated administrator repairs/updates the app and sends kiosk.enter to retry, or deliberately sends kiosk.exit for maintenance. Do not hand a borrower an administrator password. Keep the server reachable to avoid being unable to perform remote-only recovery.

A Device Owner does not prevent every firmware/recovery attack. Keep physical custody, verify safe-boot/reset/debugging restrictions in the pilot, and use a restricted attendance session and purpose-limited web kiosk. Ordinary Chrome lock task alone is not a website URL allowlist.

The emulator's Android recovery wipe failure is not proof of Nubia wipe behavior. Pilot reset/re-enrollment and eSIM retention with Steve; never select an eSIM-erasure option or use `WIPE_EUICC`.

## Recorded fork verification

On 2026-09-30 the integrated product code passed the affected upstream T0 builds/tests and the real-server T1 suite (56 passed, none failed). An enrolled Android 16/API 36 emulator demonstrated ordinary calls blocked, calls allowed only after assigning its separate profile, mobile-network settings controlled by admin, external HTTPS APK installation and update, and checksum rejection without replacing the installed app. Kiosk crash recovery stayed in OS lock-task through Back/Home/Recents/Settings attempts and an offline reboot; authenticated remote retry and exit worked. These results authorize preparing a one-Nubia pilot, not enrolling the fleet.

The lab used an ignored debug-only cleartext manifest for its loopback server and an ephemeral CA for its separate HTTPS APK origin. Production source retains normal TLS behavior; untrusted HTTPS is refused by the download regression tests. No production signing key, real phone data or carrier call was used. Android's wipe reached recovery setup but the emulator lacked `/misc`, so uncrypt could not write the bootloader control block. Physical reset and eSIM retention remain unproven.
