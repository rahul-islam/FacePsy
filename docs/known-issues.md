# Known issues

The structure/naming refactor on branch `refactor/structure-and-docs` deliberately did
**not** change runtime behavior. The issues below were found during that work and have
been left for a follow-up. In the code they are marked `NOTE: legacy behavior, see
docs/known-issues.md`. The issues that stopped data collection on current devices were
fixed afterwards in a separate commit (see [Fixed](#fixed)).

## Fixed

These were found during the refactor and verified on a Pixel 10 (Android 17) on
2026-09-29, before and after each fix. All of them were already present on `main`.

- **Every capture session crashed the app when it ended.** HiddenCam was stopped from a
  `java.util.Timer` thread, and `lifecycle-runtime` 2.3.0 requires the main thread
  (`IllegalStateException: Method markState must be called on the main thread`). This
  killed the process and any task screen the participant was on.
  `CaptureTriggerReceiver` now stops the session with `Handler(Looper.getMainLooper()).postDelayed`.
- **Triggers stopped working after the system restarted the service.** A `START_STICKY`
  restart delivers a null intent, and only `ServiceAction.START` registered the capture
  receivers. Android 8+ doesn't deliver the custom trigger broadcast to the manifest
  receiver. `SensingService.onStartCommand` now treats a null intent like `START`.
- **Collection didn't survive a reboot while accessibility was enabled.**
  `FacePsyAccessibilityService` read `SensingService.kronosClock` before the service had
  created it (`UninitializedPropertyAccessException`). The crash also killed the pending
  `BOOT_COMPLETED` delivery, so `BootReceiver` never ran. The clock is now created in
  `FacePsyApplication.onCreate`. Android may also disable an accessibility service that
  keeps crashing, which is what happened on the test device.
- **Notifications were hidden on Android 13+.** The app never asked for
  `POST_NOTIFICATIONS`. `MainActivity` now requests it. With target SDK 28, Android
  returns that request without showing a prompt, so when notifications are still off the
  app shows a dialog that opens the app's notification settings.
- **A fresh install crashed on first launch.** `MainActivity` logged
  `auth.currentUser.toString()` while no user was signed in (NPE). The log line now uses
  a plain message.

- **16 KB page size warning ("Android App Compatibility" dialog).** TFLite 2.x and ML Kit
  face-detection 16.0.0 shipped 4 KB-aligned native libraries. Now ML Kit 16.1.7 and
  LiteRT 1.4.2 (same `org.tensorflow.lite` API) are used; both libraries are 16 KB-aligned
  on arm64-v8a and x86_64 (checked in the APK). The unused `firebase-ml-model-interpreter`
  was removed. Note: ML Kit 16.1.7 may produce slightly different landmark/contour/
  probability values than 16.0.0, which matters if a study mixes app versions.
- **Old toolchain.** Upgraded to AGP 8.7.3, Gradle 8.9, Kotlin 1.9.25, JDK 17, compile
  SDK 35 (target SDK unchanged at 28). Removed `kotlin-android-extensions` (unused) and
  the broken Spotless setup (it pointed to a missing license file).

## Runtime bugs

1. **Hidden crash button.** `MainActivity` wires the invisible `startBtn` to `crashMe()`,
   which throws a `NullPointerException`. It was used to test crash-restart.
2. **Feature extraction never retries.** In `ImageProcessingWorker.doWork`, the
   `Result.retry()`/`Result.failure()` value in the `catch` block is discarded, so the
   worker always returns `Result.success()`.
3. **Camera opened on every broadcast.** `CaptureTriggerReceiver.isCameraInUse()` calls
   `Camera.open()` for every broadcast it receives and never releases the camera. The
   result is only logged.
4. **Crash restart uses the wrong PendingIntent type.**
   `CrashRestartHandler.restartServiceAndExit` wraps a *Service* intent in
   `PendingIntent.getActivity()`, so the scheduled alarm doesn't restart the service.
   The restart that works is the direct `startForegroundService` call just before it.
5. **Stroop crash before config loads.** `StroopActivity.onClick` reads
   `SensingService.stroopConfig["rounds"]!!`, which throws if `config/stroopTask` hasn't
   been received yet.
6. **Flower 4x4 blocks the UI thread and sends a null extra.** `Flower4x4Activity`
   calls `SystemClock.sleep(2000)` on the UI thread before switching back to 3x3. It
   also passes `flowerData as Parcelable?` as an extra, and that value is always null.
7. **Service exposure and battery.** `SensingService` is declared `exported="true"`, and
   it holds a `PARTIAL_WAKE_LOCK` indefinitely while running.
8. **Model reloaded per face.** `ImageProcessingWorker.computeActionUnits` maps
   `AU_200.tflite` and creates a new `Interpreter` for every detected face.
9. **Unguarded static state.** `CaptureTriggerReceiver.isCapturing` and the
   `SensingService` config maps are mutable statics without synchronization.

## Data / configuration

10. `scripts/config.json` has typos:
   - `"weihcat"` should be WeChat.
   - The Telegram package id is `com.telegram.messenger`; the real id is `org.telegram.messenger`.
   - Some apps are in the wrong category (Taobao is filed under "communication").

   The file was left unchanged because editing it changes the trigger data.
11. Firestore field names mix `snake_case` and `camelCase` (`user_id`, `game_id` vs
   `gameId`, `stimulusShownAt`). They can't be renamed without breaking existing data
   and analysis scripts.

## Build / toolchain

12. **`main` no longer builds.** Its buildscript depends on `com.novoda:bintray-release`,
    which disappeared with Bintray. The refactor branch removes the dependency, along
    with `bintrayconfig.gradle` and the `dl.bintray.com` repository.
13. **Target SDK is still 28.** The toolchain was upgraded (see Fixed), but raising the
    target changes runtime behavior (foreground-service types, scoped storage,
    notification permission, background limits) and needs its own migration and testing.
    Other old dependencies remain: CameraX `1.0.0-alpha06` (vendored HiddenCam),
    `jcenter()`, two Firebase BoMs (25.4.1 and 25.12.0), coroutines 1.1.1.

## Removed in the refactor (no runtime effect)

- The dlib/OpenCV landmark demo from the original prototype was removed. It included
  `CameraActivity`, which wasn't registered in the manifest, along with the `camera/`
  package, `Native.java`, `native-lib.cpp`, `CMakeLists.txt` and the dlib model
  downloader (`utils/Model`, `Downloader`, `Extractor`, `UserDialog`, `RectUtils`).
  The vendored `cppLibs/` (dlib + OpenCV, ~185 MB, including committed `*.o`/CMake
  build artifacts) went with it. None of this code ran in the app, apart from a
  `System.loadLibrary("native-lib")` call whose library was never used. The debug APK
  went from ~120 MB to ~66 MB.
- The unused ML Kit sample overlay (`FaceGraphic`, `GraphicOverlay`), a stub
  `UploadWorker`, and a sample `FirestoreWorker` were removed.
