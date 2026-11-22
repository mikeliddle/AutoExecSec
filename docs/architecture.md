# Architecture and privacy

Auto ExecSec is a native Android app built with Kotlin and Jetpack Compose. It uses Room and SQLCipher for local persistence and the platform's selected-SIM messaging APIs, with no backend, app Internet permission, Google API, AI parser, or telemetry. See the [interface design](../DESIGN.md) and [product brief](../PRODUCT.md) for the app's design constraints.

## Announcements transport and storage

Transport uses the same subscription-specific `SmsManager.sendTextMessage` / `sendMultipartTextMessage` path as individual reminders, once per number. No group recipients, RCS, MMS fallback, cloud gateway, sender-number spoofing, new SMS permissions or default-SMS-role request are involved. Android [documents](https://developer.android.com/reference/android/telephony/SmsManager) that, for a non-default SMS app on Android 4.4+, these standard methods automatically write outgoing messages to the system SMS provider. This preserves the existing default messaging app and is the basis for expecting outgoing texts in individual conversations, with private replies to the selected SIM's number. **OEM messaging-app display, conversation persistence and carrier behavior for this feature have not been verified on a physical phone.** Compilation/mocked results do not prove them, and no real SMS was sent for batch validation.

Local-only describes the app's storage/transport architecture, **not end-to-end encryption**. SMS is not end-to-end encrypted; copies may remain in messaging apps, device/provider backups or carrier systems even though this app disables its own backup. Do not send sensitive information on the assumption that local-only means confidential transport.

Room schema 4 adds nullable broadcast metadata to schema 3 without changing existing frozen SMS/MMS values, recipients, coverage or duplicate protection. No destructive migration is configured. Existing appointment functionality and shared-MMS approvals remain separate.

## Group MMS transport

Group reminders use the public selected-subscription `SmsManager.sendMultimediaMessage` API, not multipart SMS or private fanout. All group numbers are To recipients in one text-only MMS 1.2 SEND_REQ, with a SMIL presentation and UTF-8 text. The small encoder is implemented here without hidden APIs, reflection or third-party MMS libraries. Carrier group-MMS enablement, recipient limits and size limits are checked at approval and dispatch; unknown/unsupported configurations block, never fall back to SMS.

The app still has no Internet/APN-write/inbox permissions and does not request the default-SMS role. Android's privileged MMS service manages carrier APN/data transport. A non-exported, read-only provider grants access only to the exact opaque attempt URI through the public MMS API. It serves the frozen payload through a bounded memory-backed proxy descriptor after first unlock; no plaintext PDU file is stored. The encrypted queue remains authoritative. MMS is capped locally at 64 KiB, including at most 16 KiB UTF-8 text, and uses the carrier's smaller limit.

There is no public MMS delivery PendingIntent, so group history reports delivery as unavailable. MMS callbacks get a 35-minute observation deadline because Android can wait for a carrier data network. The app hands off only inside the original dispatch window, but Android/carrier transport can continue or retry after handoff. Missing callbacks or process interruption leave an uncertain outcome and block duplicate sends.

Primary references: [public MMS API](https://developer.android.com/reference/android/telephony/SmsManager#sendMultimediaMessage(android.content.Context,%20android.net.Uri,%20java.lang.String,%20android.os.Bundle,%20android.app.PendingIntent)), [default messaging app boundary](https://developer.android.com/reference/android/provider/Telephony#creating-an-sms-app), [AOSP MMS broker](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/MmsServiceBroker.java), [non-default outgoing MMS persistence](https://android.googlesource.com/platform/packages/services/Mms/+/refs/heads/main/src/com/android/mms/service/SendRequest.java).

## Alarm scheduling

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
