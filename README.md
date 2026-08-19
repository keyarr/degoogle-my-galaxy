# DeGoogle

DeGoogle is a root Android app that turns the shell procedure for temporarily replacing Google Play Services / GSF / Play Store with **microG Services + microG Companion** into a near one-click process. It prepares a volatile microG environment, performs a userspace soft reboot, and can back up microG data for restoration.

> [!WARNING]
> DeGoogle modifies privileged Android state and is still experimental.
>
> It has been tested only with **KernelSU** on the **Samsung Galaxy S24 Ultra (SM-S928x)**. No changes are applied unless root access, critical capabilities, and rollback safety are verified; non-homologated firmware additionally requires explicit opt-in.

## Project status

The main lifecycle is validated on **Samsung Galaxy S24 Ultra (SM-S928B)** running **Android 16 / One UI 8.5** with KernelSU:

```text
STOCK → PREPARED → (userspace soft reboot) → MICROG_ACTIVE → (backup) → MICROG_ACTIVE_BACKED_UP → (restore) → STOCK
```

Validation status:

- Shell backend: 94/94 test scenarios passing in host harness
- Android unit tests: 52/52 passing
- Lifecycle validated on-device
- microG official certificate & FakeGApps signature spoofing probe verified
- microG data backup and restoration validated on-device
- Restore-to-stock flow validated
- Localization: English and Brazilian Portuguese (pt-BR)

Material 3 UI screenshots:

<img width="280" alt="Home - Prepared" src="https://github.com/user-attachments/assets/0c148a0a-8923-486c-8067-8f2148200a57" />
<img width="280" alt="Diagnostics" src="https://github.com/user-attachments/assets/0f8b068f-bb02-47a6-b0e8-dab120bcaaa8" />
<img width="280" alt="Backup" src="https://github.com/user-attachments/assets/a6e35626-ddd2-430c-a777-b4d9c601ab12" />

**Português:** [`README.pt-BR.md`](README.pt-BR.md)

## Supported device profile

The initial Known-Good database contains one validated profile:

- **Samsung Galaxy S24 Ultra (SM-S928x)**
- **KernelSU root**
- **Required module: `fakegapps`** (provides signature spoofing required by microG. Ensure this module is installed and active before activating microG.)
- Main target: devices rooted through [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy); But anyone who unlocked the bootloader and rooted their device the "conventional" way can give it a try; however, I believe there are likely better alternatives for de-Googling for a device with bootloader unlocked.

Other Samsung devices are diagnosed dynamically, but are not declared supported
without a matching firmware/root entry and passing capabilities. They may only
reach the experimental opt-in path.

Root-My-Galaxy relies on a volatile exploit to load KernelSU. A **full kernel reboot loses both root and the temporary microG environment**.

For that reason, DeGoogle uses **userspace soft reboot only**:

```text
sys.powerctl reboot,userspace
```

The app does not expose a full-reboot action.

## How it works

DeGoogle treats the **real Android state as the source of truth** instead of trusting an internal app flag.

The backend inspects PackageManager paths, bind mounts, package flags, SELinux state, backup presence, and other runtime facts, then derives the current device state.

Operation sequence:

1. Verify root and device compatibility.
2. Download and validate the required microG APKs.
3. Prepare the temporary replacement environment transactionally.
4. Perform a userspace soft reboot.
5. Re-derive the Android state and finalize microG activation.
6. Optionally create a microG data backup.
7. Restore the stock Google environment when requested.

If a partial `prepare` operation fails, the backend attempts rollback and reports a dedicated partial/rollback exit code. During stock recovery, targets come from the transaction snapshot or the validated S24 profile; missing Package Manager entries are handled as `REINDEX_PENDING` only when the stock target exists. APKs are removed from the mask only after all owned mounts are gone, and reboot/post-boot validation completes the recovery.

## Safety model

When a check fails, the app stops or rolls back instead of continuing with an uncertain state:

- **Android real state is the source of truth**: DataStore stores auxiliary data only.
- **No validation, no root copy**: the Android app validates APK package/version, signature, and SHA-256 against the official F-Droid index before passing them to the privileged backend.
- **Transactional prepare**: partial failure triggers rollback with exit code `4`.
- **Global namespace validation**: `restorecon` and `ls -Z` run in the global namespace and success is verified.
- **Explicit backup destination**: microG backup restoration targets `com.google.android.gms`, with UID and SELinux derived at runtime.
- **Reboot is never assumed**: state is re-derived every time the app starts.

## Hybrid compatibility

The model list is no longer the primary compatibility proof. The app combines:

```text
Device Probe + Package Locator
        ↓
Capability Matrix with evidence
        ↓
Versioned Known-Good Database
        ↓
Safety Preflight + snapshot/journal
        ↓
execution only when rollback is verifiable
```

The engine states are `SUPPORTED`, `PROBABLY_SUPPORTED`, `REQUIRES_PROFILE`, `UNSUPPORTED`, and `UNSAFE`. A model-only match never produces `SUPPORTED`; `FAIL` or `UNKNOWN` on a critical capability blocks execution. The S24 is automatically approved only when fingerprint, SDK, root backend, and capabilities match the database.

The diagnostic path does not modify GMS, GSF, Store, data, cache, or reboot:

```bash
degoogle.sh dry-run
degoogle.sh preflight
```

Besides human-readable `stderr`, the backend emits `DEGOOGLE_CAP_*` records on `stdout`; Kotlin converts them into an exportable JSON report. Experimental opt-in does not disable detection: it can only authorize a `PROBABLY_SUPPORTED` result when every critical capability, including rollback and the soft-reboot strategy, is `PASS`.

