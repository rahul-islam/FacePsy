package com.rahulislam.facepsy.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rahulislam.facepsy.R
import com.rahulislam.facepsy.setup.SetupMonitor
import com.rahulislam.facepsy.setup.SetupStep

/**
 * Participant setup checklist: one screen that explains every permission FacePsy needs,
 * shows which are on, and sends the participant to the right place to turn each one on.
 *
 * Opened by [com.rahulislam.facepsy.MainActivity] after sign-in (and again whenever a
 * required step is missing), and by the reminder notification from [SetupMonitor].
 * Statuses refresh every time the screen resumes, e.g. on return from system Settings.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var stepsContainer: LinearLayout
    private lateinit var continueBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        stepsContainer = findViewById(R.id.stepsContainer)
        continueBtn = findViewById(R.id.setupContinueBtn)
        continueBtn.setOnClickListener {
            SetupMonitor.markOnboarded(this)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        SetupMonitor.markSetupShown(this)
        SetupMonitor.check(this)
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        SetupMonitor.check(this)
        render()
    }

    /** Rebuilds the checklist from the current state of each step. */
    private fun render() {
        stepsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val steps = SetupStep.values().filter { it.isApplicable(this) }

        for (step in steps) {
            val row = inflater.inflate(R.layout.item_setup_step, stepsContainer, false)
            val done = step.isDone(this)
            val blocked = isPermanentlyDenied(step)

            row.findViewById<TextView>(R.id.stepTitleTv).text = step.title
            row.findViewById<TextView>(R.id.stepReasonTv).text =
                    if (blocked) step.reason + "\n\nYou chose \"Don't allow\" before, so Android won't ask again. Tap below, open Permissions and allow ${step.title}."
                    else step.reason

            val status = row.findViewById<TextView>(R.id.stepStatusTv)
            when {
                done -> {
                    status.text = "✓ On"
                    status.setTextColor(ContextCompat.getColor(this, R.color.setupDone))
                }
                step.required -> {
                    status.text = "Required"
                    status.setTextColor(ContextCompat.getColor(this, R.color.setupRequired))
                }
                else -> {
                    status.text = "Recommended"
                    status.setTextColor(ContextCompat.getColor(this, R.color.setupRecommended))
                }
            }

            val action = row.findViewById<Button>(R.id.stepActionBtn)
            action.visibility = if (done) View.GONE else View.VISIBLE
            action.text = if (blocked) "Open App info" else actionLabel(step)
            action.setOnClickListener { perform(step) }

            stepsContainer.addView(row)
        }

        val missing = steps.filter { !it.isDone(this) }
        val requiredMissing = missing.any { it.required }
        continueBtn.isEnabled = !requiredMissing
        continueBtn.text = when {
            requiredMissing -> "Turn on the required items to continue"
            missing.isEmpty() -> "All set"
            else -> "Continue"
        }
    }

    private fun actionLabel(step: SetupStep) = when (step) {
        SetupStep.CAMERA, SetupStep.MICROPHONE -> "Allow"
        SetupStep.ACCESSIBILITY -> "Open Accessibility settings"
        SetupStep.NOTIFICATIONS -> "Open notification settings"
        SetupStep.BATTERY -> "Allow"
        SetupStep.KEEP_PERMISSIONS -> "Open App info"
    }

    /** Starts the system flow that lets the participant turn [step] on. */
    private fun perform(step: SetupStep) {
        when (step) {
            SetupStep.CAMERA, SetupStep.MICROPHONE ->
                if (isPermanentlyDenied(step)) {
                    openAppDetails()
                } else {
                    SetupMonitor.markAsked(this, step)
                    requestPermissions(step.runtimePermissions(), REQUEST_RUNTIME_PERMISSION)
                }
            SetupStep.ACCESSIBILITY ->
                start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupStep.NOTIFICATIONS ->
                // Apps targeting SDK <= 32 get no runtime prompt on Android 13+, so go
                // straight to the app's notification settings.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    start(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                } else {
                    openAppDetails()
                }
            SetupStep.BATTERY ->
                start(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                        fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            SetupStep.KEEP_PERMISSIONS ->
                start(Intent(ACTION_AUTO_REVOKE_PERMISSIONS, Uri.parse("package:$packageName")),
                        fallback = appDetailsIntent())
        }
    }

    /**
     * True if the participant denied a runtime permission of [step] and Android will no
     * longer show the prompt ("Don't allow" twice, or "Don't ask again").
     */
    private fun isPermanentlyDenied(step: SetupStep): Boolean {
        val permissions = step.runtimePermissions()
        if (permissions.isEmpty() || !SetupMonitor.wasAsked(this, step)) return false
        return permissions.any {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED &&
                    !shouldShowRequestPermissionRationale(it)
        }
    }

    private fun appDetailsIntent() =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun openAppDetails() = start(appDetailsIntent())

    private fun start(intent: Intent, fallback: Intent? = null) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            fallback?.let { startActivity(it) }
        }
    }

    companion object {
        private const val REQUEST_RUNTIME_PERMISSION = 200

        /** `Intent.ACTION_AUTO_REVOKE_PERMISSIONS` (API 30); not in the compile SDK (28). */
        private const val ACTION_AUTO_REVOKE_PERMISSIONS = "android.intent.action.AUTO_REVOKE_PERMISSIONS"
    }
}
