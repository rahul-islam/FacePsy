package com.rahulislam.facepsy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.firebase.auth.FirebaseAuth
import com.rahulislam.facepsy.data.FirebaseRefs
import com.rahulislam.facepsy.data.FirebaseRefs.Collections
import com.rahulislam.facepsy.data.TriggerContract
import com.rahulislam.facepsy.service.SensingService

/**
 * Watches which app is in the foreground.
 *
 * On every activity window change it logs `{user_id, action: packageName, timestamp}`
 * to Firestore `phoneUsageData`. If the package is in [SensingService.appTrackingList]
 * it also broadcasts [TriggerContract.ACTION_TRIGGER] so that
 * [com.rahulislam.facepsy.receiver.CaptureTriggerReceiver] starts a capture.
 *
 * The participant enables this service in system Accessibility settings, which store
 * it by component name: **do not rename or move this class.**
 */
class FacePsyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Configure these here for compatibility with API 13 and below.
        val config = AccessibilityServiceInfo()
        config.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        config.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        if (Build.VERSION.SDK_INT >= 16) // Just in case this helps
            config.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = config
    }

    @SuppressLint("LongLogTag")
    override fun onAccessibilityEvent(accessibilityEvent: AccessibilityEvent) {
        Log.d(TAG, accessibilityEvent.packageName.toString() + " -- " + accessibilityEvent.className)
        if (accessibilityEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (accessibilityEvent.packageName != null && accessibilityEvent.className != null) {
                val componentName = ComponentName(
                        accessibilityEvent.packageName.toString(),
                        accessibilityEvent.className.toString()
                )
                val isActivity = tryGetActivity(componentName) != null
                if (isActivity) {
                    val packageName = accessibilityEvent.packageName.toString()
                    Log.i(TAG, "Foreground activity: $packageName")

                    logForegroundApp(packageName)

                    if (SensingService.appTrackingList.contains(packageName)) {
                        val intent = Intent()
                        intent.action = TriggerContract.ACTION_TRIGGER
                        intent.putExtra(TriggerContract.EXTRA_PACKAGE_NAME, packageName)
                        intent.putExtra(TriggerContract.EXTRA_DURATION, SensingService.triggerDuration[TriggerContract.DurationKeys.APP].toString())
                        intent.putExtra(TriggerContract.EXTRA_GAME_ID, TriggerContract.GAME_ID_APP_USAGE)
                        this.sendBroadcast(intent)
                        Log.i(TAG, "Capture trigger sent")
                    }
                }
            }
        }
    }

    /** Writes a foreground-app event to Firestore `phoneUsageData`. */
    @SuppressLint("LongLogTag")
    private fun logForegroundApp(packageName: String) {
        val appUsageData = hashMapOf(
                "user_id" to FirebaseAuth.getInstance().currentUser?.uid,
                "action" to packageName,
                "timestamp" to SensingService.kronosClock.getCurrentTimeMs()
        )
        FirebaseRefs.firestore.collection(Collections.PHONE_USAGE_DATA)
                .add(appUsageData)
                .addOnSuccessListener { documentReference ->
                    Log.d(TAG, "DocumentSnapshot added with ID: ${documentReference.id}")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Error adding document", e)
                }
    }

    /** Returns the activity info for [componentName], or null if it is not an activity. */
    private fun tryGetActivity(componentName: ComponentName): ActivityInfo? {
        return try {
            packageManager.getActivityInfo(componentName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    override fun onInterrupt() {}

    companion object {
        const val TAG = "FacePsyAccessibilityService"
    }
}
