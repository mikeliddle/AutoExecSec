# Native task interface

## Platform and mode

Android / Operate. Material 3 interaction and layout conventions take precedence over decorative expression. The app is an approval tool, not a delivery-performance dashboard.

## Visual system

System sans-serif through Material's title/body/label roles. Cool mineral light surfaces and a blue primary action; explicit dark scheme with light blue action roles. Colors are referenced through Material roles in screens; the only literal palette values are centralized in `ReminderTheme.kt`.

Compact windows use four labeled navigation destinations: Queue, Import, People, Setup. At 700 dp the same destinations move to a navigation rail. Reading/forms have a 760 dp maximum width, scroll vertically and respect system bars/keyboard insets. Material buttons, switches, filter chips, dialogs, progress indicators and snackbars preserve Android-native affordances.

## Hierarchy

The queue leads with capability state, the next preparation action and the frozen message rows. Appointment and send times are explicitly labeled, with zone, exact phone number, selected SIM and segment count. Uncertain outcomes have a distinct warning and no resend control.

Import is a preparation step, not authorization. Review shows normalized recipient choices and rendered values, followed by the explicit authorization checkbox and batch-confirmation dialog. Unsaved appointment edits block approval.

People uses visible manual fields and a separate CSV intake. Setup groups device feasibility, SIM/message, night-before prompts, privacy/retention and deletion. Dangerous local changes use confirmation dialogs; operation errors appear in dismissible snackbars rather than disappearing silently.

## State and privacy

Busy controls are disabled and a linear indicator is shown. First load shows a progress indicator; empty states explain the next valid action. Capability failure is visible, and no device SMS capability is inferred from the presence of an APK.

Sensitive views use `FLAG_SECURE`. The optional credential screen protects viewing without weakening unattended dispatch after first unlock. Generic OS notifications stay outside the app-private visual/data surface.

## Evidence boundary

The implementation is native Compose, not a web preview. Hardware rendering, large-font behavior, TalkBack and device screenshots remain to be inspected on an authorized install; no phone installation or settings changes were performed to create this record.
