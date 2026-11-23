# Release signing and distribution

The **Publish Android** workflow builds and publishes the APK to this private repository. You do not upload APKs yourself. Publishing is manual so an ordinary push never creates a release.

## One-time setup

1. Merge the workflow to `main` and protect `main` against unreviewed changes. Both signing and publication require a private repository and protected `main`. GitHub Free does not support branch protection for private personal repositories; use a plan that does (such as GitHub Pro), not a visibility change.
2. Create a dedicated Android release keystore locally. Keep the keystore, its alias and passwords in a recoverable encrypted backup. Use this key for future releases. A production APK cannot replace an existing `local.reminders` app signed with a different key, but it can coexist with `local.reminders.debug`.
3. In **Settings → Secrets and variables → Actions**, add repository secrets `ANDROID_RELEASE_KEYSTORE_B64` (base64 of the keystore file), `ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_STORE_PASSWORD`, and `ANDROID_RELEASE_KEY_PASSWORD`. Add a repository variable `ANDROID_RELEASE_CERT_SHA256` containing the signing certificate's public SHA-256 fingerprint (64 hex characters, no colons). Use the settings UI or an approved secret-input process; do not paste secrets into chat, commit them, or reuse a debug keystore. Restrict who can change workflows or access signing settings.

## Publish a version

1. Set the next `versionCode` and `versionName` in `app/build.gradle.kts` and get the reviewed commit onto `main`. The version code must increase for each release.
2. In **Actions → Publish Android → Run workflow**, choose `main` and `verify`. Enter the full commit SHA and both version values (currently `1` and `1.0`). Actions tests and builds an unsigned release APK, then a separate runner validates the package, permissions, alignment, and signing certificate. The run summary records the signed APK's run ID, artifact ID and SHA-256. The artifact expires after seven days.
3. Run the same workflow in `release` mode with the same commit and version plus those three values. Actions promotes the exact verified APK without rebuilding it and publishes `AutoExecSec.apk` and `SHA256SUMS` under `android-<versionCode>`. Downloads require access to the private repository.

If `main` moves, verify the new commit. If a run leaves a draft, inspect it before retrying; the workflow will not overwrite a draft, release, or existing Android version tag. The workflow refuses public publishing. Revisit the threat model and add enforceable owner review for signing and publishing before making the repository public.
