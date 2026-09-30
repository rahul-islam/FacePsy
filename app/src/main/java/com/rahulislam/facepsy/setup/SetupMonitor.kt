package com.rahulislam.facepsy.setup

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.rahulislam.facepsy.R
import com.rahulislam.facepsy.data.FirebaseRefs
import com.rahulislam.facepsy.data.FirebaseRefs.Collections
import com.rahulislam.facepsy.service.SensingService
import com.rahulislam.facepsy.ui.SetupActivity

/**
 * Watches the participant setup ([SetupStep]) after onboarding, so permissions that are
 * revoked or switched off later are noticed and re-requested.
 *
 * [check] runs every minute from [SensingService], on every unlock and whenever the home
 * screen resumes. It never changes a setting itself; it only tells the participant:
 * while a required step is missing, a single notification lists it and opens [SetupActivity].
 * Each change is written to Firestore `phoneUsageData` as `SETUP_<step>_OK` /
 * `SETUP_<step>_MISSING`, so gaps in the collected data can be explained.
 */
object SetupMonitor {

    private const val TAG = "SetupMonitor"

    // Reuses the id of the original accessibility reminder, which this replaces.
    private const val REMINDER_NOTIFICATION_ID = 42

    private const val PREFS = "facepsy_setup"
    private const val KEY_ONBOARDED = "onboarding_completed"
    private const val KEY_LAST_RECOMMENDED_PROMPT = "last_recommended_prompt_ms"
    private const val KEY_ASKED_PREFIX = "asked_"
    private const val RECOMMENDED_PROMPT_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Last state seen per step in this process; used to log changes only. */
    private val lastKnown = HashMap<SetupStep, Boolean>()

    /** Applicable steps that are not done. */
    fun missingSteps(context: Context): List<SetupStep> =
            SetupStep.values().filter { it.isApplicable(context) && !it.isDone(context) }

    /** Checks all steps, logs changes, and shows or clears the reminder. Returns the missing steps. */
    @Synchronized
    fun check(context: Context): List<SetupStep> {
        val appContext = context.applicationContext
        val missing = missingSteps(appContext)
        for (step in SetupStep.values().filter { it.isApplicable(appContext) }) {
            val done = step !in missing
            if (lastKnown[step] != done) {
                lastKnown[step] = done
                logChange(step, done)
            }
        }
        // Only required steps get a notification; recommended ones are re-offered in the app
        // (see shouldShowSetup) so a declined recommendation doesn't leave a permanent notification.
        if (missing.any { it.required }) postReminder(appContext, missing.filter { it.required }) else cancelReminder(appContext)
        return missing
    }

    /**
     * Whether the home screen should open [SetupActivity] now: always until onboarding is
     * completed or while a required step is missing; for recommended steps at most once a day.
     */
    fun shouldShowSetup(context: Context): Boolean {
        val missing = missingSteps(context)
        if (!isOnboarded(context)) return true
        if (missing.any { it.required }) return true
        if (missing.isEmpty()) return false
        val last = prefs(context).getLong(KEY_LAST_RECOMMENDED_PROMPT, 0)
        return System.currentTimeMillis() - last > RECOMMENDED_PROMPT_INTERVAL_MS
    }

    fun markSetupShown(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_RECOMMENDED_PROMPT, System.currentTimeMillis()).apply()
    }

    fun isOnboarded(context: Context) = prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun markOnboarded(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    /** Whether the runtime permission prompt for [step] has been shown before. */
    fun wasAsked(context: Context, step: SetupStep) = prefs(context).getBoolean(KEY_ASKED_PREFIX + step.name, false)

    fun markAsked(context: Context, step: SetupStep) {
        prefs(context).edit().putBoolean(KEY_ASKED_PREFIX + step.name, true).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Writes `{user_id, action, timestamp}` to Firestore `phoneUsageData`. */
    private fun logChange(step: SetupStep, done: Boolean) {
        val action = "SETUP_${step.logKey}_${if (done) "OK" else "MISSING"}"
        Log.i(TAG, action)
        val data = hashMapOf(
                "user_id" to FirebaseAuth.getInstance().currentUser?.uid,
                "action" to action,
                "timestamp" to SensingService.kronosClock.getCurrentTimeMs()
        )
        FirebaseRefs.firestore.collection(Collections.PHONE_USAGE_DATA)
                .add(data)
                .addOnFailureListener { e -> Log.w(TAG, "Error adding document", e) }
    }

    /** Posts (or silently refreshes) one reminder listing the missing required steps. */
    private fun postReminder(context: Context, missing: List<SetupStep>) {
        val open = Intent(context, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val builder = NotificationCompat.Builder(context, SensingService.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("FacePsy needs your attention")
                .setContentText("Tap to turn on: " + missing.joinToString(", ") { it.title.toLowerCase() })
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                        "Tap to turn on: " + missing.joinToString(", ") { it.title.toLowerCase() }))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true) // refreshed every check, but alerts only once
                .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT))
        builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setChannelId(SensingService.NOTIFICATION_CHANNEL_ID)
        notificationManager(context).notify(REMINDER_NOTIFICATION_ID, builder.build())
    }

    private fun cancelReminder(context: Context) {
        notificationManager(context).cancel(REMINDER_NOTIFICATION_ID)
    }

    private fun notificationManager(context: Context) =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
