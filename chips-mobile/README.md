# CHIPS Android

The app opens to Today, with History and Settings available from the bottom navigation. Assistant enrollment and microphone access are optional for browsing. Settings can enable fictional dashboard previews; preview taps never persist or contact the backend.

## Build

Use Android Studio with support for Android Gradle Plugin 9.4.1 and Android SDK Platform 36. The checked-in Gradle wrapper uses Gradle 9.6.0. CI uses JDK 17; the local build also passed with Android Studio's bundled JBR 25. Kotlin and the Compose compiler use version 2.2.10.

The Gradle and AGP versions include the updates applied during Android Studio setup. Keep the [Compose compiler version aligned with Kotlin](https://kotlinlang.org/docs/compose-compiler-migration-guide.html).

Open this directory in Android Studio, let it create `local.properties` with your `sdk.dir`, then run:

```sh
bash ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
bash ./gradlew :app:connectedDebugAndroidTest
```

The second command requires an emulator or USB-connected device with debugging enabled. The app supports API 26 and later and currently targets API 36. CI builds, lints, runs unit tests, and runs navigation/preview instrumentation on an API 35 emulator. Minimum-API, target-API and Poco device checks are still required before the milestone can close.

## Voice development service

The existing voice path remains a demo: backend transcription is a stub. Dashboard and history do not require the service. To try voice, run the FastAPI service with its database/provider configuration, then set an endpoint in `local.properties`:

```properties
# Keep the quotes: this value becomes a generated Java String literal.
BASE_URL="http://10.0.2.2:8000/api/v1"
```

`10.0.2.2` reaches the development host from an Android emulator. A physical phone needs a reachable server address. `local.properties` is ignored by Git. Provider keys belong on the backend. Release authentication, encrypted transport, real transcription and persistent voice history remain roadmap work.

To use the overlay, select CHIPS as the device assistant from Settings. To try voice without changing the device assistant, grant microphone access and tap Ask CHIPS on Today. Results remain visible until dismissed; they are not saved yet.

## Manual acceptance checks

- Fresh launch opens Today without a permission prompt or assistant requirement.
- All seven dashboard cards scroll; Quick Log stays pinned. Verify at large font sizes and both gesture/button navigation on the Poco X6 Pro.
- Normal mode has no fictional scores. Settings → Show preview data explicitly enables sample values and charts.
- Today and Quick Log preview actions share completion state, say nothing was saved, and never create history. Turning preview off clears those completions.
- Ask CHIPS without microphone permission opens Settings. Denial leaves normal navigation usable; permanent denial has an App settings route.
- Returning from Android settings refreshes permission/assistant status. Check in-app and system-overlay recording separately.
- Background/dismiss during recording releases the microphone. Real transcription, transport cancellation and recorder-failure coverage are tracked under M3.

## Validation status

Source restored from GitHub commit `46f0c8510f0bdf2d1c55a779b23a62bb9cd9d857` on 19 October 2026. After Android Studio installation, the local debug build, lint and all four unit tests passed on 20 October with Gradle 9.6.0, AGP 9.4.1 and bundled JBR 25. Lint reported zero errors and 29 warnings; those warnings remain follow-up work.

An earlier CI revision passed all three instrumentation tests on an API 35 emulator. CI is rechecking the latest revision. No physical-device or lock-screen acceptance result has been recorded; M1 remains open until the Poco checks above are complete.
