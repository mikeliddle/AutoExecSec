# Local build and device setup

## Build a debug APK

Requires JDK 17 and Android SDK platform 36 / build tools 36.0.0. The checked-in Gradle wrapper pins Gradle 8.13. Dependencies download from Google Maven and Maven Central during the build; the installed app has no internet permission.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Use your own JDK/SDK paths. Alternatively open this folder in Android Studio, select JDK 17 and install SDK 36 using its SDK manager. `local.properties` is machine-local and excluded; `ANDROID_HOME` is sufficient.

On Windows ARM64, use an **x64 JDK 17 under Windows emulation** for JVM tests: Robolectric's legacy SQLite native library does not support an ARM64 Windows JVM. The runtime tests disable Robolectric Conscrypt and use an unencrypted in-memory test database; production storage remains SQLCipher-encrypted. See [validation evidence](validation.md) for the toolchain used here and the separate device encryption test.

APK output: `app\build\outputs\apk\debug\app-debug.apk`. This is a **debug build**, signed with the development key, not a production/distribution release. It installs as **Auto ExecSec (Debug)** (`fyi.mllab.autoexecsec.debug`) alongside production `fyi.mllab.autoexecsec`, with separate data and permissions. Use synthetic data for debug testing. For non-debug builds, see [release signing and distribution](release.md); no signing secrets are bundled.

## Device feasibility and installation

Production releases require an **ARM64 (`arm64-v8a`) device with a 64-bit Android OS**. Android 8.0+ (API 26) remains the minimum; ARM64-only packaging does not change that API requirement. Release APKs exclude 32-bit ARM and x86/x86_64 native libraries. Debug builds retain all four dependency-provided ABIs for development and non-sending emulator checks.

Android 12+ needs special exact-alarm access; Android 13+ needs notification permission. SMS hardware, an active SIM, runtime `READ_PHONE_STATE`, legitimately granted `SEND_SMS`, enabled app/channel notifications and a valid selected subscription are required. The sole active SIM is selected automatically for new approvals. With multiple active SIMs, choose one explicitly unless a saved explicit selection is still valid; an automatic selection does not become an explicit choice when another SIM appears. With no active SIM, sending stays blocked. Existing saved selections remain explicit; approved reminders always retain their frozen subscription.

`SEND_SMS` is **dangerous and hard-restricted**: the installer on record must allowlist it before Android can grant it. Sideloading an APK is **not a guarantee**. Verify the intended supported install path first, on an authorized test phone. If it cannot grant SMS access, unattended sending is unavailable; this app does not silently substitute a composer, become the default SMS handler or attempt a restriction bypass. The Android platform documents the requirement at [Manifest.permission.SEND_SMS](https://developer.android.com/reference/android/Manifest.permission#SEND_SMS). Distribution through Google Play also has separate SMS permission policies; this project makes no Play eligibility claim.

Only after separate authorization, a conventional test install would be:

```powershell
adb install 'app\build\outputs\apk\debug\app-debug.apk'
```

Use it only for an authorized install/update. For an in-place update of an existing `fyi.mllab.autoexecsec.debug` install with the same signing certificate, use `adb install -r` with the APK path. The application ID changed from `local.reminders`: neither new package replaces or migrates data, permissions or approved reminders from `local.reminders` or `local.reminders.debug`. Old installations remain separate and may retain their own active queues. Do not uninstall, clear data or reactivate an old queue without separate authorization.

Open Setup on the phone, deliberately request permissions and exact-alarm access, select the intended active SIM if more than one is available, save and refresh diagnostics. A sole active SIM needs no selection step and is identified in Setup and preview. Merely tapping a runtime permission request cannot resolve missing installer allowlisting. Do not grant device access or approve a real batch until all numbers and recipients are verified. No `READ_PHONE_NUMBERS` or default-SMS role is needed for legitimate SMS sending. See the [verification checklist](verification.md) before relying on a particular installation for sending.
