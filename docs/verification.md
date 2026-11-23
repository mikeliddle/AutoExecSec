# Verification checklist

## Device checklist (separate authorization required)

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

Automated JVM tests cover deterministic rules and runtime state/permission guards where supported. Separate connected tests exercised actual Huawei UI, SQLCipher/Keystore-backed persistence, no-SIM blocking and generic notifications. They do not establish radio/carrier acceptance, idle reliability or manufacturer behavior. See [validation evidence](validation.md) for the actual results and outstanding checks.

The opt-in connected suite **never sends SMS or grants permissions**. `EncryptedStorageTest` uses only an isolated randomly named SQLCipher database. `DeviceRuntimeTest` also loads the app's Keystore-wrapped key, verifies transactions in an isolated encrypted database, requires unavailable sending capabilities/no active SIM for its approval-rejection check, and posts a generic notification using already-enabled access. `DeviceWorkflowTest` launches the actual app, imports two explicitly synthetic `huawei_*` people into its encrypted recipient list, exercises previews without authorization/approval, recreates the Activity and follows the actual notification into Import. Synthetic people remain for inspection; no existing data is cleared. The full suite assumes a no-SIM test phone with notifications enabled and viewing lock off. Do not point it at a live recipient workflow.

After separately authorizing installation and synthetic testing, build/install the app and test APKs and run `adb shell am instrument -w fyi.mllab.autoexecsec.debug.test/androidx.test.runner.AndroidJUnitRunner`. `.\gradlew.bat :app:connectedDebugAndroidTest` is an alternative authorized test runner. For the isolated encryption check alone, pass `-e class local.reminders.runtime.EncryptedStorageTest` to `am instrument`; Kotlin test classes retain their namespace. The new application ID does not update or migrate old `local.reminders` installs. Do not run connected commands as part of an unauthorized phone build check; do not auto-grant permissions or clear app data to make a test pass.
