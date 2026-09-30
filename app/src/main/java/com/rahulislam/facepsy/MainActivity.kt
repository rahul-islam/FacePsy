package com.rahulislam.facepsy

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Environment.getExternalStoragePublicDirectory
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.cottacush.android.hiddencam.CaptureTimeFrequency
import com.cottacush.android.hiddencam.HiddenCam
import com.cottacush.android.hiddencam.OnImageCapturedListener
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.AuthUI.IdpConfig.EmailBuilder
import com.google.firebase.auth.FirebaseAuth
import com.rahulislam.facepsy.receiver.CaptureTriggerReceiver
import com.rahulislam.facepsy.service.CrashRestartHandler
import com.rahulislam.facepsy.service.SensingService
import com.rahulislam.facepsy.service.ServiceAction
import com.rahulislam.facepsy.service.ServiceState
import com.rahulislam.facepsy.service.getServiceState
import com.rahulislam.facepsy.tasks.flower.Flower3x3Activity
import com.rahulislam.facepsy.tasks.stroop.StroopDescriptionActivity
import com.rahulislam.facepsy.ui.InstructionActivity
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
    private val requiredPermissions =
        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA)

    private lateinit var hiddenCam: HiddenCam
    private lateinit var baseStorageFolder: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (checkPermissions()) onPermissionsGranted()

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

        requestNotificationPermissionIfNeeded()
    }

    /**
     * On Android 13+, asks for the notification permission so the foreground-service
     * notification, the accessibility reminder and FCM reminders are visible.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= API_TIRAMISU &&
                !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            requestPermissions(arrayOf(PERMISSION_POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
        }
    }

    private fun checkPermissions(): Boolean {
        return if (hasPermissions(requiredPermissions)) true
        else {
            requestPermissions(requiredPermissions, CAMERA_AND_STORAGE_PERMISSION_REQUEST_CODE)
            false
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        Log.d(TAG, "Permission result called")
        if (requestCode == CAMERA_AND_STORAGE_PERMISSION_REQUEST_CODE &&
                confirmPermissionResults(grantResults)
        ) onPermissionsGranted()

        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE &&
                !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            showEnableNotificationsDialog()
        }
    }

    /**
     * Fallback when the notification permission is still off: apps targeting SDK 32 or
     * lower may not get a runtime prompt, so send the participant to the app's
     * notification settings instead.
     */
    private fun showEnableNotificationsDialog() {
        AlertDialog.Builder(this)
                .setTitle("Allow notifications")
                .setMessage("FacePsy shows a notification while data collection is running. Please allow notifications on the next screen.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
                .setNegativeButton("Not now", null)
                .show()
    }

    private fun confirmPermissionResults(results: IntArray): Boolean {
        results.forEach {
            if (it != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    companion object {
        const val RC_SIGN_IN = 123
        const val TAG = "MainActivity"
        const val CAMERA_AND_STORAGE_PERMISSION_REQUEST_CODE = 100
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 101

        // Not in the compile SDK (28); values from Android 13 (API 33).
        private const val API_TIRAMISU = 33
        private const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}
