# Local validation evidence

Build and device prerequisites are in [build instructions](build.md); verification boundaries are in [verification guidance](verification.md).

## Public-transition preparation (isolated, local only)

Prepared from exact canonical remote `main`, `2f1cbd2611e5041faf032af1e144990d3ca8bc91`, in `D:\AutoExecSecPublishing`; the dirty primary checkout was not changed. The replacement publisher defaults to unsigned verification and requires separate owner-reviewed signing/publication environments for release. MIT applies only to original code. Full original-code/Apache/SQLCipher licenses and third-party attribution are packaged into the APK and readable offline through **Setup → Licenses**.

Offline `testDebugUnitTest`, `lintRelease` and `assembleRelease` passed: **104 JVM tests, 0 failures/errors; 0 lint errors and 3 existing warnings** (mutable navigation-history collection, redundant v26 icon resource folder and missing monochrome icon). The new Compose test exercises every license disclosure at 360x800 dp and 1.6x font scale. All four APK license assets match the reviewed source bytes. Unsigned production APK: `app\build\outputs\apk\release\app-release-unsigned.apk`, SHA-256 `2f91cb26bf45b15ece324fba50c8c14eb93d29c1f2b40012d0e32cf4c841b1e7`, package `local.reminders`, version 1.0/code 1, non-debuggable and unsigned.

`actionlint` and native PowerShell parsing of all nine workflow scripts passed. Thirty offline mock cases cover unsigned-only verification, owner/rerun restrictions, protected unchanged main, both independent reviewer/bypass/branch gates, and refusal of missing, debug or another app's signing fingerprint. The unmodified unsigned-inspection step passes on the actual APK, including its exact permission allowlist and required notice assets; the signature-permission tree guard was separately checked against real `aapt2` output. Google's fixed Build Tools 36 archive checksum was independently verified. A bounded reachable-history scan found no credential-pattern, forbidden-artifact or nonreserved-phone flags; this is not comprehensive privacy/security/legal certification.

No Actions run, environment approval, production signing/key provisioning, remote source/settings/visibility change, publication or device action was performed. At this preparation check the repository remains private, main unprotected, both required environments absent, and the old workflow active; the previous debug release is draft with both assets retained. Remote gate enforcement, Windows-hosted end-to-end publishing and physical license-screen appearance/accessibility remain unverified. They require the coordinated owner-controlled cutover, not an ungated trial release.

## Combined Templates/docs installation (2026-11-22; Galaxy S22)

The isolated Auto ExecSec candidate incorporated the reviewed app baseline, copy-only Templates feature and documentation cleanup. The primary local checkout was left unchanged. The combined offline build/test/test-APK/lint command succeeded with the same **100 passing JVM tests** and **0 lint errors / 2 existing resource warnings**. Debug APK SHA-256 remains `13F5A4DA50E8F5EC2093B1B5D3B0197DA5B0EA0F01430D4BE200BCD9CBE08F5C`; signature and 16-KB zip alignment were verified.

The user explicitly requested installation on their connected phone. Exactly one authorized adb target was present: **Samsung Galaxy S22 / SM-S901U1, API 36**. Package-manager checks found no `local.reminders` package before installation, including retained/uninstalled package records for the current owner user; only the previous test package remained. This was therefore a fresh install, not an in-place update of the old Samsung app history. No uninstall, data clear or old-queue activation was performed.

Installation succeeded, a cold launch returned **Status: ok**, and `MainActivity` was resumed with a running process and **zero PID-scoped FATAL EXCEPTION entries**. The installed base APK's read-only SHA-256 matched the candidate exactly. Android UI hierarchy acquisition was unavailable, so the Templates entry was not physically clicked or visually verified; no coordinates were guessed. No test APK/instrumentation, permission grant, settings change, reminder approval or SMS/MMS send occurred. This records installation/startup evidence, not new carrier or complete native Templates interaction evidence.

Initial local validation: 2026-11-06. Connected Huawei validation: **2026-11-09**, followed by simplified CSV implementation. Connected Samsung single-message validation: **2026-11-11**, including the CSV and automatic sole-SIM changes. These observations are not production-readiness or general unattended-reliability certification.

