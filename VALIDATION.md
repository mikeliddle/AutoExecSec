# Local validation evidence

Validated on 2026-11-06. This records build and synthetic automated evidence, **not phone/carrier feasibility or production-readiness certification**.

## Toolchain and command

Android SDK platform 36 / build tools 36.0.0, Gradle wrapper 8.13, Microsoft OpenJDK 17.0.21.0.2 **x64**, running under Windows ARM64 emulation.

The initially installed ARM64 JVM could compile the app but could not execute Robolectric's legacy SQLite native library (`Architecture 'aarch64' is not supported by SQLite library`). A portable x64 JDK was downloaded from Microsoft's official `https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip` and used instead; no tests were skipped to hide this limitation.

An example location for an equivalent portable JDK is:

```text
C:\Tools\jdk17-x64
```

Select an equivalent installed x64 JDK 17 on another Windows ARM64 machine. From `D:\AppointmentReminders`, with `JAVA_HOME` pointing to that JDK:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Result: **BUILD SUCCESSFUL**.

## Automated results

| Suite / task | Actual result | Coverage |
|---|---|---|
| DomainTest | 11 passed; no failures, errors or skips | Deterministic CSV/phone/name validation, strict appointment parsing, DST boundaries, tokens, frozen identity, lead/grace and preceding-evening logic |
| PlatformTest | 9 passed; no failures, errors or skips | Permission/SIM/notification guards, channel privacy, settings codec, delivery PDU status, callback mutability, independent exact dispatch/inexact timeout alarms |
| TransactionsTest | 23 passed; no failures, errors or skips | Atomic claim/cancel, duplicate protection, expiry, crash gaps, partial/missing/late callbacks, delivery status, review acknowledgement and deletion/retention |
| assembleDebug | Passed | Current application APK |
| assembleDebugAndroidTest | Passed | Compiled opt-in isolated encrypted-storage device test; not executed |
| lintDebug | Passed with 0 errors | 16 warnings and 1 hint; dependency-version notices, monochrome launcher icon, redundant SDK guard and a KTX suggestion |

**43 JVM tests passed in total.** Runtime database tests use unencrypted in-memory Room and Robolectric with Conscrypt disabled and legacy SQLite. They exercise real transaction code but do not prove Android Keystore or SQLCipher behavior on hardware.

Persistent reports:

```text
build\validation.log
app\build\reports\tests\testDebugUnitTest\index.html
app\build\test-results\testDebugUnitTest\TEST-*.xml
app\build\reports\lint-results-debug.html
app\build\reports\lint-results-debug.xml
```

Gradle also reports deprecated features for a future Gradle 9 upgrade; this project pins Gradle 8.13. Robolectric's alarm inspection API produces test-only deprecation warnings.

## APK evidence

| Artifact | Location | Size |
|---|---|---|
| Debug app | `D:\AppointmentReminders\app\build\outputs\apk\debug\app-debug.apk` | 36,978,753 bytes |
| Debug instrumentation test | `D:\AppointmentReminders\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk` | 359,157 bytes |

Application APK SHA-256:

```text
2CD4611B5EC2C5DD1FD5C31B37FF4858D98473DF6023A6FA183234B193099C69
```

`apksigner verify --verbose` verified the APK's v2 signature with one signer. `zipalign -c -P 16 -v 4` reported **Verification successful**. Inspection of every bundled native ELF library found 16,384-byte `PT_LOAD` alignment, including SQLCipher and AndroidX graphics path libraries for arm64-v8a, armeabi-v7a, x86 and x86_64. This checks signing/packaging/alignment, not actual hardware execution.

The packaged permission list was inspected with `aapt2 dump permissions`. Its only platform permissions are `SEND_SMS`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM` and `RECEIVE_BOOT_COMPLETED`. AndroidX additionally declares/uses the app-private, signature-protected `local.reminders.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, verified in the merged manifest. There is no internet, contacts, SMS-reading/receiving, location or broad-storage permission.

## Outstanding device evidence

No APK was installed, no SMS was sent, no device settings were changed, and no connected instrumentation test was run. Connected hardware was not used without separate authorization.

The app is **not yet verified for unattended sending on the intended phone/install path**. In particular:

- `SEND_SMS` is hard-restricted: actual installer allowlisting and runtime grant remain unverified. Sideloading alone is not sufficient evidence. If the supported install path cannot grant it, stop; do not assume unattended SMS will work.
- Real SIM selection, SMS submission/multipart/delivery callbacks, carrier limits and no-service outcomes remain unverified.
- Keystore/SQLCipher reopen after first unlock, reboot recovery, locked idle/Doze, force-stop, permission/channel revocation and manufacturer background restrictions remain unverified.
- Compose appearance, interaction, accessibility, document-picker flow and credential viewing lock have not been observed on a device.
- Release signing/distribution has not been configured. The deliverable is debuggable; keep real sensitive schedule/recipient data out until device feasibility and a non-debug release are established.

Follow the separately authorized device checklist in `README.md`. The compiled instrumentation test uses only an isolated synthetic database and never sends SMS, but installing/running it still requires authorization.
