# Native task interface

## Platform and mode

Android / Operate. Material 3 interaction and layout conventions take precedence over decorative expression. The app is an approval tool, not a delivery-performance dashboard.

## Visual system

System sans-serif through Material's title/body/label roles. Cool mineral light surfaces and a blue primary action; explicit dark scheme with light blue action roles. Colors are referenced through Material roles in screens; the only literal palette values are centralized in `ReminderTheme.kt`.

The product display name is Auto ExecSec; Android package and encrypted data identity are unchanged. Compact windows use five labeled navigation destinations: Queue, Schedule, Announcements, People, Setup. At 700 dp the same destinations move to a navigation rail. Reading/forms have a 760 dp maximum width, scroll vertically and respect system bars/keyboard insets. Material buttons, switches, filter chips, dialogs, progress indicators and snackbars preserve Android-native affordances.

## Hierarchy

The queue leads with capability state, the next preparation action and the frozen message rows. Appointment and send times are explicitly labeled, with zone, exact phone number, selected SIM and segment count. Uncertain outcomes have a distinct warning and no resend control.

People and Schedule lead with labeled, editable rows and an adjacent Import button. Compact People rows place names together and the phone below; wider rows use three columns. Schedule has native calendar/time/person pickers, optional group members and an explicit captured zone. Rows can be added/deleted. File selection and secondary CSV paste populate the same table; invalid imports preserve drafts, and replacing nonempty rows requires confirmation. Cell errors stay beside the relevant field. Editing a saved person returns to the table without changing their stable identity.

Entry/import is preparation, not authorization. Review shows normalized recipient choices and rendered values, followed by the explicit authorization checkbox and confirmation dialog. Unsaved appointment edits block approval. Back to table retains source edits. Group number/reply visibility warnings, exact frozen values and SIM/permission blockers are not hidden.

Announcements is distinct from appointment schedules and shared group MMS: one private SMS per unique number. Composition leads with message and recipient selection; templates/pacing and secondary explanations expand on demand. Full consent/carrier/privacy notices remain visible at review. A saved Announcement template reuses the appointment renderer without overwriting appointment preferences; appointment-only fields block preview. Review freezes every rendered message, unique number, SIM, segment count, delay and expiry. Pacing is never carrier permission or guaranteed timing/delivery. Both acknowledgments and final confirmation remain required. Outcomes and stop-remaining controls stay on this surface; Queue links active Announcements.

Queue has one primary preparation action; the no-reminders date form is expandable. Setup progressively discloses templates, night-before prompts, privacy/retention and diagnostics while keeping capability blockers and legitimate permission requests visible. A sole active SIM is identified without a redundant chooser. Dangerous changes retain confirmation dialogs; errors use dismissible snackbars.

## State and privacy

Copy-only Templates is reached through the labeled document action in the top app bar, avoiding a sixth cramped bottom-navigation destination. The native screen moves from named definitions to labeled variable fields and live preview, with one primary Copy output action. Create/edit is an inline form; deleting definitions and discarding edits require confirmation. Repeated variables create one field, missing values disable copying, and filled values are not saved. Existing system typography, mineral Material roles, full-width controls and vertical scrolling support keyboard and larger fonts. These templates are independent of the sending renderers.

Busy controls are disabled and a linear indicator is shown. First load shows a progress indicator; empty states explain the next valid action. Capability failure is visible, and no device SMS capability is inferred from the presence of an APK.

Sensitive views use `FLAG_SECURE`. The optional credential screen protects viewing without weakening unattended dispatch after first unlock. Generic OS notifications stay outside the app-private visual/data surface.

Table/composition drafts stay in ViewModel memory across navigation and configuration changes, not process death. No sensitive draft is written to an unencrypted SavedState/Bundle. Saved settings, recipients, encrypted audit history and approved queues keep existing storage and reapproval rules; branding adds no database migration.

## Evidence boundary

The implementation is native Compose, not a web preview. Earlier authorized Huawei/Samsung evidence applies to its identified older APKs. Current table/navigation/import/confirmation interactions are exercised with offline Robolectric Compose tests at phone/tablet sizes and a 1.6x-font phone configuration, not a connected phone or pixel review. No emulator/AVD is available locally. Pixel appearance, TalkBack and broader visual/accessibility review remain unverified. No device action is authorized for this interface update; see [validation evidence](docs/validation.md).