## Isolated copy-only template generator (2026-11-22; offline)

Implemented in `D:\AutoExecSecTemplates`, a detached local worktree created without fetching from local HEAD `2dfa6a6192265618e066e5f1c1914c3d0d94c59e`. The reviewed uncommitted Auto ExecSec source/schema/sample/documentation baseline was copied in, excluding local settings, credentials, toolchains, data and outputs. A session-held file/hash manifest distinguishes the feature changes from that baseline. This snapshot is **not yet integrated into the shared checkout or installed on a phone**.

The Templates top-bar action opens named create/edit/delete definitions, one field per distinct case-sensitive `{variable}`, live preview and explicit sensitive-flagged clipboard copy. Repeated variables share one field/value; literal definitions need none. Missing fields disable copying; malformed tokens and bounded-size violations are errors. Stored definitions use the existing SQLCipher-backed values table without a schema change, and full local deletion removes them. Filled values are not stored. Reminder/Announcement renderers and dispatch semantics are unchanged.

Using the existing x64 JDK 17 and cached SDK/dependencies, `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest` and `lintDebug` passed. **100 JVM tests; 0 failures/errors/skips**, including eight new renderer/storage/Compose tests. These cover repeated variables and first-seen field order, case sensitivity, literal text, no recursive substitution, invalid tokens/missing inputs/size limits, encrypted-store-compatible definition serialization and full deletion, navigation entry, dynamic field-count changes after editing, create/edit/delete, live preview, explicit clipboard text/sensitivity and 1.6x-font interactions. Storage tests use the existing unencrypted in-memory test database; production encryption is reused, not newly device-tested.

Lint reports **0 errors and 2 pre-existing resource/icon warnings**. The fresh offline worktree did not emit the older checkout's dependency-update notices; this is not evidence of dependency upgrades. Native pixel appearance, TalkBack, physical keyboard/clipboard behavior and phone persistence remain unverified; no emulator/AVD was available.

Isolated debug APK: `D:\AutoExecSecTemplates\app\build\outputs\apk\debug\app-debug.apk`, **37,003,965 bytes**, SHA-256 `13F5A4DA50E8F5EC2093B1B5D3B0197DA5B0EA0F01430D4BE200BCD9CBE08F5C`. Debug certificate SHA-256 remains `4A9F835E90F992BDFD1D4F5AB9870BB61243A94E05221DFB9AE94024C9D486EA`; APK signature verification and 16-KB zip alignment passed. The package/version and reviewed Android permission boundary remain unchanged. No device action, send, source integration, commit/push, signing-key provisioning or GitHub publication occurred.

## Authorized Auto ExecSec installation (2026-11-12; Huawei)

Following the user's separate installation request, exactly one authorized adb target was connected: **Huawei STK-LX3, Android 10 / API 29**. Both SIM slots reported `ABSENT` before and after the operation, preventing unexpected SIM dispatch during launch. No SMS/MMS was approved or submitted.

The existing installed package and completed update APK had the same debug certificate SHA-256, `4A9F835E90F992BDFD1D4F5AB9870BB61243A94E05221DFB9AE94024C9D486EA`. `adb install -r` succeeded without uninstalling or clearing data. The package's existing app-data inode was unchanged; private data contents were not inspected.

`am start -W` returned **Status: ok**, a cold launch of `local.reminders/.MainActivity`. The app process remained running and app-scoped activity evidence showed it resumed. Its PID-scoped crash buffer contained **zero FATAL EXCEPTION entries**. Read-only hashing of the installed base APK matched the completed Auto ExecSec artifact exactly: `35A9E933420E269291BFA7959600B60ABF3EAE04EDBAC4E8511BB83E135CEED0`, version 1.0 / code 1. The verified APK label is Auto ExecSec; this remains a debug build.

No instrumentation/test APK, permission grant, settings change, authentication bypass, data deletion, live send, commit/push or release work occurred. The Samsung installation was not changed. Installation/process evidence is not a pixel/accessibility review or new carrier-reliability evidence.

