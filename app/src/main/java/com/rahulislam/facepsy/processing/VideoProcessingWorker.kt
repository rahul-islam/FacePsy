package com.rahulislam.facepsy.processing

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.rahulislam.facepsy.data.FirebaseRefs
import com.rahulislam.facepsy.data.FirebaseRefs.Collections
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/**
 * Processes one recorded capture session (MP4 from
 * [com.rahulislam.facepsy.capture.VideoCaptureSession]):
 *
 * 1. **Audio:** copies the audio track, without re-encoding, into an `.m4a`, uploads it to
 *    Cloud Storage `audio/{uid}/{session}.m4a` and writes an `audioRecordings` document.
 * 2. **Video:** decodes the frames with the hardware decoder ([VideoFrameDecoder]) and runs
 *    [FaceFeatureExtractor] on every [FRAME_STEP]-th one (ML Kit gets the raw YUV frame),
 *    writing one `features` document per face per frame (the same fields as photos, plus
 *    `metadata.frameIndex`, `metadata.frameTimeMs`, `metadata.source = "video"`).
 * 3. Deletes the video.
 *
 * Progress is saved every [CHUNK_SIZE] analysed frames, so if WorkManager
 * stops the worker (e.g. its 10-minute limit) the retry resumes where it left off.
 * Document ids are deterministic, so a retry overwrites instead of duplicating.
 */
class VideoProcessingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override val coroutineContext = Dispatchers.IO

    override suspend fun doWork(): Result {
        val videoPath = inputData.getString(KEY_VIDEO_PATH) ?: return Result.failure()
        val startedAt = inputData.getLong(KEY_STARTED_AT, -1L)
        val seqId = inputData.getString(KEY_SEQ_ID) ?: return Result.failure()
        val gameId = inputData.getString(KEY_GAME_ID) ?: return Result.failure()
        val triggerName = inputData.getString(KEY_TRIGGER_NAME) ?: return Result.failure()

        val video = File(videoPath)
        if (!video.exists()) return Result.success() // already processed
        val session = Session(video, startedAt, seqId, gameId, triggerName)

        return try {
            uploadAudioOnce(session)
            processFrames(session)
            deleteSessionFiles(video)
            Log.i(TAG, "Finished ${video.name}")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Processing ${video.name} failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                deleteSessionFiles(video)
                Result.failure()
            }
        }
    }

    private class Session(val video: File, val startedAt: Long, val seqId: String, val gameId: String, val triggerName: String) {
        val name: String = video.nameWithoutExtension
        val progressFile = File(video.path + ".progress")
        val audioDoneFile = File(video.path + ".audio-done")
        val audioFile = File(video.path.removeSuffix(".mp4") + ".m4a")
    }

    private val uid: String
        get() = FirebaseAuth.getInstance().currentUser?.uid.toString()

    private val appVersion: String?
        get() = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName

    // --- Audio ---------------------------------------------------------------------------

    /** Extracts and uploads the audio track once per session (skipped on retries). */
    private fun uploadAudioOnce(session: Session) {
        if (session.audioDoneFile.exists()) return
        if (!extractAudioTrack(session.video, session.audioFile)) {
            Log.i(TAG, "${session.video.name} has no audio track")
            session.audioDoneFile.createNewFile()
            return
        }

        val storagePath = FirebaseRefs.Storage.audio(uid, session.name)
        Tasks.await(FirebaseStorage.getInstance().reference.child(storagePath)
                .putFile(Uri.fromFile(session.audioFile)))

        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(session.audioFile.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }

        val doc = hashMapOf(
                "user_id" to FirebaseAuth.getInstance().currentUser?.uid,
                "storagePath" to storagePath,
                "timestamp" to session.startedAt,
                "durationMs" to durationMs,
                "seq_id" to session.seqId,
                "gameId" to session.gameId,
                "triggerName" to session.triggerName,
                "appVersion" to appVersion
        )
        FirebaseRefs.firestore.collection(Collections.AUDIO_RECORDINGS)
                .document("${uid}_${session.name}")
                .set(doc)
                .addOnFailureListener { e -> Log.w(TAG, "Error adding audio document", e) }

        session.audioFile.delete()
        session.audioDoneFile.createNewFile()
        Log.i(TAG, "Uploaded audio $storagePath")
    }

    /** Copies the first audio track of [video] into [out] (MP4/AAC); false if there is none. */
    private fun extractAudioTrack(video: File, out: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 20
            val buffer = ByteBuffer.allocate(maxSize)
            val info = MediaCodec.BufferInfo()

            val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val outTrack = muxer.addTrack(format)
                muxer.start()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime,
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(outTrack, buffer, info)
                    extractor.advance()
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
            return true
        } finally {
            extractor.release()
        }
    }

    // --- Video ---------------------------------------------------------------------------

    private fun processFrames(session: Session) {
        val resumeFrom = session.progressFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        val rotation = videoRotation(session.video)
        val decoder = VideoFrameDecoder(session.video)
        val startedMs = System.currentTimeMillis()

        var firstPtsUs = -1L
        var lastPtsUs = 0L
        var analysedFrames = 0
        var faceCount = 0
        var batch = FirebaseRefs.firestore.batch()
        var batchWrites = 0

        FaceFeatureExtractor(applicationContext).use { extractor ->
            fun checkpoint(nextFrame: Int) {
                if (batchWrites > 0) {
                    batch.commit().addOnFailureListener { e -> Log.w(TAG, "Error adding documents", e) }
                    batch = FirebaseRefs.firestore.batch()
                    batchWrites = 0
                }
                // Resume from the oldest frame whose eye crops are still uploading.
                val safe = minOf(nextFrame, extractor.oldestPendingFrame() ?: nextFrame)
                session.progressFile.writeText(maxOf(safe, resumeFrom).toString())
            }

            decoder.decode { frameIndex, ptsUs, image ->
                if (firstPtsUs < 0) firstPtsUs = ptsUs
                lastPtsUs = ptsUs
                if (frameIndex < resumeFrom || frameIndex % FRAME_STEP != 0) return@decode
                val frameTimeMs = (ptsUs - firstPtsUs) / 1000
                val frameName = "${session.name}_f%05d".format(frameIndex)
                val faces = try {
                    extractor.analyze(InputImage.fromMediaImage(image, rotation),
                            YuvFrameCropper(image, rotation, decoder.isBt709),
                            "${session.video.name}#$frameIndex", frameName, frameIndex)
                } catch (e: Exception) {
                    // e.g. an eye partly outside the frame; skip this frame only.
                    Log.d(TAG, "Frame $frameIndex skipped: $e")
                    JSONArray()
                }
                analysedFrames++
                for (f in 0 until faces.length()) {
                    val doc = featureDocument(faces.getJSONObject(f), session, frameIndex, frameTimeMs)
                    batch.set(FirebaseRefs.firestore.collection(Collections.FEATURES).document("${uid}_${frameName}_$f"), doc)
                    batchWrites++
                    faceCount++
                }
                if (analysedFrames % CHUNK_SIZE == 0) checkpoint(frameIndex + 1)
            }

            extractor.awaitUploads()
            checkpoint(Int.MAX_VALUE)
        }

        val videoSeconds = (lastPtsUs - maxOf(firstPtsUs, 0L)) / 1e6
        val elapsedSeconds = (System.currentTimeMillis() - startedMs) / 1e3
        Log.i(TAG, "Analysed $analysedFrames frames ($faceCount faces) of %.1f s video in %.1f s (%.1fx real time)"
                .format(videoSeconds, elapsedSeconds, if (videoSeconds > 0) elapsedSeconds / videoSeconds else 0.0))
    }

    /** Clockwise rotation (degrees) needed to show the recorded frames upright. */
    private fun videoRotation(video: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            retriever.release()
        }
    }

    /** Same document shape as photo features, plus video frame metadata. */
    private fun featureDocument(face: JSONObject, session: Session, frameIndex: Int, frameTimeMs: Long): HashMap<String, Any> {
        val mapType = object : TypeToken<HashMap<String?, Any?>?>() {}.type
        val doc: HashMap<String, Any> = Gson().fromJson(face.toString(), mapType)
        val timestamp = (session.startedAt + frameTimeMs).toString()

        val metadata = JSONObject()
                .put("timestamp", timestamp)
                .put("seq_id", session.seqId)
                .put("gameId", session.gameId)
                .put("triggerName", session.triggerName)
                .put("appVersion", appVersion)
                .put("source", "video")
                .put("video", session.name)
                .put("frameIndex", frameIndex)
                .put("frameTimeMs", frameTimeMs)
        doc["metadata"] = Gson().fromJson(metadata.toString(), mapType)
        doc["timestamp"] = timestamp
        doc["gameId"] = session.gameId
        FirebaseAuth.getInstance().currentUser?.uid?.let { doc["user_id"] = it }
        return doc
    }

    private fun deleteSessionFiles(video: File) {
        listOf(video, File(video.path + ".progress"), File(video.path + ".audio-done"),
                File(video.path.removeSuffix(".mp4") + ".m4a")).forEach { it.delete() }
    }

    companion object {
        const val TAG = "VideoProcessingWorker"

        // WorkManager input keys and tag; set by CaptureTriggerReceiver.
        const val KEY_VIDEO_PATH = "VIDEO_PATH"
        const val KEY_STARTED_AT = "STARTED_AT"
        const val KEY_SEQ_ID = "SEQ_ID"
        const val KEY_GAME_ID = "GAME_ID"
        const val KEY_TRIGGER_NAME = "TRIGGER_NAME"
        const val WORK_TAG = "video-feature-extraction"

        /**
         * Analyse every Nth frame. 1 = every frame (~30 fps); 3 = ~10 fps, the rate of the
         * earlier photo-based pipeline. Higher values save battery and Firestore writes.
         */
        const val FRAME_STEP = 1

        /** Analysed frames between progress checkpoints / Firestore batch commits. */
        private const val CHUNK_SIZE = 30

        private const val MAX_ATTEMPTS = 5
    }
}
