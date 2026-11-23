# Validation evidence and limits

Build prerequisites are in [build instructions](build.md); separately authorized
device checks are in [verification guidance](verification.md). Results below are
bounded to their source/APK snapshots, not general reliability certification.
Device identities, hands-on test dates and personal operating details are
intentionally omitted. Historical commits are not rewritten by this cleanup.

## Current application identity and local checks

Production uses `fyi.mllab.autoexecsec`; debug uses
`fyi.mllab.autoexecsec.debug`. Kotlin namespace/component classes remain
`local.reminders`. Changing the application ID does not migrate old
`local.reminders` / `local.reminders.debug` data, permissions or approved queues.
Old installations remain separate and may retain active queues.

For the application-ID snapshot based on `583160a8d70a07b7d20668b2766de4f628ead52f`,
offline `testDebugUnitTest`, `lintRelease`, `assembleRelease`, `assembleDebug` and
`assembleDebugAndroidTest` passed: **105 JVM tests, no failures/errors; no lint
errors and three existing warnings** (mutable navigation-history collection,
redundant v26 icon resource folder and missing monochrome icon).

Actual manifests verify both package IDs, original application/activity/provider
classes, application-specific MMS/startup authorities and signature-protected
receiver permission. The added runtime test checks notification activity targeting,
MMS authority/grant isolation and absence of the old MMS authority. Release is
non-debuggable and unsigned; debug retains its development certificate. The test
APK is `fyi.mllab.autoexecsec.debug.test`, targeting the debug package.

| Local artifact | SHA-256 |
|---|---|
| `app\build\outputs\apk\release\app-release-unsigned.apk` | `f3742328291fcca2ac640f1fb613a60fd9598c7733c95ceea9b9094c60c296ae` |
| `app\build\outputs\apk\debug\app-debug.apk` | `6f85cc097f2b44d7fac7eb3b1b821e2131679a5479d4a34fa70f759bd5e5fe6e` |

These are local artifacts, not published releases. No physical startup, provider,
carrier or migration evidence has been established for the renamed packages.

## Publishing workflow verification

Run `45267770025` on `aa6d48b18e4ba4260b3c697ee2c846f78a0509a9`
passed tests/lint/release compilation but failed **Inspect unsigned production
artifact**. `apksigner verify` correctly rejected the unsigned APK with exit 1;
the script then completed without clearing that expected native exit code.
GitHub's PowerShell wrapper propagated it and failed the step. Signing and
publishing were skipped. This was separate from the Node.js 20 action warning.

The corrected check accepts only exit 1 with the exact unsigned-manifest
diagnostic, rejects successful signature verification or unexpected tool errors,
and resets the native exit status only after validating that expected result.
The same input check protects the signing job. Checkout and artifact-upload
actions are full-SHA-pinned Node.js 24 versions, compatible with the
GitHub-hosted Windows runner. No action-policy setting is weakened.

Local checks cover actionlint, native PowerShell syntax, the actual unsigned APK
through GitHub-equivalent PowerShell exit handling, signed/wrong-package input
rejection and unexpected verifier failures. Thirty offline publishing-gate cases
cover unsigned-only verification, owner/rerun restrictions, protected unchanged
main, both independent reviewer/bypass/branch gates, and invalid signing
fingerprints. These are not a hosted end-to-end release or environment approval.

The secret-free build, isolated signing/publication runners, separate owner gates,
immutable artifact hashes, fixed signing-tool checksum and package/permission/
certificate/alignment/version checks remain in place. Full MIT/Apache/SQLCipher
licenses and third-party attribution are packaged for offline **Setup > Licenses**.
The license disclosure test exercises all sections at 360x800 dp and 1.6x font
scale; packaged license assets were verified against source bytes.

## Automated coverage

Domain/runtime/migration suites cover deterministic CSV/table validation,
recipient ambiguity, captured appointment zones and DST, SMS segments, group
templates/surname blocking, lead/grace/expiry, prompt coverage, frozen approvals,
SIM selection, claim/cancel races, duplicate prevention, crash uncertainty,
multipart callbacks, retention and schema 1/2/3-to-4 preservation.

Announcements tests cover number deduplication and limits, individual frozen text,
saved templates, bounded pacing, one in-flight recipient, callback idempotence,
recovery and failure/unknown-outcome handling without blind retry. UI tests cover
table CRUD/import failure and explicit replacement, draft retention, dynamic
template fields, create/edit/delete, live preview, sensitive clipboard copying,
large-font interactions and explicit send consent. MMS PDU tests independently
decode recipient headers, lengths and UTF-8 content.

JVM database tests use unencrypted in-memory Room/Robolectric, not production
SQLCipher. On Windows ARM64, Robolectric's legacy SQLite library requires an
x64 JDK under emulation; compiling with an ARM64 JVM alone does not validate
those tests. Test/lint reports are under `app\build\reports` and
`app\build\test-results`; future builds replace them.

## Historical physical-device findings

The following observations apply only to earlier `local.reminders` debug builds,
not the renamed application or a production-signed release:

- **API 29 synthetic checks:** five connected tests passed for SQLCipher/Keystore
  reopen and state persistence, cancel/edit/reapproval transactions, Compose
  intake/preview, validation and no-SIM approval blocking. A genuine Android ICU
  regex incompatibility was reproduced and fixed by escaping the closing brace.
  A generic preparation notification opened Import. Alarm registration was
  observed, not overnight alarm delivery.
- **API 36 individual SMS:** one separately authorized single-segment reminder
  dispatched through its natural alarm with one attempt, a successful sent
  callback and a successful delivery report. Test APK SHA-256:
  `6BDED5C01BAC94C8A6BBD78312EFAF73385BA9D71E1EA1BB854424B719488795`.
  This does not establish multipart behavior, idle reliability or installer
  eligibility on another installation.
- **API 36 shared MMS:** one separately authorized two-recipient reminder reached
  SENT with one attempt and successful MMS callback. Participants reported one
  shared conversation, receipt and reply-to-all; that report is distinct from
  per-recipient delivery evidence, which the callback does not provide.
  APK SHA-256:
  `7FA5250CCE4AF2B4F6AA1182D9EE11535FF2882E7E35CA1CBA2EA728981F49C9`.
  The encrypted schema-3-to-4 store reopened and non-sending UI/provider checks
  passed. Temporary send-capable instrumentation was removed.
- **Packaging/startup:** historical debug updates/fresh installations launched
  without app-scoped fatal exceptions. In-place updates preserved app data.
  Signature, ZIP alignment and 16-KB native ELF alignment checks passed for the
  inspected snapshots; that is not execution evidence for every supported ABI.

Recipient destinations were transient test input, not source fixtures or public
evidence. No inbox/conversation reading, default-SMS role, shell permission
bypass or data clearing was used to claim success. These observations authorize
no further device operations or live sends.

## Remaining limitations

General unattended reliability is **unverified**. Still unexercised: live dual-SIM
switching; live-queue edit/cancel/reapproval; multipart/partial/failed carrier
outcomes; no-service/airplane and revocation tests; overnight input-alarm delivery;
locked idle/Doze; reboot/first-unlock recovery; force-stop; manufacturer background
restrictions; document-picker intake; credential viewing lock; and complete
physical TalkBack/large-font/dark-mode review. Local Compose assertions do not
replace those checks. Announcements have no live carrier-send evidence.

Installer allowlisting for hard-restricted SMS access is installation-specific.
No historical result guarantees permission eligibility, carrier receipt or
minute-perfect dispatch on another device. A sent callback is not delivery proof;
submitted messages cannot be recalled. Further live SMS/MMS requires separate,
explicit authorization.