## Auto ExecSec table interface (2026-11-12; local only)

The latest local build displays **Auto ExecSec**, with **Announcements** for separate individual SMS and **Schedule** for appointment SMS/shared MMS. Android application ID `local.reminders`, version `1.0` / code `1`, debug signing identity and Room schema 4 are unchanged. Branding introduces no database migration. Existing recipients, settings, encrypted audits and approved queues use the same storage and dispatch contracts.

People and Schedule now use editable rows with adjacent Import, add/delete controls, row/cell errors and native calendar/time/person affordances. CSV and legacy formats populate the same editable tables. Import failure leaves existing rows unchanged; replacement requires confirmation, and People require a separate confirmed save. Existing-person edits retain their stable ID and existing pending-approval cancellation behavior. Table and Announcement composition drafts remain in memory across navigation/configuration changes, not process death or unencrypted saved-state bundles.

Validation used the existing x64 JDK 17 under Windows ARM64 emulation, Android SDK 36 and cached dependencies:

```powershell
.\gradlew.bat --offline --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Final result: **BUILD SUCCESSFUL; 92 JVM tests, 0 failures, errors or skips**. Existing 80 domain/runtime/migration tests passed, including frozen SMS/MMS values, SIM/permission gates, cancellation, duplicate protection, uncertainty and Announcement pacing. Twelve new tests cover table validation and CRUD, import-to-editable-row identity/zone preservation, whole-import failure and explicit replacement, row limits, navigation draft retention, branding, and both Announcement acknowledgments plus final confirmation without sending. Compose interactions ran at 400x850 dp and 840x1100 dp, plus a 360x800 dp configuration at 1.6x font scale; the row-delete control's measured height is at least 48 dp.

Lint reports **0 errors, 20 warnings, 1 hint**. The three additional warnings are newer-version notices for aligned JVM Compose-test dependencies; no unrelated dependency upgrade was performed. Connected test selectors were updated and the non-sending instrumentation APK compiled, but it was not installed or run.

Latest application artifact: `D:\AppointmentReminders\app\build\outputs\apk\debug\app-debug.apk`, **36,978,844 bytes**, SHA-256:

```text
35A9E933420E269291BFA7959600B60ABF3EAE04EDBAC4E8511BB83E135CEED0
```

`aapt2` confirms label **Auto ExecSec**, the unchanged package/version and **debuggable** status. `apksigner verify --verbose --print-certs` verifies one Android Debug signer using APK v2; certificate SHA-256 is `4A9F835E90F992BDFD1D4F5AB9870BB61243A94E05221DFB9AE94024C9D486EA`. `zipalign -c -P 16 4` passed. Packaged platform permissions remain only SMS send, phone-state, notifications, exact alarms and boot; the additional receiver permission is app-private/signature-protected. No Internet, contacts, inbox-reading or broad-storage permission was added.

The compiled test APK remains at `app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`. JVM/lint reports remain under `app\build\reports` and `app\build\test-results`; future builds can replace them.

**No installation, device interaction, permission/settings change, real SMS/MMS, data deletion, commit, push or release action occurred for this interface update.** No Android emulator executable or AVD was available for visual inspection. Offline Compose interactions are not pixel, TalkBack, physical keyboard/OEM picker or on-device UI evidence. Existing phone installations and the earlier carrier evidence below remain tied to their older APKs. Signing/publication remains paused; no new live sends are authorized.

## Samsung authorized shared group MMS (schema 4)

After the concurrent SMS-batch work finished and checkout/build ownership was handed off, the combined debug APK was reviewed and installed in place on the **Samsung Galaxy S22 / SM-S901U1, Android 16 / API 36**. Application SHA-256: `7FA5250CCE4AF2B4F6AA1182D9EE11535FF2882E7E35CA1CBA2EA728981F49C9`. Signing certificate matched the previous debug install and package alignment passed. No uninstall, data clearing, shell permission grant or device-global setting change was performed. The user unlocked the phone.

The schema-3-to-4 encrypted production store opened successfully. The selected non-sending `GroupWorkflowTest` passed before the live procedure, rechecking surname blocking, mixed-surname first-name preview, carrier limits and the memory-backed MMS stream. The live procedure then verified the original single SMS remained SENT with its successful delivery report and that there were no pending, submitting, uncertain or previously attempted group reminders.

The user had separately authorized exactly one shared MMS to two specific recipients. Both destinations were supplied only as transient device-test input, never embedded in source, fixtures, documentation or build logs. A temporary opt-in procedure used the real Compose import/review screen, checked both exact destinations and the exact harmless group-test message, acknowledged shared number/reply visibility, and approved one frozen reminder through the sole selected SIM. It persisted a no-resend marker before approval.

The natural scheduled alarm produced **PENDING -> SUBMITTING -> SENT**, with **one group attempt** and a successful MMS sent callback. No direct shell send, individual SMS fanout or application retry was used. MMS does not expose a per-person delivery PendingIntent; the saved per-person delivery flag correctly remains false.

The user subsequently confirmed: **both recipients received it in one shared conversation, and reply-to-all works**. This is participant-reported receipt/thread/reply evidence, separate from the observed carrier sent callback. The reminder app and validation commands did not read Messages or unrelated conversations, request default-SMS status, or change APN/mobile-data settings.

Original settings were restored, including the **120-minute lead**, and only the procedure's temporary People entries were removed. The frozen sent-group audit remains. The temporary send-capable source was deleted, its APK replaced with the maintained non-sending instrumentation APK, and the final APK's DEX entries were checked to confirm the temporary sender was absent. The subsequent selected `GroupWorkflowTest` passed and reported **two total reminders: one SENT group with one attempt, plus the original delivered individual SMS**, with no active/uncertain reminders.

Final local unit/build/test-APK/lint validation passed again: **80 JVM tests, 0 failures/errors/skips; lint 0 errors, 17 warnings, 1 hint**. This verifies one scheduled group MMS and user-confirmed shared replies on this carrier/device combination, not arbitrary carriers, MMS delivery reports, locked idle/Doze, reboot, force-stop, no-service or general unattended reliability. The individual SMS-batch feature was not sent or carrier-tested. No signing/release, commit, push or GitHub publication occurred; both authorized live test allocations are now exhausted.

## Local individual SMS batch snapshot (schema 4; offline only)

The subsequent local SMS batch implementation, including reusable name templates and configurable inter-recipient delay, passed the command below using the existing x64 JDK 17. The current branch remains `main`; existing uncommitted group-MMS/surname changes were retained. No fetch, commit, push or PR was needed.

```powershell
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Result: **BUILD SUCCESSFUL; 80 JVM tests passed**, with no failures, errors or skips. Lint reports **0 errors, 17 warnings and 1 hint**. These results supersede the automated counts below for this schema-4 source snapshot, but do not extend any earlier physical-device evidence to this implementation.

