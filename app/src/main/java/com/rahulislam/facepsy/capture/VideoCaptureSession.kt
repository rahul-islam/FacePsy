package com.rahulislam.facepsy.capture

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File

/**
 * Records one capture session as an MP4 with the front camera, without any preview,
 * for [durationMs], then reports the file to [listener].
 *
 * Uses its own [LifecycleOwner] so CameraX can run from a receiver/service. Must be
 * started on the main thread. Audio is included when [withAudio] is true (the caller
 * checks the RECORD_AUDIO permission).
 */
class VideoCaptureSession(
        private val context: Context,
        private val outputFile: File,
        private val durationMs: Long,
        private val withAudio: Boolean,
        private val listener: Listener
) : LifecycleOwner {

    interface Listener {
        /** Called on the main thread with a finished recording. */
        fun onRecorded(video: File)

        /** Called on the main thread when nothing usable was recorded. */
        fun onRecordingFailed(message: String, cause: Throwable?)
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recording: Recording? = null
    private var finished = false

    override fun getLifecycle(): Lifecycle = lifecycleRegistry

    /** Binds the camera and starts recording; stops by itself after [durationMs]. */
    fun start() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(
                                VIDEO_QUALITY, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, videoCapture)
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED

                outputFile.parentFile?.mkdirs()
                recording = prepare(videoCapture)
                        .start(ContextCompat.getMainExecutor(context)) { event -> onEvent(event, provider, videoCapture) }

                mainHandler.postDelayed({ recording?.stop() }, durationMs)
            } catch (e: Exception) {
                finish(null, "Could not start recording", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("MissingPermission") // the caller only sets withAudio when RECORD_AUDIO is granted
    private fun prepare(videoCapture: VideoCapture<Recorder>) =
            videoCapture.output
                    .prepareRecording(context, FileOutputOptions.Builder(outputFile).build())
                    .let { if (withAudio) it.withAudioEnabled() else it }

    private fun onEvent(event: VideoRecordEvent, provider: ProcessCameraProvider, videoCapture: VideoCapture<Recorder>) {
        when (event) {
            is VideoRecordEvent.Start -> Log.d(TAG, "Recording started: ${outputFile.name}")
            is VideoRecordEvent.Finalize -> {
                provider.unbind(videoCapture)
                // Some errors (e.g. the source stopped) still leave a playable file.
                val usable = outputFile.exists() && outputFile.length() > 0
                if (event.error == VideoRecordEvent.Finalize.ERROR_NONE || usable) {
                    if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        Log.w(TAG, "Recording finished with error ${event.error}, keeping ${outputFile.length()} bytes", event.cause)
                    }
                    finish(outputFile, null, null)
                } else {
                    finish(null, "Recording failed with error ${event.error}", event.cause)
                }
            }
        }
    }

    private fun finish(video: File?, message: String?, cause: Throwable?) {
        if (finished) return
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        if (video != null) listener.onRecorded(video) else listener.onRecordingFailed(message ?: "Recording failed", cause)
    }

    companion object {
        private const val TAG = "VideoCaptureSession"

        /** 1080p, the same resolution the photo-based pipeline used (1080x1920 frames). */
        val VIDEO_QUALITY: Quality = Quality.FHD
    }
}
