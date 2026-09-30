# Architecture

FacePsy is a single Android app (`:app`) plus a vendored camera library (`:library`,
a copy of [CottaCush/HiddenCam](https://github.com/CottaCush/HiddenCam)). Every
record goes to Firebase. See [data-schema.md](data-schema.md) for the stored fields.

## Data flow

```mermaid
flowchart LR
    subgraph Triggers
        U[Phone unlock<br/>ACTION_USER_PRESENT]
        A[FacePsyAccessibilityService<br/>tracked app opened]
        T[Flower / Stroop task started]
    end

    U --> R
    A -- "com.rahulislam.facepsy.triggers" --> R
    T -- "com.rahulislam.facepsy.triggers" --> R

    R[CaptureTriggerReceiver] -->|"HiddenCam: front camera,<br/>1 frame / 100 ms for N ms"| F[(DCIM/HiddenCam/*.jpg)]
    R -->|"one job per frame"| W[ImageProcessingWorker<br/>WorkManager]
    F --> W
    W -->|"ML Kit face detection"| W
    W -->|"AU_200.tflite<br/>12 action units"| W
    W -->|"eye crops"| S[(Cloud Storage<br/>eyeRegion/)]
    W -->|"one doc per face"| FS[(Firestore<br/>features)]

    A -->|"foreground app"| PU[(Firestore<br/>phoneUsageData)]
    SE[ScreenEventLogReceiver<br/>screen on/off/unlock] --> PU
    T -->|"responses"| G[(Firestore<br/>flowerGameData / stroopData)]
```

1. **Trigger.** A capture session starts when:
   - the phone is unlocked, for `config/triggerDuration.unlockEvent` ms;
   - an app listed in `config/triggers.apps` comes to the foreground, for `.app` ms;
   - a cognitive task starts, for `.flowerGame` / `.stroopTask` ms.

   Only one session runs at a time (`CaptureTriggerReceiver.isCapturing`).
2. **Capture.** `HiddenCam` (CameraX, no preview) writes a 1080x1920 front-camera
   frame every 100 ms to `DCIM/HiddenCam`. For each frame,
   `CaptureTriggerReceiver.onImageCaptured` enqueues an `ImageProcessingWorker` with the
   file path, a Kronos timestamp, the session `seq_id`, `gameId` and the trigger name.
3. **Extract.** `ImageProcessingWorker` runs ML Kit face detection (landmarks, contours,
   classification, head pose). For each face it then:
   - builds a 200x200 grayscale face crop and runs `assets/AU_200.tflite` to estimate
     12 FACS action units;
   - crops both eyes from the color frame.
4. **Upload.** The eye crops go to Cloud Storage, and one `features` document per face
   goes to Firestore. The local frame is then deleted.

## Service lifecycle

```mermaid
sequenceDiagram
    participant M as MainActivity
    participant S as SensingService
    participant B as BootReceiver
    participant C as CrashRestartHandler
    M->>M: request camera/storage permissions
    M->>M: FirebaseUI sign-in (if needed)
    M->>S: startForegroundService(START)
    S->>S: foreground notification, wake lock
    S->>S: listen to config/* (triggers, triggerDuration, stroopTask, survey)
    S->>S: presence (/status/{uid}, users/{uid}.online)
    S->>S: register CaptureTriggerReceiver + ScreenEventLogReceiver
    S->>S: persist ServiceState.STARTED
    Note over B: after reboot
    B->>S: START (if state was STARTED)
    Note over C: uncaught exception or onDestroy
    C->>S: startForegroundService(START), then exit process
```

- `SensingService` returns `START_STICKY` and stores its state with `ServiceStateStore`,
  so `BootReceiver` can bring it back after a reboot.
- `CrashRestartHandler` is installed as the default uncaught-exception handler by
  `MainActivity` and `SensingService`. On a crash, and whenever the service is
  destroyed, it restarts the service and exits the process with code 2.
- `SensingService` exposes the live config through companion fields
  (`appTrackingList`, `triggerDuration`, `stroopConfig`, `surveyConfig`) and the shared
  `kronosClock`. These are read by the receivers, the accessibility service and the
  task activities.
- `FacePsyMessagingService` shows FCM push notifications, such as survey reminders,
  and opens `MainActivity` when tapped.

## Package map

```
com.rahulislam.facepsy
├── MainActivity                  launcher: permissions, sign-in, starts service, home buttons
├── FacePsyAccessibilityService   foreground-app logging + app-open capture trigger
├── data/
│   ├── FirebaseRefs              Firestore/RTDB/Storage names (data contract)
│   └── TriggerContract           capture-trigger broadcast action, extras, duration keys
├── service/
│   ├── SensingService            foreground service: config, presence, receivers, clock
│   ├── ServiceAction             START / STOP intent actions
│   ├── ServiceStateStore         persisted STARTED / STOPPED state
│   └── CrashRestartHandler       uncaught-exception handler that restarts the service
├── receiver/
│   ├── CaptureTriggerReceiver    runs a HiddenCam capture session, queues extraction
│   ├── ScreenEventLogReceiver    logs screen on/off/unlock
│   └── BootReceiver              restarts the service after boot
├── processing/
│   └── ImageProcessingWorker     ML Kit + TFLite feature extraction and upload
├── messaging/
│   └── FacePsyMessagingService   FCM notifications
├── tasks/
│   ├── flower/                   visual-spatial memory game (3x3 and 4x4 grids)
│   └── stroop/                   Stroop color-word task
├── ui/
│   └── InstructionActivity       participant instructions
└── util/                         permission helpers, service logging
```