| Suite | Passed |
|---|---:|
| BroadcastsTest | 5 |
| DomainTest | 21 |
| MmsPduTest | 2 |
| MigrationTest | 3 |
| PlatformTest | 14 |
| TransactionsTest | 35 |

Focused checks cover exactly 100 unique normalized numbers and rejection above that count; number deduplication; empty/invalid input; existing name-token rendering; unknown/unmatched placeholders, missing surnames and unavailable appointment fields; unresolved braces introduced through recipient fields; explicit delay bounds; saved template/delay roundtrip and legacy defaults; separate appointment/batch caps and coverage; atomic mixed-SIM/duplicate rejection; individualized frozen text/segment counts; one in-flight recipient; minimum-gap boundaries after all multipart submission results; duplicate callback idempotence; pacing surviving recovery, preference changes and history deletion; stop/failure/unknown-outcome handling without retries; pacing-key cleanup; and expiry. Schemas 1, 2 and 3 migrate to 4, preserving frozen SMS/MMS members/payloads, people, settings, coverage and duplicate protection. Schema 4 adds nullable batch ID, expiry and delay columns.

`BroadcastReviewTest` compiles into the instrumentation APK. It uses a synthetic two-person personalized preview to check both acknowledgments and the final confirmation dialog, then dismisses without authorizing sending. **It was not run on a device for this snapshot.** No installation, connected suite, permission change, device action or real SMS/MMS send was performed by this batch implementation session.

