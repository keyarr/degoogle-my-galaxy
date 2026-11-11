# Runtime checks implementation plan

Implement [SPECS.md](SPECS.md) by replacing device approval with operation-specific runtime checks while preserving recovery.

Status: in progress. Target resolution, APK validation, restart checks, and Kotlin authorization changes are implemented. Direct APK archive validation tests, KernelSU verification, and device validation remain open.

## Scope

- Keep the existing prepare, userspace restart, post-boot validation, finalize, backup, and restore lifecycle.
- Keep the Android minimum SDK, allowed roots (`/system`, `/system_ext`, `/product`), temporary mounts, and existing backup protections.
- Keep LSPosed/FakeGApps external and informational. APK identity and integrity validation remain mandatory.
- Do not add persistent boot mounts, full kernel reboot, other package replacements, or broader partition support.
- Preserve valid existing snapshots. Restrict verified legacy recovery exceptions to recovery.

## 1. Resolve and validate backend targets — done

Start with `degoogle.sh` and `tests/backend_test.sh`. Keep existing authorization gates during this step.

- Separate the active APK path, original system package path, and mount target for each package.
- For new preparation, obtain stock targets from PackageManager metadata. For existing transactions, load and validate recorded targets before interpreting package absence.
- Treat `/data/app` as an active path, not proof of a system update. Require PackageManager evidence of an updated system package and a validated stock target before cleanup.
- Recover the original path from the matching PackageManager system-package metadata when available. If it cannot be resolved, report the package and stop before removing its update.
- Resolve real paths in the PID 1 mount namespace. Require dedicated package directories under the allowed roots; reject partition roots, shared app parents, data paths, duplicate targets, and ancestor/descendant overlaps.
- Verify that the GMS target provides the privileged placement required by this flow.
- Remove S24 defaults from normal target resolution and update handling. Keep any existing legacy exception isolated to verified recovery.

Acceptance: fixtures cover non-S24 paths, a verified system update, an unresolved update, a data-only installation, unsafe paths, symlink escapes, shared parents, and overlapping targets. Unresolved targets fail before cleanup or mounting.

## 2. Make snapshots and mounts authoritative for existing transactions — done

Continue in the backend and its host tests before changing Kotlin authorization.

- Validate snapshot structure, required fields, schema, transaction identity, model/fingerprint, and all targets before use. Reject malformed or incomplete snapshots without silently falling back to profile paths.
- Preserve compatibility with valid existing snapshots; do not require new fields without a compatible migration or explicit legacy handling.
- Record actual targets, owners, modes, contexts, and existing integrity evidence before mounting. Verify snapshot and journal persistence before proceeding.
- Use recorded targets consistently in fact collection, mount detection, post-boot validation, rollback, and stock reindexing.
- Verify mount ownership using the expected mask, mount root, and backing filesystem identity. Do not infer ownership from a journal entry or a matching path alone.
- Require the GSF mask to be empty. Accept missing GSF only with its verified recorded mount; preserve the recorded target when PackageManager returns no path.
- Route partial transactions to recovery and refuse foreign mounts. Remove payloads only after their owned mounts are confirmed removed.
- Preserve backups and transaction evidence until stock packages are confirmed. Keep `REINDEX_PENDING` while registrations remain stale.

Acceptance: cover app restart with hidden GSF, missing Store during recovery, invalid/missing snapshots, foreign or stacked mounts, partial mounts, failed unmounts, legacy recovery, and stale PackageManager registrations.

## 3. Enforce operation-specific runtime checks

Update backend preflight and the root execution checks used by the app.

- Require actual UID 0 execution and verified access to the PID 1 mount namespace through the execution context that will perform the operation.
- Test a temporary global bind mount by reading its exact expected content and verifying removal. Preserve evidence and report failure if cleanup cannot be confirmed.
- Keep SELinux Enforcing. Test owner, mode, and context reproduction for all three targets and verify the resulting masks and payload metadata. Replace universal `system_file` assumptions with validated target-specific metadata.
- Verify writable mask/transaction storage and retain the existing free-space check. Account for the actual payload size when confirming sufficient mask storage.
- Distinguish an absent PackageManager cache from one that exists but cannot be inspected or modified. Block the affected operation on insufficient access.
- Preserve package, version, integrity, and signing validation before update removal, Store disabling, or mounting. Resolve the CLI validation gap: ZIP magic alone is insufficient. Identify an available verifier and trusted expected metadata; block if required validation cannot be performed, rather than trusting a caller-supplied validation flag.
- Revalidate targets and transaction integrity immediately before changes, including after update cleanup.
- Give restoration its own requirements. It must remain reachable when preparation checks fail and must not require registered Google packages or functional microG/spoofing.

Acceptance: inject failures in root, namespace access, bind cleanup, each target's metadata, storage, persistence, cache access, and APK validation. Check that each failure stops before the affected mutation or preserves a recoverable transaction.

Current status: root, namespace, bind cleanup, target and mask metadata, storage, persistence, and PackageManager cache checks are implemented. The app verifies the F-Droid entry JAR against the pinned repository certificate, verifies the signed index hash, and checks APK package, version, signer, and SHA-256. The shell CLI requires expected APK hashes and checks files before cleanup and after copying. Host tests inject failures in these runtime checks and an APK hash mismatch. Release parsing rejects a missing signer and the validator requires an expected certificate fingerprint; Android PackageManager archive rejection still lacks a focused test.

