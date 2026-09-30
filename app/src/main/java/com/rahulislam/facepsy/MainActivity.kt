package com.rahulislam.facepsy

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Environment.getExternalStoragePublicDirectory
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cottacush.android.hiddencam.CaptureTimeFrequency
import com.cottacush.android.hiddencam.HiddenCam
import com.cottacush.android.hiddencam.OnImageCapturedListener
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.AuthUI.IdpConfig.EmailBuilder
import com.google.firebase.auth.FirebaseAuth
import com.rahulislam.facepsy.receiver.CaptureTriggerReceiver
import com.rahulislam.facepsy.service.CrashRestartHandler
import com.rahulislam.facepsy.setup.SetupMonitor
import com.rahulislam.facepsy.setup.SetupStep
import com.rahulislam.facepsy.service.SensingService
import com.rahulislam.facepsy.service.ServiceAction
import com.rahulislam.facepsy.service.ServiceState
import com.rahulislam.facepsy.service.getServiceState
import com.rahulislam.facepsy.tasks.flower.Flower3x3Activity
import com.rahulislam.facepsy.tasks.stroop.StroopDescriptionActivity
import com.rahulislam.facepsy.ui.InstructionActivity
import com.rahulislam.facepsy.ui.SetupActivity
import com.rahulislam.facepsy.util.hasPermissions
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * Home screen and app entry point (launcher activity).
 *
 * - Requests camera and storage permissions.
 * - Signs the participant in with FirebaseUI (email/password), then starts
 *   [SensingService], which runs data collection in the background.
 * - Offers buttons for the pre/post surveys (links from `config/survey`), the Stroop and
 *   Flower cognitive tasks, and the instructions screen.
 *
 * The launcher and pinned shortcuts refer to this class by name: **do not rename or
 * move it.**
 */
class MainActivity : AppCompatActivity(), OnImageCapturedListener {
    private lateinit var hiddenCam: HiddenCam
    private lateinit var baseStorageFolder: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Permissions are requested by SetupActivity (opened from onResume).
        if (hasPermissions(SetupStep.CAMERA_STORAGE_PERMISSIONS)) onPermissionsGranted()

        Thread.setDefaultUncaughtExceptionHandler(CrashRestartHandler(this))

        startCaptureFolderCounter()

        // NOTE: legacy behavior, see docs/known-issues.md — the (hidden) Start button
        // deliberately crashes the app; it was used to test CrashRestartHandler.
        val startBtn: Button = findViewById(R.id.startBtn)
        startBtn.setOnClickListener {
            crashMe()
        }

        // Legacy: folders from an earlier on-device processing pipeline; no longer used.
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Inference").apply {
            if (!exists()) mkdir()
        }
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM + "/Inference"), "image").apply {
            if (!exists()) mkdir()
        }
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM + "/Inference"), "features").apply {
            if (!exists()) mkdir()
        }

        // Legacy test broadcast; nothing receives it.
        val intent = Intent()
        intent.action = "com.rahulislam.broadcast.test"
        intent.putExtra("packageName", "currentApp")
        sendBroadcast(intent)

        signInOrStartService()
        setUpButtons()
    }

    /**
     * Once per second, shows the number of frames waiting in `DCIM/HiddenCam` in the
     * (hidden) image-count label.
     */
    private fun startCaptureFolderCounter() {
        val imageCountTv: TextView = findViewById(R.id.imageCountTv)

        val thread: Thread = object : Thread() {
            @SuppressLint("SetTextI18n")
            override fun run() {
                try {
                    while (!this.isInterrupted) {
                        sleep(1000)
                        runOnUiThread {
                            val dcim = getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM + "/" + CaptureTriggerReceiver.CAPTURE_FOLDER_NAME)
                            if (dcim.listFiles() != null) {
                                val pics = dcim.listFiles().size
                                imageCountTv.text = "Number of Images: $pics"
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                }
            }
        }

        thread.start()
    }

    /** True right after returning from SetupActivity, so it isn't reopened in a loop. */
    private var returningFromSetup = false

    override fun onResume() {
        super.onResume()
        if (!::hiddenCam.isInitialized && hasPermissions(SetupStep.CAMERA_STORAGE_PERMISSIONS)) {
            onPermissionsGranted()
        }

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

    override fun onImageCaptured(image: File, packageName: String, metadata: JSONObject) {
        val message = "Image captured, saved to:${image.absolutePath}"
        Log.i(TAG, message)
        log(message)
        showToast(message)
    }

    override fun onImageCaptureError(e: Throwable?) {
        e?.run {
            val message = "Image captured failed:${e.message}"
            showToast(message)
            log(message)
            printStackTrace()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        Log.d(TAG, message)
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    /**
     * Creates `DCIM/HiddenCam` and initializes a [HiddenCam] instance. The instance is
     * never started (captures are started by [CaptureTriggerReceiver]), but creating it
     * binds CameraX to its lifecycle, so it is kept.
     */
    fun onPermissionsGranted() {
        Log.d(TAG, "permission granted")

        baseStorageFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), CaptureTriggerReceiver.CAPTURE_FOLDER_NAME).apply {
            if (!exists()) mkdir()
        }
        hiddenCam = HiddenCam(
            applicationContext, baseStorageFolder, this,
            CaptureTimeFrequency.Recurring(CaptureTriggerReceiver.RECURRING_INTERVAL_MS),
            targetResolution = Size(CaptureTriggerReceiver.CAPTURE_WIDTH, CaptureTriggerReceiver.CAPTURE_HEIGHT)
        )

    }

    companion object {
        const val RC_SIGN_IN = 123
        private const val RC_SETUP = 124
        const val TAG = "MainActivity"
    }
}