Application APK at that offline checkpoint: `app\build\outputs\apk\debug\app-debug.apk`, **36,978,852 bytes**, SHA-256:

```text
7FA5250CCE4AF2B4F6AA1182D9EE11535FF2882E7E35CA1CBA2EA728981F49C9
```

The compiled instrumentation APK is `app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk` (**1,232,203 bytes**). Reports remain at `app\build\reports\tests\testDebugUnitTest\index.html`, `app\build\test-results\testDebugUnitTest\TEST-*.xml` and `app\build\reports\lint-results-debug.xml`; later builds can replace them.

At this offline checkpoint, physical schema-3-to-4 encrypted-store upgrade was unverified; the later connected MMS section above records the successful upgrade. Actual individual-batch SIM/carrier pacing, OEM messaging-app conversation persistence/private replies, and delivery for this feature remain **unverified**. Android documents automatic system SMS-provider persistence for the standard non-default-app send APIs used here; that platform contract is not a substitute for phone/carrier verification. No safe carrier rate, delivery guarantee or Google Play approval is claimed. Other connected results apply only to their explicitly identified APKs.

## Shared group preview and surname guard

The group-MMS snapshot built on **2026-11-11** passed **65 JVM tests**, APK/test-APK assembly and lint (0 errors, 17 warnings, 1 hint). Both version-1 and version-2 databases migrated to schema 3 while preserving frozen reminders, settings, coverage and duplicate protection. Explicit first names survive the version-2 migration; surnames are not guessed for legacy people.

A compatible debug update was installed in place on the Samsung, without clearing data, changing permissions or replacing its signing identity. The phone was initially locked; the first connected test could not find a Compose hierarchy. After the user unlocked it, the explicitly selected **GroupWorkflowTest passed**, and passed again with redacted capability reporting enabled.

The real UI showed a surname-mismatch warning and disabled approval for a two-person `{LastName}` template. Switching to `{FirstName}` allowed the mixed-surname group to preview both exact numbers, comma-separated first names and the shared-conversation privacy notice. The test never checked authorization or approved a reminder. It restored original settings, removed only its own synthetic people and verified reminder history was unchanged.

The same connected test read a complete synthetic MMS payload through the memory-backed proxy descriptor, including its reported size. Runtime capabilities reported ready, verified carrier group MMS enabled, a 20-recipient limit and a 65,536-byte locally capped payload limit. These checks do **not** prove carrier submission, delivery, system threading or reply-to-all behavior.

At this schema-3 checkpoint, **no group MMS had been submitted**. Further live testing was paused when concurrent source changes appeared during validation, and the unbuilt temporary sender was removed. The subsequent schema-4 group-send result is recorded above. No SMS was resent, and no GitHub/signing work was resumed. Evidence in this section applies to application APK SHA-256 `C0CAFD223B9AF517F5AE8E0FEC32B5ED7B8CC78093BBD727E90C0B51521514D8`, not subsequent source edits.

## Samsung single-message validation

The user separately authorized installing the current compatible debug build on a **Samsung Galaxy S22 / SM-S901U1, Android 16 / API 36**, and exactly one harmless, single-segment test SMS. The destination was supplied transiently to the on-device procedure and is deliberately absent from source, fixtures and this document.

The application had not previously been installed on this phone, so there was no old approved queue to activate. The user granted Android permissions/special access through normal phone UI. Runtime checks subsequently reported SMS/phone-state/notification/exact-alarm capabilities ready, one active subscription and no reminders or attempts. No shell permission grants or device-global setting changes were issued.

The automatic selection check cleared only the app-local selected-SIM setting, reconciled, and confirmed the sole active SIM was selected without opening a chooser; the queue and attempt counts remained zero. Original app-local settings were restored.

Automatic selections are now distinguished from explicit choices in saved settings. If another active SIM appears, a previous automatic choice cannot silently qualify as a manual choice; new approvals require an explicit selection. Legacy saved selections remain explicit, and existing approved reminders keep their frozen subscription. A stale preview cannot bypass the saved-selection check at approval.

