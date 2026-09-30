# Data schema

This page lists everything FacePsy reads from or writes to Firebase. The names are
defined in `app/src/main/java/com/rahulislam/facepsy/data/FirebaseRefs.kt` and
`data/TriggerContract.kt`. **They are a data contract; do not rename them.**

Timestamps are milliseconds since the Unix epoch. Unless noted otherwise, a `timestamp`
comes from the NTP-synced `SensingService.kronosClock`. Fields named `*At` / `*Time`
come from the device clock (`System.currentTimeMillis()`).

## Cloud Firestore

### `config/*` (read, written by researchers)

`SensingService` listens to these documents live. You can seed them with
`scripts/configure_firebase.py`; each top-level key of `scripts/config.json` becomes
one document.

| Document | Key | Type | Used for |
|---|---|---|---|
| `triggers` | `apps` | array of `{packageName: string, enable: bool, appName: string, category: string}` | Opening an app whose `packageName` is enabled starts a capture. The app reads only `packageName` and `enable`. |
| `triggerDuration` | `unlockEvent`, `app`, `flowerGame`, `stroopTask` | number (ms) | Capture length per trigger type. |
| `stroopTask` | `rounds` | number | Stroop responses per session. |
| `survey` | `preLink`, `postLink` | string (URL) | Opened from the home screen with `?userId={uid}` appended. |

### `features` (one document per detected face per captured frame)

Written by `processing/ImageProcessingWorker`. The per-face JSON is converted to a map
with Gson, so JSON numbers are stored as doubles.

| Field | Type | Description |
|---|---|---|
| `user_id` | string | Firebase Auth uid; left out if not signed in. |
| `timestamp` | string | Kronos time (ms) when the frame was queued. |
| `gameId` | string | Session id: a Flower/Stroop game UUID, `appUsage`, or `phoneUnlock`. |
| `fileName` | string | Absolute path of the frame on the device, `DCIM/HiddenCam/{yyyy-MM-dd-HH-mm-ss-SSS}{triggerName}.jpg`. The file is deleted after processing. |
| `boundingBox` | string | `Rect.flattenToString()`: `"left top right bottom"` in pixels. |
| `landmarks` | array of `{type: int, x: float, y: float}` | ML Kit face landmarks. |
| `contours` | array of `{x: float, y: float}` | Every point of every ML Kit face contour, flattened in ML Kit's contour order. |
| `headEulerAngle` | `{X, Y, Z}` float | Head pitch, yaw (rotation to the right) and roll (sideways tilt), in degrees. |
| `classification` | `{leftEyeOpenProbability, rightEyeOpenProbability, smilingProbability}` float or null | ML Kit classification. |
| `au` | `{AU01, AU02, AU04, AU06, AU07, AU10, AU12, AU14, AU15, AU17, AU23, AU24}` float | Action-unit intensities from `assets/AU_200.tflite`. Empty if the face box falls outside the image or the model failed to load. |
| `metadata.timestamp` | string | Same as `timestamp`. |
| `metadata.seq_id` | string | Kronos time (ms) when the capture session started; groups frames of one session. |
| `metadata.gameId` | string | Same as `gameId`. |
| `metadata.triggerName` | string | Package name of the triggering app, or `phoneUnlock` / `flowerGame` / `stroopTask`. |
| `metadata.appVersion` | string | App `versionName`. |

### `phoneUsageData`

| Field | Type | Description |
|---|---|---|
| `user_id` | string or null | Firebase Auth uid. |
| `action` | string | An intent action (`android.intent.action.SCREEN_ON`, `SCREEN_OFF`, `USER_PRESENT`), written by `receiver/ScreenEventLogReceiver`; **or** the package name of the app that came to the foreground, written by `FacePsyAccessibilityService`. |
| `timestamp` | number | Kronos time (ms). |

### `flowerGameData` (one document per Flower game round)

Written by `tasks/flower/FlowerGameCommon.kt#saveFlowerGameData`, which is called
from `Flower3x3Activity` and `Flower4x4Activity`.

| Field | Type | Description |
|---|---|---|
| `user_id` | string or null | Firebase Auth uid. |
| `game_id` | string | Session UUID, shared across the 3x3 and 4x4 grids. |
| `complexity` | int | Sequence length (number of glows), 3–7. |
| `num_span` | int | Grid width: `3` or `4`. |
| `status` | bool | `true` if the whole sequence was reproduced. |
| `target_seq` | int[] | Button indices that lit up, in order. |
| `tapped_seq` | int[] | Button indices tapped; `-1` = not tapped. |
| `time_diff` | long[] | ms between consecutive taps; the first entry is measured from the end of the glow sequence. |
| `tapSeqTime` | long[] | Device time of each tap; `-1` = not tapped. |
| `startGlowTime` / `endGlowTime` | long | Device time when the glow sequence started and ended. |
| `timestamp` | long | Kronos time (ms) when the document was written. |

### `stroopData` (one document per Stroop response)

Written by `tasks/stroop/StroopActivity`.

| Field | Type | Description |
|---|---|---|
| `user_id` | string or null | Firebase Auth uid. |
| `game_id` | string | Session UUID. |
| `stroopWord` | string | Word shown: `RED`, `GREEN`, `BLUE` or `YELLOW`. |
| `stroopColor` | string | Ink color of the word, from the same set. |
| `stroopResponse` | string | Button tapped, from the same set. |
| `stimulusShownAt` / `stimulusRespondedAt` | long | Device time. |
| `seq` | int | Response number in the session, starting at 1. |
| `timestamp` | long | Kronos time (ms). |

### `users/{uid}`

`SensingService.trackPresence` merges `{online: true}` into this document on every
Realtime Database (re)connection.

## Realtime Database

| Path | Value | Writer |
|---|---|---|
| `/status/{uid}` | `"online"`, `"offline"` (set on disconnect), or `"destroyed"` (service destroyed) | `service/SensingService` |
| `/captureStatus/{uid}` | bool, `true` while a capture session is running | `receiver/CaptureTriggerReceiver` |
| `.info/connected` | read only | `service/SensingService` |

## Cloud Storage

| Path | Content | Writer |
|---|---|---|
| `eyeRegion/{uid}/{imageBaseName}_LEFT.png`, `..._RIGHT.png` | Full-color crop of each eye (contour box + 20 px margin), one per face per frame. `imageBaseName` is the frame file name without its extension. If no user is signed in, `uid` is the string `null`. | `processing/ImageProcessingWorker` |

## Local contracts (on the device)

| Name | Value | Where |
|---|---|---|
| Broadcast action | `com.rahulislam.facepsy.triggers`, with String extras `packageName`, `duration` (ms), `gameId` | `data/TriggerContract`, manifest |
| WorkManager input keys | `IMAGE_URI`, `TIMESTAMP`, `SEQ_ID`, `GAME_ID`, `TRIGGER_NAME`; tag `feature-extraction` | `ImageProcessingWorker` companion |
| SharedPreferences | file `SPYSERVICE_KEY`, key `SPYSERVICE_STATE` = `STARTED` or `STOPPED` | `service/ServiceStateStore.kt` |
| Frame folder | `DCIM/HiddenCam` | `CaptureTriggerReceiver.CAPTURE_FOLDER_NAME` |
| Notification channel | `ENDLESS SERVICE CHANNEL` (ids 1 = foreground, 42 = enable accessibility) | `service/SensingService` |
