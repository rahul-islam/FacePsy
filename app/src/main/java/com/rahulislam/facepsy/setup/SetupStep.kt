package com.rahulislam.facepsy.setup

import android.Manifest
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
    CAMERA_STORAGE(
            "Camera and storage",
            "FacePsy briefly records your face with the front camera when a trigger fires, stores the frames on the phone until they are analysed, then deletes them.",
            required = true,
            logKey = "CAMERA"
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
            "Android removes permissions from apps you haven't opened for a few months. Turn off \"Pause app activity if unused\" (or \"Remove permissions if app is unused\") so the study keeps working.",
            required = false,
            logKey = "KEEP_PERMISSIONS"
    );

    /** False if the step doesn't exist on this Android version. */
    fun isApplicable(context: Context): Boolean = when (this) {
        BATTERY -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        KEEP_PERMISSIONS -> Build.VERSION.SDK_INT >= API_R && autoRevokeExempted(context) != null
        else -> true
    }

    fun isDone(context: Context): Boolean = when (this) {
        CAMERA_STORAGE -> context.hasPermissions(CAMERA_STORAGE_PERMISSIONS)
        ACCESSIBILITY -> isAccessibilityEnabled(context)
        NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
        BATTERY -> Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .isIgnoringBatteryOptimizations(context.packageName)
        KEEP_PERMISSIONS -> autoRevokeExempted(context) ?: true
    }

    companion object {
        val CAMERA_STORAGE_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)

        /** Android 11 (API 30); not in the compile SDK (28). */
        const val API_R = 30

        /**
         * Whether the app is exempt from Android 11+ unused-app permission removal, via
         * `PackageManager.isAutoRevokeWhitelisted()` (API 30, called reflectively because
         * the compile SDK is 28). Null if unavailable.
         */
        fun autoRevokeExempted(context: Context): Boolean? = try {
            context.packageManager.javaClass.getMethod("isAutoRevokeWhitelisted")
                    .invoke(context.packageManager) as Boolean
        } catch (e: Exception) {
            null
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