The one-message procedure then used the actual Compose import/review screen, checked the exact destination, harmless rendered message and one-segment preview, acknowledged automatic sending and confirmed the batch. The app froze the selected subscription and persisted one pending reminder. Its natural scheduled alarm dispatched approximately 144 seconds after approval, without a direct shell send or a second SMS.

Observed persisted result: **one reminder, one attempt, SENT, delivery report received (`delivered=true`)**. A sent callback alone would not have proved delivery; this device also supplied a successful delivery report. The temporary template/one-minute lead were restored to the original template/120-minute lead. A separate post-send read-only probe confirmed these settings, the saved sent/delivered outcome and no pending queue. The audit history remains available on the phone.

The procedure's final Compose refresh failed because no Compose hierarchy was available after the scheduled wait. This happened **after** the sent/delivery reports and settings restoration, not during submission. The send was not retried. The read-only follow-up passed, and the app was relaunched. Temporary send-capable instrumentation was removed from source and replaced on the phone; it is not part of the maintained test suite.

The successful SMS used APK SHA-256 `6BDED5C01BAC94C8A6BBD78312EFAF73385BA9D71E1EA1BB854424B719488795`. The final multi-SIM explicit-choice safety refinement was rebuilt, locally tested and installed afterward without any further send; that APK had SHA-256 `06477652A72B9534F3B6E9C64FF66AA0A71FB2FAE673D50D2D44C8B73242E10E`. The app's activity was confirmed resumed after that in-place update.

No signing/release work, commit, push, uninstall, data clearing, reboot, global clock/battery change, authentication bypass or additional send was performed. This verifies one foreground-to-scheduled dispatch on this install, not locked idle/Doze, force-stop, reboot recovery, dual-SIM switching or another installer's ability to grant hard-restricted SMS access.

## Simplified CSV update (local only)

People now accept `FirstName,LastName,phone`; schedules accept `Date,Time,Name`. Legacy formats remain supported. UI guidance and `samples` lead with the simpler formats. Exact combined names resolve schedules; duplicate and unknown names require explicit choice, with no fuzzy match or ID precedence in the simple format.

The phone's current `ZoneId` is captured once per import, copied to each preview row, displayed even for unresolved rows and frozen into approved appointments. Automated tests change the JVM default zone after parsing and confirm that existing drafts remain identical, while a later import captures the new zone.

People imports preserve complete first names such as `Mary Ann`, normalize numbers, reuse unique saved name/number identities (including legacy IDs), deduplicate identical rows and keep same-name/different-number people distinct. Conflicting identities or ambiguous existing name/number pairs fail explicitly. All people are saved transactionally; first-name changes invalidate pending approvals. Legacy reimports preserve an existing explicit first name when the full name is unchanged.

Room schema 1 -> 2 adds only a nullable `firstName` column, without destructive fallback. A Robolectric test creates the checked-in version-1 schema, inserts synthetic recipients, a frozen pending reminder, coverage, settings and a tombstone, then opens it through the production migration. Room validates the migrated schema; values and frozen reminder state/time/zone/message/subscription survive, and explicit first names persist after reopening. This is a local SQLite/Room migration test, not a new on-device SQLCipher migration observation.

No phone installation, SMS attempt, permission/setting change, data clearing, commit or GitHub push was performed for this update.

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

## Earlier schema-3 automated results

