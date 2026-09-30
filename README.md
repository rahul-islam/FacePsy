<img width="1750" alt="FacePsy" src="./imgs/header.png">

FacePsy is designed to capture real-time facial behavior primitives as users interact with their mobile devices.

> This is the official codebase of the affective mobile sensing system paper [FacePsy: An Open-Source Affective Mobile Sensing System - Analyzing Facial Behavior and Head Gesture for Depression Detection in Naturalistic Settings](https://dl.acm.org/doi/10.1145/3676505), accepted by [ACM International Conference on Mobile Human-Computer Interaction (MobileHCI 2024)](https://mobilehci.acm.org/2024/).

# News 📰
> Our work on mood detection using pupillary responses was accepted at [IEEE-EMBS BSN 2024](https://bsn.embs.org/2024/), titled [MoodPupilar: Predicting Mood Through Smartphone Detected Pupillary Responses in Naturalistic Settings](https://arxiv.org/abs/2408.01855).

## Depression Study In-the-Wild
<img width="1750" alt="FacePsy Study" src="./imgs/study.png">

## Introduction
<img width="1750" alt="FacePsy System" src="./imgs/system.png">

## Functionalities:
* Real-time facial behavior primitives (e.g. AU, head pose, facial expressions) tracking
* App usage tracking (e.g. screen on/off, app open/close)
* Cognitive assessment (e.g. Stroop, Visual Spatial Memory, etc.)
* Custom EMA delivery
* Data collection can be done in the background
* Use triggers such as screen on/off, app open/close, etc. to start/stop data collection
* Realtime feature extraction of facial behavior primitives, and stores them remote database
* Reboot app on device restart, app crash, etc, and continue data collection

## Architecture
When a **trigger** fires, the app records the front camera in the background for a configured duration. Triggers are:
- phone unlock;
- opening an app from the study's app list (detected by an accessibility service);
- starting a cognitive task.

Each frame then goes through the following steps:
1. It is queued to a WorkManager job.
2. The job runs **ML Kit** face detection (landmarks, contours, head pose, eye/smile probabilities) and a **TensorFlow Lite** model (`AU_200.tflite`) that estimates 12 facial action units.
3. It uploads one Firestore document per face, plus eye-region crops to Cloud Storage.
4. It deletes the frame.

A foreground service keeps collection running. It mirrors the study config from Firestore, reports presence, and is restarted after reboots and crashes.

See [docs/architecture.md](docs/architecture.md) for diagrams and details.

## Project structure
```
app/src/main/java/com/rahulislam/facepsy/
├── FacePsyApplication.kt           # creates the shared NTP clock at process start
├── MainActivity.kt                 # launcher: permissions, sign-in, home screen
├── FacePsyAccessibilityService.kt  # foreground-app logging + app-open trigger
├── data/        # Firebase paths (FirebaseRefs) and trigger broadcast contract
├── service/     # SensingService (foreground service), state store, crash restart
├── receiver/    # capture trigger, screen-event logging, boot
├── processing/  # ImageProcessingWorker: ML Kit + TFLite feature extraction
├── messaging/   # FCM notifications
├── tasks/       # Flower (visual-spatial memory) and Stroop cognitive tasks
├── setup/       # setup checklist steps and monitoring (SetupStep, SetupMonitor)
├── ui/          # setup checklist and instructions screens
└── util/
app/src/main/assets/AU_200.tflite   # action-unit model
library/                            # HiddenCam background camera library (Apache-2.0)
scripts/                            # Firestore config seeding script
docs/                               # architecture, data schema, known issues
c4model/                            # C4 / Structurizr model of the whole study system
```

## Requirements
1. **JDK 8.** The project uses Android Gradle Plugin 3.4.3 / Gradle 5.1.1, which don't run on newer JDKs (including Android Studio's bundled JDK). One option is [Amazon Corretto 8](https://docs.aws.amazon.com/corretto/latest/corretto-8-ug/downloads-list.html). A portable tarball is enough; point `JAVA_HOME` at it (in Android Studio: *Settings → Build Tools → Gradle → Gradle JDK*).
2. **Android SDK.** Install it with Android Studio or the command-line tools. Gradle downloads platform 28 and build-tools 28.0.3 automatically.
3. A Firebase project (see below).

No NDK, CMake or OpenCV is required.

## Firebase Setup
1. Open [Firebase Console](https://console.firebase.google.com/) and create a new project.
2. Register the Android app (`com.rahulislam.facepsy`) with your Firebase project.
3. Download the `google-services.json` file and copy it to the `app/` directory (it is gitignored).
4. In your Firebase project, open `Authentication` and enable `Email/Password`.
5. In your Firebase project, open `Cloud Firestore` and click `Create Database`.
6. Enable `Realtime Database` (used for online/capture status) and `Storage` (used for eye-region crops).

### Firestore Rules

Replace the default rules with the following config, and modify it as needed.

```
rules_version = '2';

service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} {
      allow read, write: if request.auth != null;
    }
  }
}
```

### Firestore Data (study configuration)

The app reads its study configuration from the `config` collection and picks up changes live:

- Document `triggers`
  - `apps`: array of maps, one per app whose launch starts a capture, e.g.
    ```json
    { "appName": "WhatsApp", "category": "communication", "packageName": "com.whatsapp", "enable": true }
    ```
- Document `triggerDuration`: capture length in **milliseconds**
  - `unlockEvent`: 10000
  - `app`: 10000
  - `flowerGame`: 90000
  - `stroopTask`: 60000
- Document `stroopTask`
  - `rounds`: 30
- Document `survey`
  - `preLink`: https://example.com/
  - `postLink`: https://example.com/

The app opens survey links with `?userId={firebase uid}` appended.

### Seeding the configuration
[`scripts/configure_firebase.py`](scripts/README.md) uploads [`scripts/config.json`](scripts/config.json) (the values above plus a list of common apps) to the `config` collection:

```bash
cd scripts
pip install -r requirements.txt
python configure_firebase.py --cred ./cred/<service-account-key>.json --config config.json
```

## Installation
1. Clone the repository.
2. Add `app/google-services.json` (see Firebase Setup).
3. Create `local.properties` containing `sdk.dir=/path/to/Android/sdk`. Android Studio creates it automatically.
4. Build with JDK 8:
   ```bash
   export JAVA_HOME=/path/to/jdk8
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   You can also open the project in Android Studio with the Gradle JDK set to JDK 8 and run it.

## Usage
1. Open the app.
2. Create an account, or log in if you already have one.
3. Grant the permissions the app asks for (see below).
4. The app starts its background service and collects data whenever a trigger fires.

### Participant permissions
After sign-in, FacePsy opens a **Set up FacePsy** checklist. It explains each item, shows whether it is on, and opens the right system screen:
- **Camera and storage** (required): frames are written to `DCIM/HiddenCam` briefly before they are processed and deleted. If the participant chose "Don't allow" twice, the button opens App info instead.
- **Accessibility service** (required): needed for app-open triggers and foreground-app logging. On Android 13+, if FacePsy was installed from a downloaded APK, the switch is greyed out until the participant opens *App info → ⋮ → Allow restricted settings*.
- **Notifications**, **unrestricted battery use** and **keep permissions if unused** (recommended).

Afterwards, FacePsy checks every minute and on each unlock. If something required is revoked or switched off later, it shows a "FacePsy needs your attention" notification that opens the checklist, and the checklist opens again when the app is opened. A **force stop** from Settings stops everything and switches the accessibility service off; opening FacePsy once brings collection back and asks for accessibility again.

## Data collected
Records go to Firestore collections:
- `features`: facial features per detected face;
- `phoneUsageData`: screen events and foreground apps;
- `flowerGameData` and `stroopData`: cognitive-task responses;
- `users`: presence.

Eye-region crops go to Storage under `eyeRegion/`. Status flags go to Realtime Database. The field-by-field schema is in [docs/data-schema.md](docs/data-schema.md).

## Study Admin/Researcher/Developer
The study admin, researcher or developer can access the collected data by logging into the [Firebase console](https://console.firebase.google.com/).

The following parameters can be configured in Firestore (`config` collection):
1. Customizable triggers
2. EMA (survey) links
3. Data collection length for each trigger type
4. Number of Stroop rounds

## Troubleshooting
- **`Unsupported class file major version` / Gradle fails to start.** Gradle is running on a JDK newer than 11; set `JAVA_HOME` (or the Android Studio Gradle JDK) to JDK 8.
- **`File google-services.json is missing`.** Copy your Firebase config to `app/google-services.json`.
- **Many `unexpected element (uri:"", local:"base-extension")` warnings.** These are harmless: the old Android Gradle Plugin can't parse metadata from newer SDK packages.
- **"This app isn't 16 KB compatible" dialog on Android 15+.** This is expected for debug builds. The bundled TFLite and ML Kit native libraries predate 16 KB page sizes (see [docs/known-issues.md](docs/known-issues.md)).
- **`PERMISSION_DENIED` in logcat before signing in.** This is expected with the rules above; the config listeners need an authenticated user.

## Contributing
Read [CLAUDE.md](CLAUDE.md) before changing code. It lists the build setup and the **invariants** that must not change: pinned class names, Firebase paths and field names, broadcast/WorkManager keys, and names looked up at runtime.

Known bugs and technical debt are tracked in [docs/known-issues.md](docs/known-issues.md). Please keep behavior changes and refactors in separate commits.

## Credit
Thanks to [CottaCush/HiddenCam](https://github.com/CottaCush/HiddenCam), which is vendored in `library/` under the Apache License 2.0 (see [NOTICE](NOTICE)).

## Citation
If you find this repository useful, please consider giving a star :star: and citation using the given BibTeX entry:
```
@article{10.1145/3676505,
author = {Islam, Rahul and Bae, Sang Won},
title = {FacePsy: An Open-Source Affective Mobile Sensing System - Analyzing Facial Behavior and Head Gesture for Depression Detection in Naturalistic Settings},
year = {2024},
issue_date = {September 2024},
publisher = {Association for Computing Machinery},
address = {New York, NY, USA},
volume = {8},
number = {MHCI},
url = {https://doi.org/10.1145/3676505},
doi = {10.1145/3676505},
month = sep,
articleno = {260},
numpages = {32},
keywords = {affective computing, application instrumentation, depression, empirical study that tells us about people, field study, machine learning, mobile computing, system}
}
```

## License

This project is licensed under the MIT License (see [LICENSE](LICENSE)). Third-party notices are in [NOTICE](NOTICE).

## Contact

If you have any questions or suggestions, please feel free to contact [Prof. Sang Won Bae](mailto:sbae4@stevens.edu) or [Rahul](mailto:rahul.islam3@gmail.com).

