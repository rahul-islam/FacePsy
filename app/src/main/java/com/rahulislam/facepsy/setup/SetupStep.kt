package com.rahulislam.facepsy.setup

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.accessibility.AccessibilityEventCompat
import androidx.core.view.accessibility.AccessibilityManagerCompat
import com.rahulislam.facepsy.util.hasPermissions

/**
 * One item of the participant setup checklist shown by
 * [com.rahulislam.facepsy.ui.SetupActivity].
 *
 * [required] steps block data collection; the others make it more reliable.
 * [logKey] is used in the `phoneUsageData` actions `SETUP_<logKey>_OK` / `SETUP_<logKey>_MISSING`.
 */
enum class SetupStep(
        val title: String,
        val reason: String,
        val required: Boolean,
        val logKey: String
) {
    CAMERA(
            "Camera",
            "When a trigger fires, FacePsy records a short video of your face with the front camera. The video is analysed on your phone and then deleted; only facial measurements and eye-region images are uploaded.",
            required = true,
            logKey = "CAMERA"
    ),
    MICROPHONE(
            "Microphone",
            "FacePsy records audio together with each video. The audio, which can include other people's voices nearby, is uploaded to the study server for analysis.",
            required = true,
            logKey = "MICROPHONE"
    ),
    ACCESSIBILITY(
            "Accessibility service",
            "Lets FacePsy see which app is open, so it can log app usage and start data collection when you open a study app. On the next screen tap FacePsy and turn it on.\n\nIf the switch is greyed out: open App info for FacePsy, tap ⋮ and choose \"Allow restricted settings\", then try again.",
            required = true,
            logKey = "ACCESSIBILITY"
    ),
    NOTIFICATIONS(
            "Notifications",
            "Shows that data collection is running and reminds you if something needs attention.",
            required = false,
            logKey = "NOTIFICATIONS"
    ),
    BATTERY(
            "Unrestricted battery use",
            "Stops Android from pausing FacePsy in the background to save battery.",
            required = false,
            logKey = "BATTERY"
    ),
    KEEP_PERMISSIONS(
            "Keep permissions",
            "Android removes permissions from apps you haven't opened for a few months. On the next screen, scroll to the bottom and under \"Unused app settings\" turn off \"Manage app if unused\". (On some phones it is called \"Pause app activity if unused\" or \"Remove permissions if app is unused\".)",
            required = false,
            logKey = "KEEP_PERMISSIONS"
    );

    /** False if the step doesn't exist on this Android version. */
    fun isApplicable(context: Context): Boolean = when (this) {
        BATTERY -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        // Hidden when unused-app permission removal can't apply to FacePsy at all (e.g.
        // target SDK < 30 in the default mode: the system toggle is off and greyed out).
        KEEP_PERMISSIONS -> Build.VERSION.SDK_INT >= API_R && autoRevokeExempted(context) != null &&
                !(autoRevokeMode(context) == AppOpsManager.MODE_DEFAULT &&
                        context.applicationInfo.targetSdkVersion < API_R)
        else -> true
    }

    fun isDone(context: Context): Boolean = when (this) {
        CAMERA, MICROPHONE -> context.hasPermissions(runtimePermissions())
        ACCESSIBILITY -> isAccessibilityEnabled(context)
        NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
        BATTERY -> Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .isIgnoringBatteryOptimizations(context.packageName)
        KEEP_PERMISSIONS -> autoRevokeExempted(context) ?: true
    }

    /** Runtime permissions this step requests (empty for settings-based steps). */
    fun runtimePermissions(): Array<String> = when (this) {
        CAMERA -> arrayOf(Manifest.permission.CAMERA)
        MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
        else -> emptyArray()
    }

    companion object {

        /** Android 11 (API 30); not in the compile SDK (28). */
        const val API_R = 30

        /** App-op behind Android 11+ "Manage app if unused" / auto-revoke. */
        private const val OP_AUTO_REVOKE = "android:auto_revoke_permissions_if_unused"

        /** Current mode of [OP_AUTO_REVOKE] (`AppOpsManager.MODE_*`), or null if unavailable. */
        fun autoRevokeMode(context: Context): Int? = try {
            (context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
                    .checkOpNoThrow(OP_AUTO_REVOKE, context.applicationInfo.uid, context.packageName)
        } catch (e: Exception) {
            null
        }

        /**
         * Whether the app is exempt from Android 11+ unused-app permission removal
         * ("Manage app if unused" is off). Null if the feature doesn't exist here.
         *
         * `MODE_DEFAULT` means removal is on only for apps targeting SDK 30+, so a
         * target-28 app is exempt without the participant doing anything.
         */
        fun autoRevokeExempted(context: Context): Boolean? {
            if (Build.VERSION.SDK_INT < API_R) return null
            return when (autoRevokeMode(context)) {
                AppOpsManager.MODE_IGNORED -> true
                AppOpsManager.MODE_ALLOWED -> false
                AppOpsManager.MODE_DEFAULT -> context.applicationInfo.targetSdkVersion < API_R
                else -> null
            }
        }

        /**
         * True if an accessibility service of this package is enabled. Checks the secure
         * setting first, then falls back to two AccessibilityManager APIs.
         */
        fun isAccessibilityEnabled(context: Context): Boolean {
            val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (settingValue != null && settingValue.contains(context.packageName)) return true

            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            try {
                val enabledServices = AccessibilityManagerCompat.getEnabledAccessibilityServiceList(accessibilityManager, AccessibilityEventCompat.TYPES_ALL_MASK)
                if (enabledServices.any { it.id.contains(context.packageName) }) return true
            } catch (e: NoSuchMethodError) {
            }
            try {
                val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityEvent.TYPES_ALL_MASK)
                if (enabledServices.any { it.id.contains(context.packageName) }) return true
            } catch (e: NoSuchMethodError) {
            }
            return false
        }
    }
}
