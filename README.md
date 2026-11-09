# Appointment reminders

A native Android app for locally prepared, explicitly approved SMS appointment reminders through a selected phone SIM. Kotlin, Jetpack Compose, Room and SQLCipher. No backend, Google API, AI parser, network access, telemetry or paid service.

**Do not use this for safety-critical reminders. An assembled APK does not establish that a phone can grant SMS access or deliver reminders while idle.** No installation, device-setting changes or actual SMS sending are part of this build.

## Build

Requires JDK 17 and Android SDK platform 36 / build tools 36.0.0. The checked-in Gradle wrapper pins Gradle 8.13. Dependencies download from Google Maven and Maven Central during the build; the installed app has no internet permission.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Use your own JDK/SDK paths. Alternatively open this folder in Android Studio, select JDK 17 and install SDK 36 using its SDK manager. `local.properties` is machine-local and excluded; `ANDROID_HOME` is sufficient.

On Windows ARM64, use an **x64 JDK 17 under Windows emulation** for JVM tests: Robolectric's legacy SQLite native library does not support an ARM64 Windows JVM. The runtime tests disable Robolectric Conscrypt and use an unencrypted in-memory test database; production storage remains SQLCipher-encrypted. See `VALIDATION.md` for the toolchain used here and the separate device encryption test.

APK output: `app\build\outputs\apk\debug\app-debug.apk`. This is a **debug build**, signed with the development key, not a production/distribution release. Keep sensitive live data out of a debuggable build until device feasibility has been established and you have reviewed the implementation. For a production build configure your own signing key outside source control and assemble a non-debug release; no signing secrets are bundled.

### Device feasibility and installation

Supported API range: Android 8.0+ (API 26); Android 12+ needs special exact-alarm access; Android 13+ needs notification permission. SMS hardware, an active SIM, runtime `READ_PHONE_STATE`, legitimately granted `SEND_SMS`, enabled app/channel notifications and a explicitly selected subscription are required.

