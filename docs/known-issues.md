# Known issues

The structure/naming refactor (branch `refactor/structure-and-docs`) deliberately did
**not** change runtime behavior. The issues below were found during that work and are
left as-is for a follow-up. In the code they are marked `NOTE: legacy behavior, see
docs/known-issues.md`.

## Runtime bugs

1. **Every capture session crashes the app when it ends** (verified on a Pixel running
   Android 17, 2026-09-29). `CaptureTriggerReceiver.startCaptureSession` stops HiddenCam
   from a `java.util.Timer` thread ("SettingUp"). `HiddenCam.stop()` calls
   `LifecycleRegistry.markState`, and the resolved `androidx.lifecycle:lifecycle-runtime`
   2.3.0 enforces the main thread, so it throws `IllegalStateException: Method markState
   must be called on the main thread` and kills the process, including any task screen the
   participant is using. Frames captured before the crash are still processed. The code and
   the resolved dependency are identical on `main`. Fix: post `hiddenCam.stop()` to the
   main looper (e.g. `Handler(Looper.getMainLooper()).postDelayed { ... }`).
2. **Triggers stop working after the service is restarted by the system**
   (verified on device, 2026-09-29). `SensingService` is `START_STICKY`, so after the
   process dies (e.g. issue 1) Android restarts it with a **null** intent.
   `onStartCommand` only calls `startService()` for `ServiceAction.START`, so the runtime
   `CaptureTriggerReceiver` / `ScreenEventLogReceiver` registrations, wake lock and
   presence tracking are not set up again. The manifest-declared `CaptureTriggerReceiver`
   does not help: Android 8+ skips implicit custom broadcasts to manifest receivers
   ("Background execution not allowed"). Captures resume only when the participant opens
   the app (which sends `START`). Fix: treat a null intent like `START`.
3. **Data collection does not survive a reboot while accessibility is enabled**
   (verified on device, 2026-09-29). After boot, Android binds
   `FacePsyAccessibilityService` before `SensingService` is running. The first
   foreground-app event reads `SensingService.kronosClock`, which is only initialized in
   `SensingService.onCreate`, and throws `UninitializedPropertyAccessException`. The crash
   kills the process while `BOOT_COMPLETED` is pending, so `BootReceiver` never runs
   (broadcast history: `FAILURE ... reason: onApplicationCleanupLocked`). The process then
   crashes on every app switch until the participant opens FacePsy. Same code on `main`
   (`EndlessService.kronosClock`). Fix: initialize the clock in an `Application` class (or
   lazily) and/or skip logging until the service is running.
4. **Notification permission is never requested** (Android 13+). The app predates
   `POST_NOTIFICATIONS`; with target SDK 28 Android only auto-prompts when a channel is
   created while an activity is in the foreground, which `SensingService` does not do.
   Without it the "Data collection is active" foreground notification, the accessibility
   reminder and FCM notifications are all hidden (the service still runs). Workaround:
   allow notifications for FacePsy in system settings.
5. **Fresh install crashes on first launch** (verified on a Pixel running Android 17, 2026-09-29).
   `MainActivity.signInOrStartService` calls `Log.i(TAG, auth.currentUser.toString())`
   right after launching FirebaseUI sign-in. With no signed-in user this throws a
   `NullPointerException`. `CrashRestartHandler` then starts `SensingService` and exits
   the process, and the FirebaseUI sign-in screen still appears. The bug was already on
   `main` (original `MainActivity.kt:358`).
6. **Hidden crash button.** `MainActivity` wires the invisible `startBtn` to `crashMe()`,
   which throws a `NullPointerException`. It was used to test crash-restart.
7. **Feature extraction never retries.** In `ImageProcessingWorker.doWork`, the
   `Result.retry()`/`Result.failure()` value in the `catch` block is discarded, so the
   worker always returns `Result.success()`.
8. **Camera opened on every broadcast.** `CaptureTriggerReceiver.isCameraInUse()` calls
   `Camera.open()` for every broadcast it receives and never releases the camera. The
   result is only logged.
9. **Crash restart uses the wrong PendingIntent type.**
   `CrashRestartHandler.restartServiceAndExit` wraps a *Service* intent in
   `PendingIntent.getActivity()`, so the scheduled alarm doesn't restart the service.
   The restart that works is the direct `startForegroundService` call just before it.
10. **Stroop crash before config loads.** `StroopActivity.onClick` reads
   `SensingService.stroopConfig["rounds"]!!`, which throws if `config/stroopTask` hasn't
   been received yet.
11. **Flower 4x4 blocks the UI thread and sends a null extra.** `Flower4x4Activity`
   calls `SystemClock.sleep(2000)` on the UI thread before switching back to 3x3. It
   also passes `flowerData as Parcelable?` as an extra, and that value is always null.
12. **Service exposure and battery.** `SensingService` is declared `exported="true"`, and
   it holds a `PARTIAL_WAKE_LOCK` indefinitely while running.
13. **Model reloaded per face.** `ImageProcessingWorker.computeActionUnits` maps
   `AU_200.tflite` and creates a new `Interpreter` for every detected face.
14. **Unguarded static state.** `CaptureTriggerReceiver.isCapturing` and the
   `SensingService` config maps are mutable statics without synchronization.

## Data / configuration

15. `scripts/config.json` has typos:
   - `"weihcat"` should be WeChat.
   - The Telegram package id is `com.telegram.messenger`; the real id is `org.telegram.messenger`.
   - Some apps are in the wrong category (Taobao is filed under "communication").

   The file was left unchanged because editing it changes the trigger data.
16. Firestore field names mix `snake_case` and `camelCase` (`user_id`, `game_id` vs
   `gameId`, `stimulusShownAt`). They can't be renamed without breaking existing data
   and analysis scripts.

## Build / toolchain

17. **`main` no longer builds.** Its buildscript depends on `com.novoda:bintray-release`,
    which disappeared with Bintray. The refactor branch removes the dependency, along
    with `bintrayconfig.gradle` and the `dl.bintray.com` repository.
18. **Old toolchain:** AGP 3.4.3, Gradle 5.1.1, compile/target SDK 28, Kotlin 1.3.70,
    CameraX `1.0.0-alpha06`, `jcenter()`, `kotlin-android-extensions`, two Firebase
    BoMs (25.4.1 and 25.12.0), and the deprecated `firebase-ml-model-interpreter`.
    Building needs JDK 8–11. Upgrading changes build output and is out of scope for the
    refactor.
19. **16 KB page size.** `libtensorflowlite_jni.so` (TFLite 2.3.0) and
    `libface_detector_v2_jni.so` (ML Kit face-detection 16.0.0) aren't 16 KB aligned,
    so Android 15+ shows a compatibility warning for debuggable builds. Fixing this
    means upgrading those libraries.
20. **Spotless** is only applied to `library/` and points to a missing
    `spotless.license.kt`.

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
