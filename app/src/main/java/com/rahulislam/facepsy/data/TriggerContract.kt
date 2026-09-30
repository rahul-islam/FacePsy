package com.rahulislam.facepsy.data

/**
 * Contract of the broadcast that asks
 * [com.rahulislam.facepsy.receiver.CaptureTriggerReceiver] to start a camera
 * capture session.
 *
 * Sent by [com.rahulislam.facepsy.FacePsyAccessibilityService] (tracked app opened)
 * and by the cognitive task activities. Phone unlock uses the system
 * `ACTION_USER_PRESENT` broadcast instead.
 *
 * The action and extra names are also declared in `AndroidManifest.xml`;
 * **do not change their values.**
 */
object TriggerContract {

    const val ACTION_TRIGGER = "com.rahulislam.facepsy.triggers"

    /** Name of the trigger (a package name, or one of the `TRIGGER_*` names below). */
    const val EXTRA_PACKAGE_NAME = "packageName"

    /** Capture duration in milliseconds, sent as a String. */
    const val EXTRA_DURATION = "duration"

    /** Session id stored with every feature document (a game UUID or [GAME_ID_APP_USAGE]). */
    const val EXTRA_GAME_ID = "gameId"

    /** Trigger name and game id used for phone-unlock captures. */
    const val TRIGGER_PHONE_UNLOCK = "phoneUnlock"
    const val TRIGGER_FLOWER_GAME = "flowerGame"
    const val TRIGGER_STROOP_TASK = "stroopTask"

    /** Game id used for captures triggered by opening a tracked app. */
    const val GAME_ID_APP_USAGE = "appUsage"

    /** Keys of the `config/triggerDuration` document. */
    object DurationKeys {
        const val UNLOCK_EVENT = "unlockEvent"
        const val APP = "app"
        const val FLOWER_GAME = "flowerGame"
        const val STROOP_TASK = "stroopTask"
    }
}
