# FacePsy: contributor & agent guide

This is an Android app (Kotlin) that captures front-camera frames when triggers fire,
extracts facial features on the device, and uploads them to Firebase. Background
reading: [docs/architecture.md](docs/architecture.md),
[docs/data-schema.md](docs/data-schema.md), [docs/known-issues.md](docs/known-issues.md).

## Build

The toolchain is old: AGP 3.4.3 and Gradle 5.1.1. It needs **JDK 8** (11 at most);
newer JDKs, including Android Studio's bundled JBR, won't run it.

```bash
export JAVA_HOME=/path/to/jdk8            # e.g. a portable Corretto 8 in ~/.jdks
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
cp /path/to/google-services.json app/     # gitignored; Firebase project config
./gradlew :app:assembleDebug              # SDK platform 28 / build-tools 28.0.3 auto-download
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No NDK, CMake or OpenCV is needed. There are no meaningful tests
(`app/src/test` holds only the template). Verify changes on a device.

## Layout

```
app/src/main/java/com/rahulislam/facepsy/
  MainActivity, FacePsyAccessibilityService   (root: FQCNs are pinned, see below)
  FacePsyApplication                          (creates SensingService.kronosClock at process start)
  data/        FirebaseRefs, TriggerContract   (all Firebase paths + broadcast contract)
  service/     SensingService, ServiceAction, ServiceStateStore, CrashRestartHandler
  receiver/    CaptureTriggerReceiver, ScreenEventLogReceiver, BootReceiver
  processing/  ImageProcessingWorker           (ML Kit + TFLite AU model + upload)
  messaging/   FacePsyMessagingService         (FCM)
  tasks/       flower/ (3x3 + 4x4 memory game, FlowerGameCommon), stroop/
  ui/          InstructionActivity
  util/        ContextExt, Log
library/       vendored HiddenCam (Apache-2.0, see NOTICE); don't restyle, keep diffable
scripts/       configure_firebase.py (seeds Firestore `config`)
```

## Hard invariants (breaking these silently breaks studies or existing installs)

- **Pinned class names.** Don't rename or move these:
  - `com.rahulislam.facepsy.FacePsyAccessibilityService`: participants enable it by
    component name in Settings.
  - `com.rahulislam.facepsy.processing.ImageProcessingWorker`: WorkManager persists the
    class name.
  - `com.rahulislam.facepsy.MainActivity`: the launcher and pinned shortcuts point to it.
- **Firebase names.** Firestore collections and config docs, RTDB paths, the Storage path
  and every document field name (including the mixed `snake_case`/`camelCase` ones) are a
  data contract. They live in `data/FirebaseRefs.kt` and at the write sites. Don't
  change their values.
- **Keys and strings:**
  - broadcast action `com.rahulislam.facepsy.triggers` and its extras `packageName`,
    `duration`, `gameId`;
  - WorkManager keys `IMAGE_URI`, `TIMESTAMP`, `SEQ_ID`, `GAME_ID`, `TRIGGER_NAME` and
    tag `feature-extraction`;
  - SharedPreferences `SPYSERVICE_KEY` / `SPYSERVICE_STATE`;
  - notification channel `ENDLESS SERVICE CHANNEL`;
  - wake-lock tag `EndlessService::lock`.
- **Looked up by name at runtime:**
  - `newGame(View)` is bound through `android:onClick` in the flower layouts;
  - flower buttons are found as `button_0…button_N` via `getIdentifier`;
  - Stroop ink colors are the color resources `RED`, `GREEN`, `BLUE`, `YELLOW`.
- **Manifest.** When moving a manifest-registered component, update
  `AndroidManifest.xml` and the layout `tools:context` values in the same change.
- **Known bugs** are listed in `docs/known-issues.md` and marked
  `NOTE: legacy behavior` in code. Fix them in dedicated, behavior-changing commits,
  never mixed into refactors.