## 4. Verify restart requests and completion separately

Update the backend reboot path and affected recovery/reboot code.

- Distinguish the AOSP userspace reboot mechanism from KernelSU's emulated soft reboot. Do not infer support from root-manager name or command presence.
- Use available support evidence to select an applicable mechanism. Report unavailable or unresolved support before preparation proceeds.
- Record kernel boot identity and framework process identity/start time before requesting a restart. Persist the request and validation evidence so checks survive app termination.
- A successful command exit means only that the request returned successfully. Require evidence that the framework restarted while the kernel boot identity remained unchanged, followed by package, mount, privileged-placement, and SELinux validation.
- A pre-existing `sys.boot_completed=1` is insufficient proof of a new restart. Handle refused requests, no-op requests, and validation timeouts without marking the transaction complete or repeatedly rebooting.
- Preserve the existing automatic restart guard and never fall back to a full kernel reboot.

Constraints: [AOSP userspace reboot is deprecated from Android 15](https://source.android.com/docs/core/runtime/soft-restart). The [KernelSU implementation](https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/soft_reboot.rs) is a separate mechanism; its installed version and behavior must be checked rather than assumed from upstream source.

Acceptance: cover unsupported mechanisms, rejected requests, successful exits without a restart, completed framework restarts, changed kernel identity, and post-boot validation failures.

Current status: AOSP userspace restart requires the init support property. Restart requests persist kernel and system_server identities, and post-boot validation requires a new framework identity with the same kernel identity plus owned GMS/GSF/Store mounts, expected package paths, privileged GMS, and Enforcing SELinux. Host tests cover unsupported and rejected requests, no-op requests, successful framework restart, changed kernel identity, incomplete boot, mount loss, package validation, and post-boot safety failures. KernelSU behavior remains unverified and device validation remains open.

## 5. Switch Kotlin state detection, authorization, and UI together

Affected files include `SystemFacts.kt`, `SystemPackageInfo.kt`, `DeviceProfile.kt`, `StateDetector.kt`, `Capability.kt`, `CompatibilityEngine.kt`, `BackendRunner.kt`, `AppViewModel.kt`, preferences, and affected screens/resources.

- Expose validated operational targets and transaction/mount evidence in backend facts. Align Kotlin path validation with the backend; neither layer may accept partition roots or guessed targets.
- Derive state from those facts without fixed S24 or Samsung-only dynamic profiles. Use directory-boundary comparisons for package paths.
- Remove manufacturer and signature spoofing from operation requirements. Require privileged placement at the stage where it can be established and verify the actual flag after restart.
- Remove Known-Good promotion and model/firmware approval from authorization. Retain metadata only where useful for diagnostics or transaction integrity.
- Remove experimental authorization and its preference/environment plumbing. Show readiness, pending validation, recovery state, and specific failed checks.
- Ensure direct CLI commands enforce the same operation requirements. Keep preparation and recovery authorization separate.
- Keep `degoogle.sh` and `app/src/main/assets/root/degoogle.sh` identical whenever the backend changes.

Acceptance: unknown/non-Samsung models pass when required evidence passes; a known S24 fails when a requirement fails. Missing/inactive FakeGApps blocks neither masking nor restoration. Backend and Kotlin tests agree on hidden packages, partial transactions, and recovery states.

Current status: runtime-based Kotlin authorization, state detection, and UI changes are implemented. Focused JVM tests passed; device behavior remains unverified.

## 6. Validate the complete lifecycle

- Run focused host checks with `sh tests/backend_test.sh` and affected Kotlin tests with `./gradlew :app:testDebugUnitTest --tests '<affected test class>'`. Broaden checks only for failures or cross-layer concerns.
- Review the diff and verify backend asset parity. Preserve unrelated changes, including the existing untracked `SPECS.md`.
- On the connected device, first repeat read-only checks through the app's actual root execution context. ADB permissions do not establish app permissions.
- Resolve any existing transaction/recovery ambiguity before testing a new preparation. Do not treat missing snapshots as permission to guess targets.
- On-device operational validation must cover temporary bind cleanup, metadata reproduction under Enforcing, preparation, framework restart, privileged placement, finalization, backup, stock restoration, and post-restart reindexing.
- Report host simulation and device validation separately. Other devices remain untested even when preflight passes.

The earlier read-only inspection found Android 16, privileged microG, Companion, absent GSF, and three mask mounts. The ADB session could not enter PID 1's mount namespace; the default transaction directory contained `ROLLBACK_REQUIRED` without a snapshot. These observations require rechecking and do not establish current app readiness or a safe recovery action.

Current status: host backend tests pass (238/0), and the full Kotlin unit suite passes. ShellCheck is unavailable. `adb devices -l` reports no connected device, so the app root context and full operational lifecycle have not been validated on-device.

## Implementation checkpoint

Backend target resolution, APK authenticity checks, authoritative transaction targets, recovery validation, and Kotlin authorization/UI changes are implemented. Host backend tests cover dynamic and unsafe targets, verified and unresolved updates, valid and legacy recovery, cache invalidation, restart identity, post-boot validation, and injected runtime failures. Remaining work is focused Android APK archive tests, verified KernelSU behavior, and on-device lifecycle validation.
