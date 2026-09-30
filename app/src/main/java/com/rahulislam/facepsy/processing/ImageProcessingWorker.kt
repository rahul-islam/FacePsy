package com.rahulislam.facepsy.processing

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
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
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.rahulislam.facepsy.data.FirebaseRefs
import com.rahulislam.facepsy.data.FirebaseRefs.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.collections.HashMap

/**
 * Extracts facial-behavior features from one captured frame and uploads them.
 *
 * Enqueued by [com.rahulislam.facepsy.receiver.CaptureTriggerReceiver] for every frame
 * (input keys `KEY_*` below). For each face ML Kit finds in the image it:
 * 1. runs the `AU_200.tflite` model on a 200x200 grayscale face crop to estimate
 *    12 facial action unit intensities ([ACTION_UNITS]);
 * 2. uploads left/right eye-region PNG crops to Cloud Storage `eyeRegion/{uid}/`;
 * 3. writes one document to Firestore `features` with landmarks, contours, bounding
 *    box, head Euler angles, classification probabilities, AUs and metadata
 *    (see `docs/data-schema.md`).
 * The frame file is deleted after all faces are processed.
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

    /** Returns a desaturated copy of [bmpOriginal]. */
    private fun toGrayscale(bmpOriginal: Bitmap): Bitmap? {
        val height: Int = bmpOriginal.height
        val width: Int = bmpOriginal.width
        val bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmpGrayscale)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val f = ColorMatrixColorFilter(cm)
        paint.colorFilter = f
        c.drawBitmap(bmpOriginal, 0f, 0f, paint)
        return bmpGrayscale
    }

    /**
     * Converts a [AU_MODEL_INPUT_SIZE]² bitmap to the model's float input: one channel
     * (the red byte of each pixel), normalized to roughly [-1, 1].
     */
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer? {
        val inputSize = AU_MODEL_INPUT_SIZE
        val byteBuffer = ByteBuffer.allocateDirect(4 * 1 * inputSize * inputSize * 1)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val value = intValues[pixel++]
                byteBuffer.putFloat(((value shr 16 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
            }
        }
        return byteBuffer
    }

    /**
     * Runs face detection on the image at [imageUriInput] and returns one JSON object per
     * face. Deletes the image on success; on any exception the image is kept and the
     * exception propagates.
     */
    @SuppressLint("LongLogTag")
    private suspend fun extractFeatures(imageUriInput: String): JSONArray {
        val pathSplit: List<String> = imageUriInput.split("/")
        val imageFileName = pathSplit[pathSplit.size - 1]
        Log.i(TAG, imageFileName)

        val highAccuracyOpts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()

        val detector = FaceDetection.getClient(highAccuracyOpts)

        Log.d(TAG, imageUriInput)
        val image: InputImage = InputImage.fromFilePath(this.applicationContext, Uri.fromFile(File(imageUriInput)))

        val bitmapGray = image.bitmapInternal?.let { toGrayscale(it) }

        val jsonArray = JSONArray()
        val result = detector.process(image)

        Tasks.await(result)
        for (face in result.result!!) {
            val matrix = Matrix()
            val auObj = computeActionUnits(face, bitmapGray, matrix)

            val i: Int = imageFileName.lastIndexOf(".")
            val imageBaseName = imageFileName.substring(0, i)
            uploadEyeRegion(image, face, FaceContour.LEFT_EYE, "LEFT", imageBaseName, matrix)
            uploadEyeRegion(image, face, FaceContour.RIGHT_EYE, "RIGHT", imageBaseName, matrix)

            val jsonFace = buildFaceJson(face, imageUriInput, auObj)
            Log.d(TAG, jsonArray.toString())
            jsonArray.put(jsonFace)

            // To also upload the full frame, put image.bitmapInternal (or the file at
            // imageUriInput) to Storage under "faceImages/$uid/$imageBaseName.jpg" here.
        }

        val file = File(imageUriInput)
        file.delete()

        Log.d(TAG, "Currently Processing\t" + imageUriInput)

        return jsonArray
    }

    /**
     * Crops [face] from [bitmapGray], resizes it to [AU_MODEL_INPUT_SIZE]² and runs the
     * action-unit model. Returns `{AU01: float, ...}`, or an empty object if the gray
     * bitmap is missing, the face box is out of bounds, or the model cannot be loaded.
     */
    private fun computeActionUnits(face: Face, bitmapGray: Bitmap?, matrix: Matrix): JSONObject {
        val auObj = JSONObject()
        if (bitmapGray != null) {
            Log.i(TAG, bitmapGray.width.toString() + " " + bitmapGray.height.toString() + " " + (face.boundingBox.left + face.boundingBox.width()).toString() + " " + face.boundingBox.top.toString() + " " + face.boundingBox.width().toString() + " " + face.boundingBox.height().toString())
            if ((face.boundingBox.top + face.boundingBox.height() <= bitmapGray.height) && (face.boundingBox.left + face.boundingBox.width() <= bitmapGray.width)) {
                val cropFace = Bitmap.createBitmap(bitmapGray, face.boundingBox.left, face.boundingBox.top, face.boundingBox.width(), face.boundingBox.height(), matrix, true)

                val scaledBitmap = Bitmap.createScaledBitmap(cropFace, AU_MODEL_INPUT_SIZE, AU_MODEL_INPUT_SIZE, true)
                val modelInput = convertBitmapToByteBuffer(scaledBitmap)

                val probabilityBuffer: TensorBuffer = TensorBuffer.createFixedSize(intArrayOf(1, ACTION_UNITS.size), DataType.FLOAT32)

                try {
                    // NOTE: the model is loaded once per face (see docs/known-issues.md).
                    val tfliteModel = FileUtil.loadMappedFile(applicationContext, AU_MODEL_ASSET)
                    val tflite = Interpreter(tfliteModel)

                    tflite.run(modelInput, probabilityBuffer.getBuffer())

                    ACTION_UNITS.forEachIndexed { index, auName ->
                        auObj.put(auName, probabilityBuffer.getFloatValue(index))
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    Log.e(TAG, "Error reading model", e)
                }
            }
        }
        return auObj
    }

    /**
     * Crops the eye described by [contourType] (with [EYE_CROP_MARGIN_PX] of margin on
     * each side) from the full-color frame and uploads it as a PNG to
     * `eyeRegion/{uid}/{imageBaseName}_{side}.png`. Throws if the contour is missing
     * or the crop falls outside the image.
     */
    private fun uploadEyeRegion(image: InputImage, face: Face, contourType: Int, side: String, imageBaseName: String, matrix: Matrix) {
        val eyeContour = face.getContour(contourType)?.points!!

        val left = eyeContour[0].x.toInt() - EYE_CROP_MARGIN_PX
        val top = eyeContour[4].y.toInt() - EYE_CROP_MARGIN_PX
        val width = eyeContour[8].x.toInt() - eyeContour[0].x.toInt() + 2 * EYE_CROP_MARGIN_PX
        val height = eyeContour[12].y.toInt() - eyeContour[4].y.toInt() + 2 * EYE_CROP_MARGIN_PX

        val eyeCrop = Bitmap.createBitmap(image.bitmapInternal!!, left, top, width, height, matrix, true)

        val uid = FirebaseAuth.getInstance().currentUser?.uid.toString()
        val eyeRegionRef = FirebaseStorage.getInstance().reference
                .child(FirebaseRefs.Storage.eyeRegion(uid, imageBaseName, side))

        val baos = ByteArrayOutputStream()
        eyeCrop.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val data = baos.toByteArray()

        eyeRegionRef.putBytes(data)
                .addOnFailureListener {
                    Log.i(TAG, "FAIL")
                }.addOnSuccessListener {
                    Log.i(TAG, FirebaseAuth.getInstance().currentUser?.uid.toString())
                }
    }

    /** Serializes ML Kit's results for [face] plus the action units [auObj]. */
    private fun buildFaceJson(face: Face, imageUriInput: String, auObj: JSONObject): JSONObject {
        val jsonFace = JSONObject()

        val jsonLandMarksArray = JSONArray()
        for (landmark in face.allLandmarks) {
            val jsonLandMarkObj = JSONObject()
            jsonLandMarkObj.put("type", landmark.landmarkType)
            jsonLandMarkObj.put("x", landmark.position.x)
            jsonLandMarkObj.put("y", landmark.position.y)
            jsonLandMarksArray.put(jsonLandMarkObj)
        }

        val jsonContoursArray = JSONArray()
        for (contour in face.allContours) {
            for (point in contour.points) {
                val jsonContoursObj = JSONObject()
                jsonContoursObj.put("x", point.x)
                jsonContoursObj.put("y", point.y)
                jsonContoursArray.put(jsonContoursObj)
            }
        }

        Log.d(TAG, jsonContoursArray.toString())

        val jsonHeadEulerObj = JSONObject()
        jsonHeadEulerObj.put("X", face.headEulerAngleX)
        jsonHeadEulerObj.put("Y", face.headEulerAngleY) // Head is rotated to the right rotY degrees
        jsonHeadEulerObj.put("Z", face.headEulerAngleZ) // Head is tilted sideways rotZ degrees

        val jsonClassificationObj = JSONObject()
        jsonClassificationObj.put("leftEyeOpenProbability", face.leftEyeOpenProbability)
        jsonClassificationObj.put("rightEyeOpenProbability", face.rightEyeOpenProbability)
        jsonClassificationObj.put("smilingProbability", face.smilingProbability)

        jsonFace.put("fileName", imageUriInput)
        jsonFace.put("landmarks", jsonLandMarksArray)
        jsonFace.put("contours", jsonContoursArray)
        jsonFace.put("boundingBox", face.boundingBox.flattenToString())
        jsonFace.put("headEulerAngle", jsonHeadEulerObj)
        jsonFace.put("classification", jsonClassificationObj)
        jsonFace.put("au", auObj)
        return jsonFace
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

        /** TFLite action-unit model bundled in `src/main/assets`. */
        const val AU_MODEL_ASSET = "AU_200.tflite"

        /** Width and height of the square face crop the AU model expects. */
        const val AU_MODEL_INPUT_SIZE = 200
        private const val IMAGE_MEAN = 128
        private const val IMAGE_STD = 128.0f

        /** Output order of the AU model (FACS action unit codes). */
        val ACTION_UNITS = arrayOf("AU01", "AU02", "AU04", "AU06", "AU07", "AU10", "AU12", "AU14", "AU15", "AU17", "AU23", "AU24")

        /** Padding added around each side of the eye contour when cropping. */
        const val EYE_CROP_MARGIN_PX = 20
    }
}
