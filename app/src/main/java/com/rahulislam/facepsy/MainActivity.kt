package com.rahulislam.facepsy

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.AuthUI.IdpConfig.EmailBuilder
import com.google.firebase.auth.FirebaseAuth
import com.rahulislam.facepsy.service.CrashRestartHandler
import com.rahulislam.facepsy.setup.SetupMonitor
import com.rahulislam.facepsy.service.SensingService
import com.rahulislam.facepsy.service.ServiceAction
import com.rahulislam.facepsy.service.ServiceState
import com.rahulislam.facepsy.service.getServiceState
import com.rahulislam.facepsy.tasks.flower.Flower3x3Activity
import com.rahulislam.facepsy.tasks.stroop.StroopDescriptionActivity
import com.rahulislam.facepsy.ui.InstructionActivity
import com.rahulislam.facepsy.ui.SetupActivity
import java.util.*

/**
 * Home screen and app entry point (launcher activity).
 *
 * - Signs the participant in with FirebaseUI (email/password), then starts
 *   [SensingService], which runs data collection in the background.
 * - Opens the setup checklist ([SetupActivity]) after sign-in and whenever a required
 *   permission is missing.
 * - Offers buttons for the pre/post surveys (links from `config/survey`), the Stroop and
 *   Flower cognitive tasks, and the instructions screen.
 *
 * The launcher and pinned shortcuts refer to this class by name: **do not rename or
 * move it.**
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Permissions are requested by SetupActivity (opened from onResume).

        Thread.setDefaultUncaughtExceptionHandler(CrashRestartHandler(this))

        // NOTE: legacy behavior, see docs/known-issues.md — the (hidden) Start button
        // deliberately crashes the app; it was used to test CrashRestartHandler.
        val startBtn: Button = findViewById(R.id.startBtn)
        startBtn.setOnClickListener {
            crashMe()
        }

        signInOrStartService()
        setUpButtons()
    }

    /** True right after returning from SetupActivity, so it isn't reopened in a loop. */
    private var returningFromSetup = false

    override fun onResume() {
        super.onResume()
        val requiredMissing = SetupMonitor.check(this).any { it.required }
        findViewById<Button>(R.id.finishSetupBtn).visibility = if (requiredMissing) View.VISIBLE else View.GONE

        // After sign-in: show the setup checklist on first run, and again whenever the
        // participant comes back while something required was revoked or switched off.
        val signedIn = FirebaseAuth.getInstance().currentUser != null
        if (returningFromSetup) {
            returningFromSetup = false
        } else if (signedIn && SetupMonitor.shouldShowSetup(this)) {
            openSetup()
        }
    }

    private fun openSetup() {
        startActivityForResult(Intent(this, SetupActivity::class.java), RC_SETUP)
    }

    /** Launches FirebaseUI sign-in if needed; otherwise starts [SensingService]. */
    private fun signInOrStartService() {
        val auth = FirebaseAuth.getInstance()
        Log.i(TAG, auth.currentUser?.uid.toString())
        if (auth.currentUser == null) {
            startActivityForResult(
                    AuthUI.getInstance()
                            .createSignInIntentBuilder()
                            .setAvailableProviders(Arrays.asList(
                                    EmailBuilder().build()))
                            .build(),
                    RC_SIGN_IN)

            Log.i(TAG, "Sign-in started")
        } else {
            actionOnService(ServiceAction.START)
        }
    }

    private fun setUpButtons() {
        findViewById<Button>(R.id.launchFlowerTaskBtn).setOnClickListener {
            startActivity(Intent(this, Flower3x3Activity::class.java))
        }

        findViewById<Button>(R.id.launchStroopTaskBtn).setOnClickListener {
            startActivity(Intent(this, StroopDescriptionActivity::class.java))
        }

        findViewById<Button>(R.id.launchPreSurveyBtn).setOnClickListener {
            openSurvey(SensingService.surveyConfig["preLink"])
        }

        findViewById<Button>(R.id.launchPostSurveyBtn).setOnClickListener {
            openSurvey(SensingService.surveyConfig["postLink"])
        }

        findViewById<Button>(R.id.finishSetupBtn).setOnClickListener { openSetup() }

        findViewById<Button>(R.id.launchInstructionBtn).setOnClickListener {
            startActivity(Intent(this, InstructionActivity::class.java))
        }
    }

    /** Opens [link] in the browser with `?userId={uid}` appended. */
    private fun openSurvey(link: String?) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(
                link + "?userId=" + FirebaseAuth.getInstance().currentUser?.uid.toString()
        ))
        startActivity(browserIntent)
    }

    /** Sends [action] to [SensingService] (as a foreground service on API 26+). */
    private fun actionOnService(action: ServiceAction) {
        if (getServiceState(this) == ServiceState.STOPPED && action == ServiceAction.STOP) return
        Intent(this, SensingService::class.java).also {
            it.action = action.name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                log("Starting the service in >=26 Mode")
                startForegroundService(it)
                return
            }
            log("Starting the service in < 26 Mode")
            startService(it)
        }
    }

    fun crashMe() {
        throw NullPointerException()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SETUP) returningFromSetup = true

        if (requestCode == RC_SIGN_IN) {
            if (resultCode == Activity.RESULT_OK) {
                actionOnService(ServiceAction.START)
            }
            // Otherwise sign-in failed or was cancelled; the participant can retry by reopening the app.
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    companion object {
        const val RC_SIGN_IN = 123
        private const val RC_SETUP = 124
        const val TAG = "MainActivity"
    }
}
