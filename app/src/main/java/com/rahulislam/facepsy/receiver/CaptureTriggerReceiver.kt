package com.rahulislam.facepsy.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.rahulislam.facepsy.capture.VideoCaptureSession
import com.rahulislam.facepsy.data.FirebaseRefs.RealtimeDb
import com.rahulislam.facepsy.data.TriggerContract
import com.rahulislam.facepsy.processing.VideoProcessingWorker
import com.rahulislam.facepsy.service.SensingService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Records a timed front-camera video session when a trigger fires, and queues the
 * recording for processing.
 *
 * Triggers:
 * - `ACTION_USER_PRESENT` (phone unlock), for `config/triggerDuration.unlockEvent` ms;
 * - [TriggerContract.ACTION_TRIGGER] (tracked app opened, or a cognitive task started),
 *   for the duration carried in the intent.
 *
 * Only one session runs at a time ([isCapturing]). [VideoCaptureSession] writes an MP4
 * (with audio when the microphone permission is granted) to app-private storage; when it
 * finishes, a [VideoProcessingWorker] is enqueued for it. Capture state is mirrored to
 * Realtime Database `/captureStatus/{uid}`.
 *
 * Registered at runtime by [SensingService] and also declared in the manifest.
 */
class CaptureTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, action.toString())
        if (Intent.ACTION_USER_PRESENT == action && !isCapturing) {
            SensingService.triggerDuration[TriggerContract.DurationKeys.UNLOCK_EVENT]?.let {
                startCaptureSession(context, it.toLong(),
                        TriggerContract.TRIGGER_PHONE_UNLOCK, TriggerContract.TRIGGER_PHONE_UNLOCK)
            }
        }

        if (TriggerContract.ACTION_TRIGGER == action && !isCapturing) {
            val triggerName = intent.extras!!.getString(TriggerContract.EXTRA_PACKAGE_NAME).toString()
            val duration = intent.extras!!.getString(TriggerContract.EXTRA_DURATION)!!.toLong()
            val gameId = intent.extras!!.getString(TriggerContract.EXTRA_GAME_ID).toString()
            startCaptureSession(context, duration, triggerName, gameId)
        }
    }

    /** Records video for [duration] ms, tagged with [triggerName] and [gameId]. */
    private fun startCaptureSession(context: Context, duration: Long, triggerName: String, gameId: String) {
        val appContext = context.applicationContext
        val startedAt = SensingService.kronosClock.getCurrentTimeMs()
        val sessionName = SESSION_NAME_FORMAT.format(Date(startedAt)) + triggerName
        val videoFile = File(File(appContext.filesDir, CAPTURE_DIR), "$sessionName.mp4")
        val withAudio = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        isCapturing = true
        setCapturingStatus(isCapturing)
        Log.d(TAG, "Start $sessionName (audio: $withAudio)")

        VideoCaptureSession(appContext, videoFile, duration, withAudio, object : VideoCaptureSession.Listener {
            override fun onRecorded(video: File) {
                endSession()
                enqueueProcessing(appContext, video, startedAt, seqId = startedAt.toString(), gameId = gameId, triggerName = triggerName)
            }

            override fun onRecordingFailed(message: String, cause: Throwable?) {
                endSession()
                Log.w(TAG, "$message ($sessionName)", cause)
            }
        }).start()
    }

    private fun endSession() {
        isCapturing = false
        setCapturingStatus(isCapturing)
    }

    /** Queues [VideoProcessingWorker] for a finished recording; runs when online. */
    private fun enqueueProcessing(context: Context, video: File, startedAt: Long, seqId: String, gameId: String, triggerName: String) {
        Log.d(TAG, "Video recorded: ${video.absolutePath} (${video.length()} bytes)")
        val request = OneTimeWorkRequestBuilder<VideoProcessingWorker>()
                .setInputData(workDataOf(
                        VideoProcessingWorker.KEY_VIDEO_PATH to video.absolutePath,
                        VideoProcessingWorker.KEY_STARTED_AT to startedAt,
                        VideoProcessingWorker.KEY_SEQ_ID to seqId,
                        VideoProcessingWorker.KEY_GAME_ID to gameId,
                        VideoProcessingWorker.KEY_TRIGGER_NAME to triggerName
                ))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(VideoProcessingWorker.WORK_TAG)
                .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    /** Publishes [captureStatus] to Realtime Database `/captureStatus/{uid}`. */
    fun setCapturingStatus(captureStatus: Boolean) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            Firebase.database.getReference(RealtimeDb.captureStatus(user.uid)).setValue(captureStatus)
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

        /** Folder under the app's private files dir where recordings wait for processing. */
        const val CAPTURE_DIR = "captures"

        /** Session (file) name prefix: capture start time; the trigger name is appended. */
        private val SESSION_NAME_FORMAT = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)

        /** True while a capture session is running; only one session at a time. */
        var isCapturing = false
    }
}