| Suite / task | Actual result | Coverage |
|---|---|---|
| DomainTest | 21 passed; no failures, errors or skips | Both CSV formats, optional group columns, exact identity ambiguity, first/shared-last-name tokens and surname blocking, captured zones, phone normalization, DST, lead/grace and prompts |
| MmsPduTest | 2 passed; no failures, errors or skips | Independently decoded recipient headers, multipart lengths, exact UTF-8 body and invalid/oversize input |
| PlatformTest | 12 passed; no failures, errors or skips | SIM selection/freeze, count-specific templates, carrier limits, preserved region settings, permission/notification guards, delivery status and alarm/callback behavior |
| TransactionsTest | 28 passed; no failures, errors or skips | Atomic people batches, whole-group invalidation and overlap protection, group sent/uncertain outcomes, claim/cancel, expiry, crash gaps, review and retention |
| MigrationTest | 2 passed; no failures, errors or skips | Version-1 and version-2 schemas migrate to version 3 without losing people, pending frozen reminders, settings or duplicate protection |
| assembleDebug | Passed | Current application APK |
| assembleDebugAndroidTest | Passed | Maintained connected tests compiled; earlier Huawei suite and separately scoped Samsung procedures described above/below |
| lintDebug | Passed with 0 errors | 17 warnings and 1 hint; dependency-version notices, monochrome launcher icon, redundant SDK guard and a KTX suggestion |

**65 JVM tests passed in total** for the group snapshot described above. Runtime JVM database tests use unencrypted Room and Robolectric with Conscrypt disabled and legacy SQLite. These JVM tests do not themselves prove hardware encryption behavior; the earlier connected tests below exercised SQLCipher and Keystore on the Huawei before the CSV update. Samsung probes also reopened the production encrypted store and observed the persisted sent/delivered outcome.

Persistent reports:

```text
build\validation.log
build\device-build.log
build\device-instrumentation.log
build\device-final-ui.txt
build\csv-validation.log
build\sim-validation.log
build\group-final-validation.log
app\build\reports\tests\testDebugUnitTest\index.html
app\build\test-results\testDebugUnitTest\TEST-*.xml
app\build\reports\lint-results-debug.html
app\build\reports\lint-results-debug.xml
```

Gradle also reports deprecated features for a future Gradle 9 upgrade; this project pins Gradle 8.13. Robolectric's alarm inspection API produces test-only deprecation warnings.

## Earlier schema-4 APK evidence (before Auto ExecSec interface)

| Artifact | Location | Size |
|---|---|---|
| Debug app | `D:\AppointmentReminders\app\build\outputs\apk\debug\app-debug.apk` | 36,978,852 bytes |
| Debug instrumentation test | `D:\AppointmentReminders\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk` | 1,232,203 bytes |

Application APK SHA-256:

```text
7FA5250CCE4AF2B4F6AA1182D9EE11535FF2882E7E35CA1CBA2EA728981F49C9
```

`apksigner verify --verbose` verified the APK's v2 signature with one signer. `zipalign -c -P 16 -v 4` reported **Verification successful**. Inspection of every bundled native ELF library found 16,384-byte `PT_LOAD` alignment, including SQLCipher and AndroidX graphics path libraries for arm64-v8a, armeabi-v7a, x86 and x86_64. This checks signing/packaging/alignment, not actual hardware execution.

The packaged permission list was inspected with `aapt2 dump permissions`. Its only platform permissions are `SEND_SMS`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM` and `RECEIVE_BOOT_COMPLETED`. AndroidX additionally declares/uses the app-private, signature-protected `local.reminders.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, verified in the merged manifest. There is no internet, contacts, SMS-reading/receiving, location or broad-storage permission.

## Earlier connected Huawei results (before simplified CSV update)

The user explicitly authorized installing and launching the app and non-SMS synthetic testing on the attached phone. Target: **Huawei STK-LX3, Android 10 / API 29**. Both SIM slots reported `ABSENT`. No device serial or unrelated phone data is included here.

These observations apply to the prior installed APK (`D315AA6A72D53B9B3EE29194BB79CC672DCFCBCE8FA835B4DF556044AFC09299`), not the latest CSV APK above. No device action was taken for the CSV request.

The app and test APKs were installed and updated in place without clearing app data or uninstalling anything. An initial isolated encryption test passed. Expanded device validation reproduced a **real application crash** on entering a valid schedule preview: Android's ICU regex engine rejected the template pattern's unescaped closing brace, although the desktop Java regex engine accepted it. The closing brace is now escaped in `Templates`, preserving token behavior. The corrected application was rebuilt, installed, and tested again.

Final connected command, after authorized in-place APK installation:

```powershell
adb shell am instrument -w local.reminders.test/androidx.test.runner.AndroidJUnitRunner
```