`SEND_SMS` is **dangerous and hard-restricted**: the installer on record must allowlist it before Android can grant it. Sideloading an APK is **not a guarantee**. Verify the intended supported install path first, on an authorized test phone. If it cannot grant SMS access, unattended sending is unavailable; this app does not silently substitute a composer, become the default SMS handler or attempt a restriction bypass. The Android platform documents the requirement at [Manifest.permission.SEND_SMS](https://developer.android.com/reference/android/Manifest.permission#SEND_SMS). Distribution through Google Play also has separate SMS permission policies; this project makes no Play eligibility claim.

Only after separate authorization, a conventional test install would be:

```powershell
adb install 'app\build\outputs\apk\debug\app-debug.apk'
```

This command is guidance, **not an action performed by this project**. Open Setup on the phone, deliberately request permissions and exact-alarm access, select the intended active SIM, save and refresh diagnostics. Merely tapping a runtime permission request cannot resolve missing installer allowlisting. Do not grant device access or approve a real batch until all numbers and recipients are verified. No `READ_PHONE_NUMBERS` or default-SMS role is needed for legitimate SMS sending.

## Workflow

1. **People:** select a phone country in Setup, then add stable recipient IDs, names and numbers manually or via CSV. Numbers are normalized to E.164 using libphonenumber; that is format validation, not ownership or SMS-capability verification.
2. **Import:** paste deterministic CSV or choose a single file through Android's document picker. Imports are foreground-only. The source paste/file, unrelated columns and selected URI are not persisted. No clipboard scraping or durable URI permission.
3. **Review:** resolve every recipient, inspect the exact phone number, rendered message, appointment and send times, zone, SIM and SMS segment count. Change appointment fields and press **Update preview** before approval. Invalid/ambiguous rows block approval. Imports alone never send.
4. **Approve:** check the authorization box and confirm the batch. Values and SIM subscription are frozen. The default lead time is 120 minutes. At most 40 reminders per batch, and at most 6 segments per reminder.
5. **Queue:** inspect pending/submitting/sent/failed/uncertain/expired/cancelled outcomes. Refresh while the app is open to fetch new callbacks and capability status. Pending reminders can be cancelled or edited; editing cancels the prior approval and opens a new review. Changes to a recipient, template or lead time invalidate affected pending approvals. Reapproval is explicit.

Approved reminders are not silently redirected if the selected SIM disappears. There is a 40-active-reminder cap and a conservative local throttle of 40 submission attempts per hour; carrier/platform multipart rate limits can still reject messages. Submitted SMS cannot be edited, cancelled or recalled. Carrier SMS charges may apply; SMS is not RCS.

## Deterministic imports

UTF-8 CSV, optionally with a BOM; commas, CRLF/LF and quoted fields with doubled quotes are supported. Multiline fields, duplicate headers, mismatched column counts, more than 40 data rows and files over 128 KiB are rejected. Extra columns are discarded. Every row must validate; no partial schedule approval.

Recipient CSV:

```csv
id,name,phone
alex,Alex Example,+12025550101
sam,Sam Example,+12025550102
```

IDs: 1-64 ASCII letters, digits, hyphens or underscores. Names: 1-120 printable characters. Local phone numbers use the explicitly selected two-letter country (e.g. US, GB, CA, AU). Service codes and extensions are rejected. Matching IDs update a saved person; names do not have to be unique. The entire recipient file is validated before saving; if storage interrupts row-by-row saving, the UI reports the error and already saved rows remain visible.

Schedule CSV:

```csv
recipient,date,time,zone
alex,2027-01-12,14:00,America/New_York
sam,2027-01-12,6:30 PM,America/New_York
```

Dates: `yyyy-MM-dd`. Times: `HH:mm` (24-hour) or `h:mm AM/PM`. Use an IANA time zone such as `America/New_York`, or an explicit fixed offset such as `-04:00`. Stable recipient ID wins; otherwise an **exact case-sensitive full name** is considered. Missing or ambiguous matches require a manual recipient choice. There is no fuzzy matching or prose interpretation.

A daylight-saving gap is rejected. A repeated wall-clock time is also rejected: choose the intended explicit fixed offset (for example `-04:00` versus `-05:00` for the fall overlap). Normal appointments are frozen as instants; changing the phone's time zone does not move them. Changing the wall clock triggers reconciliation and the expiry policy.

The supplied `samples` use **synthetic, reserved example phone numbers** and fixed future dates. Do not approve them for SMS. Change dates to future synthetic test dates for offline previews; use only separately authorized real test numbers for device SMS testing.

## Templates

Supported tokens: `{firstName}`, `{time}`, `{dayPeriod}`. Unknown tokens or unmatched braces are errors. The first whitespace-delimited name part is used. Time is rendered in the appointment zone and includes AM/PM.

Default:

```text
Hey {firstName}. This is your reminder that you have an appointment with John at {time} {dayPeriod}.
```

Example at 14:00:

```text
Hey Alex. This is your reminder that you have an appointment with John at 2:00 PM today.
```

`{dayPeriod}` is `today` for appointment times before 18:00 and `tonight` from 18:00 onward, in the appointment zone. This is a deterministic rule, not inference. To avoid misleading text, send time and appointment must fall on the **same appointment-zone date**; reduce lead time for early appointments. The preview shows the complete result before authorization. Android's selected-SIM `SmsManager.divideMessage` supplies the actual segment preview, including Unicode and extended GSM characters.

## Night-before schedule-input notification

Default: Monday-Friday **reminder-work days**, notified on the preceding evening at **20:00 in the phone's local time zone**. For Monday, the prompt is on Sunday evening. Additional one-off work dates and a configurable local evening time are supported.

Prompts are independent of imported appointments: the app cannot know an unentered schedule. A date with an approved schedule, or explicitly marked **no reminders**, suppresses its preceding-evening prompt. Marking no reminders suppresses the prompt; it does not cancel already approved reminders. The prompt opens Import and contains no names, times, numbers or message bodies.

A one-shot **inexact** idle-capable alarm is scheduled, then the next occurrence is computed. Android may delay it. Evening DST gaps resolve forward and overlaps use the earlier occurrence. The appointment queue uses a separate exact-alarm wakeup and separate notification channel.

Submission callback timeouts use a third, independent inexact alarm, so an unresolved SMS attempt cannot replace a future exact appointment wakeup.

## Failure policy

- Persist before scheduling; transactionally claim and persist an attempt before handing anything to `SmsManager`.
- Default dispatch grace is **15 minutes**, but never send at or after the appointment. A missed reminder becomes expired and needs manual review.
- Track sent callbacks per multipart segment, with separate optional delivery callbacks. **Sent means Android reported submission**, not confirmed recipient delivery. Only complete delivery-status reports for all parts can mark delivery reported; missing, pending or failed reports do not. Delivery reports may never arrive.
- No blind automatic retries of uncertain, missing-callback or partially submitted messages. A crash between persisting an attempt and invoking SMS can leave uncertainty; exactly-once carrier delivery is not promised.
- Cancellation and dispatch share synchronization/transaction guards. Cancellation is refused once dispatch begins.
- Permission/exact-alarm/SIM checks gate approval and dispatch; blocked pending reminders can expire rather than being silently rerouted. Revocation can stop the process and remove exact alarms; reopen the app to reconcile and see diagnostics.
- Reconcile on launch, boot/first unlock, package replacement, clock/time-zone changes and exact-alarm grant. No pre-unlock sending, always-running service, alarm-clock hack or `USE_EXACT_ALARM`. Bounded broadcast work batches due items.

Android can throttle idle alarms. Phone off, no service, airplane mode, force-stop, SIM changes, locked-before-first-unlock and manufacturer background restrictions can prevent or delay dispatch. No location permission is requested: the app cannot preflight radio/carrier service on every Android version, so authoritative SMS callbacks determine those failures. A carrier can deliver an already submitted SMS late. The app does not automatically request battery exemption; review your manufacturer's unrestricted/auto-start guidance manually.

## Privacy and deletion

The entire Room database, including recipients, scheduling metadata, templates and queue, is SQLCipher-encrypted. A random database key is wrapped with Android Keystore AES-GCM. It is usable for background dispatch after first unlock, without per-send authentication. The optional viewing lock uses the device credential and does not change the background key. Screenshots/recents previews are protected with `FLAG_SECURE`. Notifications and diagnostics are generic, and no personal content is logged.

No `INTERNET`, contacts, `READ_SMS`, `RECEIVE_SMS`, broad-storage, accessibility or notification-listener permissions. App backups and device transfer exclude app-private data. Imports do not modify or remove source files or clipboard content.

Completed history retention is configurable from 1-365 days (default 30); uncertain outcomes remain for manual review. **Acknowledge outcome review** records that you manually checked an uncertain attempt; it does not mark it sent/delivered, change its uncertain state or permit a retry. Local deletion controls remove recipients/settings/reminder content as described on screen, cancel pending reminders, and preserve non-reversible duplicate-protection fingerprints to prevent accidental re-import/resending. Full deletion refuses active submissions and unacknowledged uncertain outcomes; acknowledge the manual review first. Uninstalling/clearing app storage removes that protection and the encryption key; treat reinstall as a new installation.

**External privacy boundary:** Android automatically records a non-default app's sent SMS in the system SMS provider. Source documents/files/clipboard, system SMS history and its backups, carriers and recipient devices are outside this app's encryption/deletion controls. See [SmsManager.sendTextMessage](https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.app.PendingIntent,%20android.app.PendingIntent)).

