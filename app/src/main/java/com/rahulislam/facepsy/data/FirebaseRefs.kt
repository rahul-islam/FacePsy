package com.rahulislam.facepsy.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

/**
 * Names of every Firebase location the app reads from or writes to.
 *
 * These strings are the data contract with the study backend: analysis scripts,
 * Firestore security rules and `scripts/configure_firebase.py` depend on them.
 * **Do not change their values.** See `docs/data-schema.md` for the fields stored
 * under each location.
 */
object FirebaseRefs {

    /** Shared Firestore client (the same singleton `Firebase.firestore` returns). */
    val firestore: FirebaseFirestore
        get() = Firebase.firestore

    /** Top-level Cloud Firestore collections. */
    object Collections {
        /** Study configuration, edited by researchers. See [ConfigDocs]. */
        const val CONFIG = "config"

        /** One document per participant uid; holds the `online` presence flag. */
        const val USERS = "users"

        /** Screen on/off/unlock events and foreground-app changes. */
        const val PHONE_USAGE_DATA = "phoneUsageData"

        /** One document per face detected in a captured frame. */
        const val FEATURES = "features"

        /** One document per Flower (visual-spatial memory) game round. */
        const val FLOWER_GAME_DATA = "flowerGameData"

        /** One document per Stroop task response. */
        const val STROOP_DATA = "stroopData"

        /** One document per uploaded capture-session audio file. */
        const val AUDIO_RECORDINGS = "audioRecordings"
    }

    /** Documents inside the [Collections.CONFIG] collection. */
    object ConfigDocs {
        /** `apps`: array of `{packageName, enable, ...}` whose launch triggers a capture. */
        const val TRIGGERS = "triggers"

        /** Capture duration in ms per trigger, keyed by [TriggerContract.DurationKeys]. */
        const val TRIGGER_DURATION = "triggerDuration"

        /** `rounds`: number of Stroop stimuli per session. */
        const val STROOP_TASK = "stroopTask"

        /** `preLink` / `postLink`: survey URLs opened from the home screen. */
        const val SURVEY = "survey"
    }

    /** Paths in the Firebase Realtime Database (used for presence/status only). */
    object RealtimeDb {
        /** Special path that reports whether the client is connected. */
        const val INFO_CONNECTED = ".info/connected"

        /** Service status for a participant: `online`, `offline` or `destroyed`. */
        fun status(uid: String) = "/status/$uid"

        /** `true` while the camera is capturing for a participant. */
        fun captureStatus(uid: String) = "/captureStatus/$uid"
    }

    /** Paths in Cloud Storage. */
    object Storage {
        /** Cropped eye-region PNG for one captured frame; [side] is `LEFT` or `RIGHT`. */
        fun eyeRegion(uid: String, imageBaseName: String, side: String) =
            "eyeRegion/$uid/${imageBaseName}_$side.png"

        /**
         * All eye-region crops of one video session, as PNG entries
         * `{session}_f{frame:05}_{face}_{LEFT|RIGHT}.png`.
         */
        fun eyeRegionZip(uid: String, sessionName: String) = "eyeRegion/$uid/$sessionName.zip"

        /** Audio track (AAC in MP4) of one capture session. */
        fun audio(uid: String, sessionName: String) = "audio/$uid/$sessionName.m4a"
    }
}
