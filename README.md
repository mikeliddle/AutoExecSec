# Auto ExecSec

Auto ExecSec is an Android app for preparing appointment reminders and sending them through your phone's SIM. You review the recipients, message text, and send details before approving anything. The app stores its data on the phone and does not require an account or a backend.

- **Appointment reminders:** Enter people and appointments in editable tables or import CSVs. Schedule individual SMS or a shared group MMS.
- **Announcements:** Write one announcement and review a separate, private SMS for each selected person. Unlike group MMS, recipients do not see each other's numbers.
- **Copy-only templates:** Save named text templates, fill their dynamic fields and copy the result to another app. No messages are sent by this feature.
- **Preparation and follow-up:** Reuse message templates, get a night-before prompt to prepare the next workday's schedule, and check the queue for send outcomes.

## Install

Auto ExecSec requires **Android 8.0 or later**. No published release is provided here. For testing, use a trusted local debug APK (skip to step 2), or build one from this checkout:

1. Install JDK 17, Android SDK Platform 36 and Build Tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME` to their install locations. From the repository root, run `.\gradlew.bat :app:assembleDebug` on Windows or `./gradlew :app:assembleDebug` on macOS/Linux.
2. With Android `adb` and an authorized test phone connected, run `adb install app/build/outputs/apk/debug/app-debug.apk`, or substitute the path to your existing APK. This is a debug build for testing, not a production release; use synthetic data.
3. Open **Setup**, review diagnostics, grant the supported permissions and select the intended SIM if prompted. Android requires the installer to allow SMS permission; sideloading alone does not guarantee sending. See [device setup and installation](docs/build.md#device-feasibility-and-installation) if a capability is blocked.

An active SIM and a compatible carrier plan are required for sending; charges may apply. Delivery is not guaranteed. **Do not use Auto ExecSec for safety-critical reminders.**

## Documentation

- [Using Auto ExecSec](docs/usage.md) and [CSV formats, sample files, and message templates](docs/formats.md)
- [Local build and device setup](docs/build.md) and [release signing and distribution](docs/release.md)
- [Architecture and privacy](docs/architecture.md), [verification checklist](docs/verification.md), and [validation evidence](docs/validation.md)
- [Product brief](PRODUCT.md) and [interface design](DESIGN.md)
