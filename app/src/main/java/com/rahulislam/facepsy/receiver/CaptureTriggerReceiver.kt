package com.rahulislam.facepsy.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Camera
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import android.util.Size
import androidx.work.*
import com.cottacush.android.hiddencam.CaptureTimeFrequency
import com.cottacush.android.hiddencam.HiddenCam
import com.cottacush.android.hiddencam.OnImageCapturedListener
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.rahulislam.facepsy.data.FirebaseRefs.RealtimeDb
import com.rahulislam.facepsy.data.TriggerContract
import com.rahulislam.facepsy.processing.ImageProcessingWorker
import com.rahulislam.facepsy.service.SensingService
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Starts a timed front-camera capture session when a trigger fires, and queues every
 * captured frame for feature extraction.
 *
 * Triggers:
 * - `ACTION_USER_PRESENT` (phone unlock), for `config/triggerDuration.unlockEvent` ms;
 * - [TriggerContract.ACTION_TRIGGER] (tracked app opened, or a cognitive task started),
 *   for the duration carried in the intent.
 *
 * Only one session runs at a time ([isCapturing]). Frames are written by [HiddenCam]
 * to `DCIM/HiddenCam` every [RECURRING_INTERVAL_MS] ms, then [onImageCaptured]
 * enqueues an [ImageProcessingWorker] for each file. Capture state is mirrored to
 * Realtime Database `/captureStatus/{uid}`.
 *
 * Registered at runtime by [SensingService] and also declared in the manifest.
 */
class CaptureTriggerReceiver : BroadcastReceiver(), OnImageCapturedListener {

    private lateinit var hiddenCam: HiddenCam
    private lateinit var baseStorageFolder: File

    var context: Context? = null

    override fun onReceive(context: Context, intent: Intent) {
        this.context = context

        val action = intent.action
        // NOTE: legacy behavior, see docs/known-issues.md — isCameraInUse() opens the
        // camera on every broadcast and is only logged, not used to gate capture.
        Log.i(TAG, action + isCameraInUse().toString())
        if (Intent.ACTION_USER_PRESENT == action && !isCapturing) {
            SensingService.triggerDuration[TriggerContract.DurationKeys.UNLOCK_EVENT]?.let {
                startCaptureSession(context, RECURRING_INTERVAL_MS, it.toLong(),
                        TriggerContract.TRIGGER_PHONE_UNLOCK, TriggerContract.TRIGGER_PHONE_UNLOCK)
            }
        }

        if (TriggerContract.ACTION_TRIGGER == action && !isCapturing) {
            val triggerName = intent.extras!!.getString(TriggerContract.EXTRA_PACKAGE_NAME).toString()
            val duration = intent.extras!!.getString(TriggerContract.EXTRA_DURATION).toLong()
            val gameId = intent.extras!!.getString(TriggerContract.EXTRA_GAME_ID).toString()
            startCaptureSession(context, RECURRING_INTERVAL_MS, duration, triggerName, gameId)
        }
    }

    /** Returns true if the (back) camera cannot be opened, i.e. another app holds it. */
    fun isCameraInUse(): Boolean {
        try {
            Camera.open()
        } catch (e: RuntimeException) {
            return true
        }
        return false
    }

    /**
     * Captures a frame every [recurringInterval] ms for [duration] ms, tagging frames
     * with [triggerName] and [gameId].
     */
    private fun startCaptureSession(context: Context, recurringInterval: Long, duration: Long, triggerName: String, gameId: String) {
        baseStorageFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), CAPTURE_FOLDER_NAME).apply {
            if (!exists()) mkdir()
        }

        val jsonMetadata = JSONObject()
        jsonMetadata.put("seq_id", SensingService.kronosClock.getCurrentTimeMs())
        jsonMetadata.put("gameId", gameId)

        hiddenCam = HiddenCam(
                context, baseStorageFolder, this,
                CaptureTimeFrequency.Recurring(recurringInterval),
                targetResolution = Size(CAPTURE_WIDTH, CAPTURE_HEIGHT),
                triggerName = triggerName,
                metadata = jsonMetadata
        )

        // Unused lookup kept from the original code (vibration feedback was disabled);
        // removing the cast would change behavior on devices without a vibrator service.
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        isCapturing = true
        hiddenCam.start()
        Log.d(TAG, "Start")
        setCapturingStatus(isCapturing)
        // Stop on the main thread: HiddenCam.stop() updates a Lifecycle, which must happen there.
        Handler(Looper.getMainLooper()).postDelayed({
            hiddenCam.stop()
            isCapturing = false
            setCapturingStatus(isCapturing)
        }, duration)
    }

    /** Publishes [captureStatus] to Realtime Database `/captureStatus/{uid}`. */
    fun setCapturingStatus(captureStatus: Boolean) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            Firebase.database.getReference(RealtimeDb.captureStatus(user.uid)).setValue(captureStatus)
        }
    }

    /** Queues feature extraction for a captured frame. */
    override fun onImageCaptured(image: File, packageName: String, metadata: JSONObject) {
        Log.d(TAG, "Image captured, saved to:${image.absolutePath}")

        val featureExtractionRequest: WorkRequest =
                OneTimeWorkRequestBuilder<ImageProcessingWorker>()
                        .setInputData(workDataOf(
                                ImageProcessingWorker.KEY_IMAGE_URI to "${image.absolutePath}",
                                ImageProcessingWorker.KEY_TIMESTAMP to "${SensingService.kronosClock.getCurrentTimeMs()}",
                                ImageProcessingWorker.KEY_SEQ_ID to "${metadata["seq_id"]}",
                                ImageProcessingWorker.KEY_GAME_ID to "${metadata["gameId"]}",
                                ImageProcessingWorker.KEY_TRIGGER_NAME to "${packageName}"
                        ))
                        .addTag(ImageProcessingWorker.WORK_TAG)
                        .setInitialDelay(5, TimeUnit.MILLISECONDS)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MILLISECONDS)
                        .build()

        context?.let {
            WorkManager
                .getInstance(it)
                .enqueue(featureExtractionRequest)
        }
    }

    override fun onImageCaptureError(e: Throwable?) {
        e?.run {
            Log.d(TAG, "Image captured failed:${e.message}")
            printStackTrace()
        }
    }

    /**
     * Filter used for the runtime registration in [SensingService]. Screen on/off are
     * included but ignored by [onReceive].
     */
    val filter: IntentFilter
        get() {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            filter.addAction(TriggerContract.ACTION_TRIGGER)
            return filter
        }

    companion object {
        const val TAG = "CaptureTriggerReceiver"

        /** Interval between captured frames. */
        const val RECURRING_INTERVAL_MS = 1 * 100L

        /** Folder under DCIM where HiddenCam writes frames before they are processed. */
        const val CAPTURE_FOLDER_NAME = "HiddenCam"

        const val CAPTURE_WIDTH = 1080
        const val CAPTURE_HEIGHT = 1920

        /** True while a capture session is running; only one session at a time. */
        var isCapturing = false
    }
}
