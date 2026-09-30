## Context

FacePsy is an opportunistic affective sensing system. A study **participant** installs
the FacePsy Android app, signs in, and grants camera and accessibility permissions.
From then on, the app briefly records the front camera in the background whenever a
trigger fires:
- the phone is unlocked;
- a configured app (e.g. a messaging app) is opened;
- the participant plays one of the in-app cognitive tasks (Stroop, Flower memory game).

The app extracts facial behavior primitives on the device (ML Kit landmarks, contours,
head pose, eye/smile probabilities, and 12 facial action units from a TensorFlow Lite
model). It uploads these features, plus cropped eye regions, to **Google Firebase**.
Raw frames are deleted after processing. The app also logs screen and foreground-app
events, and it opens pre/post **surveys** (e.g. Qualtrics) from links configured in
Firestore.

A **researcher** configures the study in Firestore (which apps trigger capture, how
long each capture lasts, Stroop rounds, survey links) and pushes notifications through
Firebase Cloud Messaging. Collected data is analyzed downstream, for example after a
Firebase → BigQuery sync and in a compliance dashboard. Those analysis components are
outside this repository.

![](embed:SystemContext)