## Device checklist — separate authorization required

Use only a consenting test recipient and synthetic appointment content. Do not approve the sample phone numbers.

| Scenario | Required observation |
|---|---|
| Supported install path | Installer allowlisting plus runtime SMS grant actually succeeds; otherwise stop |
| Explicit dual SIM | Selected subscription sends; disabled/removed SIM is never silently replaced |
| Authorized single SMS | Exact preview matches number/body; per-part sent callback is reflected |
| Multipart / Unicode | Each segment callback tracked; a partial or missing result is uncertain, not retryable |
| Locked idle / Doze | After first unlock, actual dispatch and callback behavior documented, including delays |
| Reboot | Pending queue reconstructed after first unlock; missed grace expires |
| Force-stop / phone off | No reliability promise; reopen/reboot behavior and expiry shown |
| Permission revocation | SMS, SIM, exact alarms and notification denial visibly block sending |
| Disabled channel | Input or dispatch channel denial visible in diagnostics |
| Airplane mode / no SIM / no service | Explicit failure/block/expiry; no fallback SIM or blind retry |
| Clock / zone / DST | Frozen appointment instant remains; late dispatch expires; prompt recomputes |
| Cancel near dispatch | Cancellation succeeds only before claim; submitted texts cannot be recalled |
| Crash gap / missing callback | Persisted attempt becomes uncertain; no automatic resend |
| Privacy / deletion / viewing lock | No private notification text; local deletion and credential lock boundaries verified |

Automated JVM tests cover deterministic rules and runtime state/permission guards where supported. They do not establish phone SMS grant, radio/carrier acceptance, SQLCipher hardware Keystore behavior, idle reliability or manufacturer behavior. See `VALIDATION.md` for the actual build/test evidence and outstanding hardware checks.

An additional opt-in instrumentation test creates and deletes only an isolated, randomly named synthetic SQLCipher database, checks reopen and absence of plaintext recipient content, and never touches the live queue. It **does not send SMS**. After separately authorizing installation of app/test APKs on a test device, it can be run with `.\gradlew.bat :app:connectedDebugAndroidTest`. Do not run that command as part of an unauthorized phone build check.