The initial database is [`app/src/main/assets/compatibility/known_good.json`](app/src/main/assets/compatibility/known_good.json). There is no automatic remote database update.

The implementation does not claim physical support for another Samsung family.
Signature spoofing and soft-reboot safety remain blocking when the device cannot
provide functional evidence; a maintainer must validate a report and add the
firmware to the database before it can become `SUPPORTED`.

## Messaging apps and push notifications

If messaging apps such as WhatsApp or Telegram were already installed before microG activation, push notifications may need to be re-registered.

Observed workflow:

1. Activate microG.
2. Reinstall affected messaging apps so they request fresh push registration.
3. Open **microG Settings → Cloud Messaging (GCM)** and confirm that the apps appear as registered.
4. Create a microG backup in DeGoogle once registration is confirmed.

If the volatile environment is later lost after a full reboot, restoring the microG backup can preserve the registered microG data and avoid repeating the initial setup in the tested workflow.

The current `BootReceiver` also performs this check without requiring the
Activity to be opened. If a full reboot leaves the Package Manager stale
(`RESTORE_PREPARED`) or a known legacy mask mounted, the app unmounts only
known sources, preserves the backup, clears the parse cache, removes orphaned
microG data, and requests a soft reboot to reindex the system. In `STOCK` with
only temporary payloads, it removes known residue. Foreign mounts and
ambiguous states remain blocked and generate an alert instead of being removed
automatically.

## Build

Requirements:

- Android SDK
- `compileSdk 35`
- `minSdk 26`
- JDK 17+ (tested with JDK 21)

Configure the SDK:

```bash
printf 'sdk.dir=%s\n' "$HOME/android-sdk" > local.properties
```

Build and run unit tests:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper is tracked in git, so a separate Gradle installation is not required.

`local.properties` is local-only and must not be committed.

For release builds, use a dedicated keystore; the build never uses the debug key. Copy
`keystore.properties.example` to `keystore.properties`, fill in the values, and keep the
private keystore out of Git. In CI, the same properties can be provided through
`DEGOOGLE_RELEASE_STORE_FILE`, `DEGOOGLE_RELEASE_STORE_PASSWORD`, `DEGOOGLE_RELEASE_KEY_ALIAS`, and
`DEGOOGLE_RELEASE_KEY_PASSWORD`.

## Backend

The shell backend can be tested on a host without modifying an Android device:

```bash
sh -n degoogle.sh
sh degoogle.sh test
sh tests/backend_test.sh
```

Backend protocol:

```text
stdout  → DEGOOGLE_KEY=value
stderr  → human-readable diagnostics
```

Exit codes:

| Code | Meaning |
|---:|---|
| `0` | OK |
| `1` | Error |
| `2` | Usage |
| `3` | Precondition failure |
| `4` | Partial failure / rollback |
| `5` | Incompatible device |

## Architecture

```text
degoogle.sh
  shell backend:
  probe / dry-run / preflight / prepare / finalize /
  backup / restore-backup / restore-stock / rollback /
  post-boot-validate / soft-reboot / status / test

microg-session.sh
  original reference script; not used by the app

docs/
  ANALISE.md
  INCIDENTE-RESCUE-PARTY.md

tests/
  backend_test.sh

app/
  src/main/assets/
    root/degoogle.sh
    compatibility/known_good.json

  src/main/java/dev/degoogle/app/
    domain/
      DeviceFacts, SystemPackageInfo, PackageLocator
      Capability, CompatibilityEngine, KnownGoodDatabase
      TransactionJournal, DeviceState, StateDetector

    root/
      RootExecutor
      RootBackend
      BackendInstaller
      BackendRunner

    security/
      SignatureSpoofingProbe

    microg/
      ReleaseRepository
      ApkValidator
      MicrogManager

    backup/
      BackupManager

    reboot/
      RebootController

    ui/
      home
      diagnostics
      backup
      settings
      components

    boot/
      BootReceiver

  src/test/
    domain, security, state-machine, parser, and mocked-backend tests
```

## Known caveats & operational notes

### Firmware crash during soft reboot (Rescue Party)
A `system_server` crash on `CachedAppOptimizer.compactApp` has been observed around userspace soft reboots on Samsung Android 16 / One UI 8.5 test firmware. Repeated crashes can trigger Android's Rescue Party mechanism, resulting in a full kernel reboot.

On devices with volatile exploit root, a full kernel reboot drops root and unmounts the masks. The device remains recoverable and the backup under `/data/local/tmp/microg-backup/` survives. Full incident analysis and evidence: [`docs/INCIDENTE-RESCUE-PARTY.md`](docs/INCIDENTE-RESCUE-PARTY.md).

Experimental mitigation (disables cached-app freezer; changes memory management behavior):
```bash
settings put global cached_apps_freezer 0
```

### Third-party stores (Aurora Store)
Aurora Store may attempt to update microG GmsCore to Google Play Services. Exclude microG packages from auto-updates in Aurora Store.

### Post-reboot Package Manager sync
After a full reboot, PackageManager may hold stale cache entries for microG. Use the app's restore flow to clear the package cache and trigger a soft reboot to reindex stock packages.

## Documentation

- [`docs/ANALISE.md`](docs/ANALISE.md): Architecture, migration from `microg-session.sh`, and security decisions
- [`docs/INCIDENTE-RESCUE-PARTY.md`](docs/INCIDENTE-RESCUE-PARTY.md): Rescue Party incident log, timeline, and recovery verification
