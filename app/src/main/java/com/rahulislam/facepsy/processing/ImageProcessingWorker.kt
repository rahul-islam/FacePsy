package com.rahulislam.facepsy.processing

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.rahulislam.facepsy.data.FirebaseRefs
import com.rahulislam.facepsy.data.FirebaseRefs.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.collections.HashMap

/**
 * Extracts facial-behavior features from one captured **photo** and uploads them.
 *
 * Photos were the capture format before video; this worker is kept so photo jobs that are
 * still queued when a participant updates the app get processed. New captures are video
 * ([VideoProcessingWorker]). The per-face work is done by [FaceFeatureExtractor]; each face
 * becomes one Firestore `features` document (see `docs/data-schema.md`).
 *
 * The fully qualified class name is persisted by WorkManager for queued work:
 * **do not rename or move this class.**
 */
class ImageProcessingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override val coroutineContext = Dispatchers.IO

    override suspend fun doWork(): Result = coroutineScope {

        val imageUriInput =
                inputData.getString(KEY_IMAGE_URI) ?: return@coroutineScope Result.failure()
        val timestamp =
                inputData.getString(KEY_TIMESTAMP) ?: return@coroutineScope Result.failure()
        val seqId =
                inputData.getString(KEY_SEQ_ID) ?: return@coroutineScope Result.failure()
        val gameId =
                inputData.getString(KEY_GAME_ID) ?: return@coroutineScope Result.failure()
        val triggerName =
                inputData.getString(KEY_TRIGGER_NAME) ?: return@coroutineScope Result.failure()

        val resultJsonArray: JSONArray
        try {
            val job = async {
                extractFeatures(imageUriInput)
            }
            resultJsonArray = job.await()

            for (i in 0 until resultJsonArray.length()) {
                val jsonObject = resultJsonArray.getJSONObject(i)
                val retMap: HashMap<String, Any> = Gson().fromJson(
                        jsonObject.toString(), object : TypeToken<HashMap<String?, Any?>?>() {}.type
                )

                val metadataJson = JSONObject()
                metadataJson.put("timestamp", timestamp)
                metadataJson.put("seq_id", seqId)
                metadataJson.put("gameId", gameId)
                metadataJson.put("triggerName", triggerName)
                metadataJson.put("appVersion", applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName)

                retMap["metadata"] = Gson().fromJson(
                        metadataJson.toString(), object : TypeToken<HashMap<String?, Any?>?>() {}.type
                )
                retMap["timestamp"] = timestamp
                retMap["gameId"] = gameId
                FirebaseAuth.getInstance().currentUser?.uid?.let { retMap.put("user_id", it) }

                // Add a new document with a generated ID
                FirebaseRefs.firestore.collection(Collections.FEATURES)
                        .add(retMap)
                        .addOnSuccessListener { documentReference ->
                            Log.d(TAG, "DocumentSnapshot added with ID: ${documentReference.id}")
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "Error adding document", e)
                        }
            }

        } catch (e: Exception) {
            // NOTE: legacy behavior, see docs/known-issues.md — this value is discarded,
            // so the worker always reports success.
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }

        Result.success()
    }

    /**
     * Runs [FaceFeatureExtractor] on the image at [imageUriInput] and returns one JSON
     * object per face. Deletes the image on success; on any exception the image is kept
     * and the exception propagates.
     */
    private fun extractFeatures(imageUriInput: String): JSONArray {
        val imageFileName = imageUriInput.substringAfterLast("/")
        Log.i(TAG, imageFileName)
        val imageBaseName = imageFileName.substring(0, imageFileName.lastIndexOf("."))

        val image = InputImage.fromFilePath(applicationContext, Uri.fromFile(File(imageUriInput)))
        val eyeCrops = DirectUploadEyeCropSink()
        val jsonArray = FaceFeatureExtractor(applicationContext, eyeCrops).use { extractor ->
            extractor.analyze(image, BitmapFrameCropper(image.bitmapInternal!!), imageUriInput, imageBaseName)
        }
        eyeCrops.awaitUploads()

        File(imageUriInput).delete()
        Log.d(TAG, "Currently Processing\t" + imageUriInput)
        return jsonArray
    }

    companion object {
        const val TAG = "ImageProcessingWorker"

        // WorkManager input keys and tag; set by CaptureTriggerReceiver. Do not change.
        const val KEY_IMAGE_URI = "IMAGE_URI"
        const val KEY_TIMESTAMP = "TIMESTAMP"
        const val KEY_SEQ_ID = "SEQ_ID"
        const val KEY_GAME_ID = "GAME_ID"
        const val KEY_TRIGGER_NAME = "TRIGGER_NAME"
        const val WORK_TAG = "feature-extraction"
    }
}
