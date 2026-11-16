# DeGoogle

Temporarily mask Google Play Services, Google Services Framework, and Play Store on a rooted Android device, with optional microG replacements.

DeGoogle uses bind mounts to change the active Android environment without rewriting the original system APKs. It handles preparation, userspace restart, validation, microG data backup, and restoration of the stock Google components.

**[Portugues brasileiro](README.pt-BR.md)**

> [!WARNING]
> Experimental software that modifies privileged Android state.
>
> On-device validation has been performed on a Samsung Galaxy S24 Ultra (SM-S928B), running Android 16 / One UI 8.5 with KernelSU. Passing compatibility checks on another device does not mean that device has been physically tested.

## Modes

| Mode | Behavior |
|---|---|
| DeGoogle only | Masks GMS, GSF, and Play Store with empty directories. Installs no replacements. |
| Replace with microG | Replaces GMS with microG Services, masks GSF, and replaces Play Store with microG Companion. |

DeGoogle only provides no replacement for Google services. Apps that depend on them may lose functionality, including Firebase push notifications.

microG provides a compatibility layer, but does not guarantee that every app or Google-dependent feature will work.

The scope is these three system components. DeGoogle does not remove every Google app or service from the device.

## Requirements and compatibility

- A rooted Android device. The app recognizes KernelSU, Magisk, and APatch; on-device validation has been performed with KernelSU.
- Android 8.0 or later to install the app. This minimum does not guarantee operational compatibility.
- Working global mount namespace access, bind mounts, SELinux context handling, and a usable userspace restart strategy.
- For microG mode, functional signature spoofing. The tested setup uses FakeGApps.
- Internet access to download microG APKs when preparing microG mode.

Compatibility is determined from runtime checks, including package locations, mount access, SELinux, restart strategy, and rollback prerequisites. Device model and known-good firmware entries provide context rather than authorization by themselves.

Failed or unknown critical checks block execution. A fallback framework restart requires explicit approval when it cannot be fully verified.

The primary tested use case is [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy), where KernelSU is loaded through a volatile exploit.

## Usage

1. Open DeGoogle and grant root access.
2. Wait for the device check to finish. Inspect Diagnostics if execution is blocked.
3. Select DeGoogle only or Replace with microG.
4. Confirm preparation and follow the userspace restart prompt.
5. Reopen the app and complete any remaining validation or microG configuration.

In microG mode, APKs are downloaded and checked for package identity, version, signing certificate, and SHA-256 before being passed to the privileged backend.

The app derives the current state from Android rather than relying only on a saved flag. Checks display their progress; an incomplete refresh leaves device operations blocked until the state is reconciled.

The interface is available in English and Brazilian Portuguese.

## microG setup and backup

After activation, open microG settings and configure the services you need.

If messaging apps were installed before microG activation, their push registrations may need to be renewed. In the tested workflow, reinstalling affected apps allowed them to register again. Confirm registration in microG Cloud Messaging before creating a backup.

The Backup tab can create, update, restore, or delete a local snapshot of microG data:

```text
/data/local/tmp/microg-backup
```

This is a microG data backup, not a device backup. It can preserve registration data for later restoration, but notification delivery must still be checked after restoring it.

Backup actions are unavailable in DeGoogle only mode. Existing backup files are preserved.

## Restoring Google

Use Restore Google Stock from Home and follow the restart and reindexing steps offered by the app.

Restoration removes the temporary masks and exposes the original Google components again. The microG backup is preserved.

A full kernel reboot removes the temporary mounts. With volatile exploit root, it also ends the root session. Package Manager may still retain microG entries afterward, so losing the mounts alone does not guarantee that restoration is complete.

After boot, DeGoogle checks the system state. Stock restoration requires an explicit action in the app.

## Limitations

- Temporary mounts do not survive a full kernel reboot. DeGoogle requests userspace or framework restarts instead.
- A `system_server` crash involving `CachedAppOptimizer.compactApp` has been observed on the tested Samsung firmware around userspace restarts. Repeated crashes can trigger Rescue Party and a full reboot.
- Third-party stores may offer Google Play Services as an update to microG. Exclude microG packages from automatic updates.
- Preparation may remove active GMS or Play Store updates to expose the original system APKs, and temporarily disable Play Store to prevent immediate updates.
- Runtime checks reduce uncertainty; they do not establish physical validation for every device or firmware.

## Build and test

Requirements: JDK 17 or later and an Android SDK with platform 35.

Configure the SDK path in `local.properties`:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

The current Gradle configuration also validates release signing during project configuration. Copy `keystore.properties.example` to `keystore.properties` and configure an existing private keystore before running Gradle. Keep signing files and credentials out of Git.

Build the debug APK and run unit tests:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Check the shell backend on the host:

```bash
sh -n degoogle.sh
sh degoogle.sh test
sh tests/backend_test.sh
```

## Source layout

- `app/`: Android app, UI, compatibility checks, APK validation, and operation coordination.
- `degoogle.sh`: canonical privileged shell backend, copied into app assets during builds.
- `tests/backend_test.sh`: host-side backend scenarios.
- `microg-session.sh`: original reference script, not used by the app.

The backend writes structured `DEGOOGLE_KEY=value` records to stdout and diagnostics to stderr.