Final result: **OK (5 tests)** in 19.561 seconds, with no failures or skips.

| Device check | Observed result |
|---|---|
| Launch and foreground UI | Corrected app cold-launched successfully; final resumed activity is `local.reminders/.MainActivity`; app-scoped final-process AndroidRuntime error log was empty |
| SQLCipher | Isolated randomly named synthetic database reopened correctly; persisted recipient sentinel absent from raw database bytes |
| Keystore wrapping and state persistence | Production Keystore-wrapped database key loaded consistently; separate synthetic SQLCipher database reopened with it and retained state |
| Cancel/edit/reapproval transactions | Duplicate approval rejected, pending cancellation allowed, changed input cancelled approval, fresh approval persisted; not-due claim returned null and no attempt was created, all in an isolated encrypted database without alarm/SMS dispatch |
| Real Compose import | Imported two synthetic people through CSV and confirmation; saved people remained after Activity recreation and previous instrumentation-process relaunch |
| Validation | Invalid appointment date produced a row error, not a crash; unrelated source columns were not shown in review |
| Preview | Exact normalized numbers, default rendered first name/message, 2:00 PM today, 6:30 PM tonight after appointment edit, one SMS segment, recipient resolution and discard were exercised on screen |
| No-SIM guard | Setup showed no selectable subscription; approval UI remained disabled; real runtime rejected an approval request before creating a reminder or send attempt |
| Permissions | Initially observed SMS and phone-state denial with actionable UI. Later read-only package checks showed both granted and `RESTRICTION_INSTALLER_EXEMPT` for SMS. No grant/revoke command or permission-setting UI action was issued by this validation; the source of the intervening grant is unverified |
| Exact-alarm access | Android 10 has no Android 12+ exact-alarm special-access flow; no special access was requested or changed |
| Notifications | Existing notification access was enabled; a real generic, private input-channel notification was posted and its actual content PendingIntent opened Import |
| Night-before logic / alarm registration | On-device deterministic one-off/coverage tests passed; read-only `dumpsys alarm` showed the app's `RTC_WAKEUP` `local.reminders.PROMPT` alarm registered. No overnight wait or actual scheduled alarm delivery was performed |
| Final state | Corrected app remains installed and running on Queue, showing **Automatic sending is blocked / No active SMS subscription is available** and no approved reminders |

Two explicitly synthetic recipients (`huawei_alex`, `huawei_sam`, names ending `Device Example`, reserved `+1 202 555-01xx` numbers) remain in People for inspection. No schedule was approved. The tests clean up only their own randomly named synthetic databases. A generic preparation notification was posted; no real recipient data, SMS history or other app data was accessed.

## Remaining limitations

The earlier Huawei validation attempted no SMS. Samsung procedures submitted exactly one authorized individual SMS with a successful delivery report and one separately authorized shared group MMS with a successful sent callback. Participants confirmed group receipt and reply-to-all; the app did not infer per-person delivery from that callback. No permissions or system settings were changed by validation commands, no data was cleared, and no reboot, battery change, security-warning bypass or OS authentication was performed. No commit or GitHub push was made for the device-test changes.

The app is **not yet verified for general unattended reliability**. Samsung evidence establishes one scheduled single-part submission and successful delivery report on its authorized installation. Huawei still has no SIM. Neither observation establishes that another installation/distribution path can grant hard-restricted SMS permission.

Still unexercised: live dual-SIM switching; live-queue edit/cancel/reapproval; multipart/partial/failed carrier outcomes; no-service/airplane and revocation tests; overnight input-alarm delivery; locked idle/Doze; reboot/first-unlock recovery; force-stop; manufacturer background restrictions; document-picker file intake; credential viewing lock; TalkBack/large-font/dark-mode visual review. UI semantics/display assertions were exercised on both real devices; no pixel screenshots were captured because the app deliberately protects its window.

Release signing/distribution work is paused. This remains a debuggable build, unsuitable as a production private-data assurance. Follow the remaining device checklist in `README.md`; any additional real SMS or MMS requires new user authorization.
