# FacePsy: contributor & agent guide

This is an Android app (Kotlin) that captures front-camera frames when triggers fire,
extracts facial features on the device, and uploads them to Firebase. Background
reading: [docs/architecture.md](docs/architecture.md),
[docs/data-schema.md](docs/data-schema.md), [docs/known-issues.md](docs/known-issues.md).

## Build

Toolchain: AGP 8.7, Gradle 8.9, Kotlin 1.9, **JDK 17** (Gradle 8.9 doesn't run on JDK 25,
Android Studio's bundled JBR, so point `JAVA_HOME` / the Gradle JDK at a JDK 17).
compileSdk 35, **targetSdk 28** (deliberately; raising it changes runtime behavior).

```bash
export JAVA_HOME=/path/to/jdk17           # e.g. a portable Corretto 17 in ~/.jdks
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
cp /path/to/google-services.json app/     # gitignored; Firebase project config
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No NDK, CMake or OpenCV is needed. There are no meaningful tests
(`app/src/test` holds only the template). Verify changes on a device.

## Layout

```
app/src/main/java/com/rahulislam/facepsy/
  MainActivity, FacePsyAccessibilityService   (root: FQCNs are pinned, see below)
  FacePsyApplication                          (creates SensingService.kronosClock at process start)
  capture/     VideoCaptureSession             (records front-camera MP4 via CameraX)
  data/        FirebaseRefs, TriggerContract   (all Firebase paths + broadcast contract)
  service/     SensingService, ServiceAction, ServiceStateStore, CrashRestartHandler
  receiver/    CaptureTriggerReceiver, ScreenEventLogReceiver, BootReceiver
  processing/  VideoProcessingWorker, FaceFeatureExtractor (ML Kit + LiteRT AU model + upload);
               ImageProcessingWorker (legacy, drains photo jobs queued before the update)
  messaging/   FacePsyMessagingService         (FCM)
  tasks/       flower/ (3x3 + 4x4 memory game, FlowerGameCommon), stroop/
  setup/       SetupStep, SetupMonitor          (permission checklist + re-checks)
  ui/          SetupActivity, InstructionActivity
  util/        ContextExt, Log
scripts/       configure_firebase.py (seeds Firestore `config`)
```

## Hard invariants (breaking these silently breaks studies or existing installs)

- **Pinned class names.** Don't rename or move these:
  - `com.rahulislam.facepsy.FacePsyAccessibilityService`: participants enable it by
    component name in Settings.
  - `com.rahulislam.facepsy.processing.VideoProcessingWorker` and
    `com.rahulislam.facepsy.processing.ImageProcessingWorker`: WorkManager persists the
    class name for queued work. (ImageProcessingWorker is kept only to drain photo jobs
    queued by an older version.)
  - `com.rahulislam.facepsy.MainActivity`: the launcher and pinned shortcuts point to it.
- **Firebase names.** Firestore collections and config docs, RTDB paths, the Storage path
  and every document field name (including the mixed `snake_case`/`camelCase` ones) are a
  data contract. They live in `data/FirebaseRefs.kt` and at the write sites. Don't
  change their values.
- **Keys and strings:**
  - broadcast action `com.rahulislam.facepsy.triggers` and its extras `packageName`,
    `duration`, `gameId`;
  - WorkManager keys `VIDEO_PATH`, `STARTED_AT`, `SEQ_ID`, `GAME_ID`, `TRIGGER_NAME` and
    tag `video-feature-extraction` (and the legacy photo keys `IMAGE_URI`, `TIMESTAMP`,
    `SEQ_ID`, `GAME_ID`, `TRIGGER_NAME`, tag `feature-extraction`);
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
