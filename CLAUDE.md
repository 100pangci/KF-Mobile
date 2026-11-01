# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

Requires JDK 21 and Android SDK 36. On Windows, run the Gradle wrapper from PowerShell:

```powershell
# Standard verification (leave live-test flags unset)
.\gradlew.bat :shared:desktopTest :androidApp:assembleDebug :androidApp:lintDebug

# One shared JVM test class
.\gradlew.bat :shared:desktopTest --tests 'work.miaola.kf.NativeControllerTest'

# Individual Android tasks
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :androidApp:lintDebug

# Python tooling tests and local package generation
python -m unittest tools/test_probe_login.py tools/test_package.py
python tools/package.py
```

The `desktopTest` target runs shared/common tests on the JVM and does not need an emulator. Add a test method name to the Gradle `--tests` pattern to run one method. CI uses the equivalent `./gradlew :shared:desktopTest :androidApp:assembleDebug --stacktrace` tasks on Linux. Run the Gradle test, assemble, and lint tasks before `tools/package.py`; packaging checks their generated reports before creating the APK/source archive.

`KF_LIVE_TEST`, `KF_LOGIN_TEST`, `KF_SITE_AUDIT`, and `KF_AUDIT_RENDER` enable opt-in test paths; leave them unset for ordinary verification. The site audit is policy-checked, read-only, and uses an existing local cookie; audit rendering uses private local captures. Read `docs/native-coverage.md` before running these paths, and do not run authenticated or live-site tests without authorization.

## Architecture

- `shared` is the Kotlin Multiplatform core. `commonMain` contains domain models, repository/controller state, shared Compose UI, and platform-independent policies. `jvmSharedMain` supplies the HTTP and HTML-parsing implementation used by both Android and the desktop JVM target (`OkHttp` and `Jsoup`). The desktop target lets the shared parser, controller, and UI behavior be tested without an emulator.
- The main flow is shared Compose UI → `AppController` → `DomainRepository` / HTTP service → HTML parsers → domain/page models → controller state. The Android app hosts the shared UI and provides Android entry/navigation, secure local storage, and image/media integrations.
- Site pages are parsed into native models and Compose screens, with a generic native fallback for unsupported structures; this is not a WebView-based client. `SiteRoutePolicy` controls which network routes are allowed, while `SiteActionRisk` controls confirmation UX. Keep those checks independent. Preserve conservative handling of unknown routes/actions, fresh form revalidation before writes, and uncertain mutation outcomes rather than treating an HTTP success code or redirect as proof of a completed site action.

For site protocol, parser, routing, forms, mutation receipts, and audit constraints, use `docs/native-coverage.md` as the detailed reference. For navigation, paging, editor, recovery, and interaction behavior, see `docs/native-interaction-pass.md`. JVM rendering and synthetic tests do not establish Android-device behavior; use `docs/android-device-checklist.md` for device-level verification.
