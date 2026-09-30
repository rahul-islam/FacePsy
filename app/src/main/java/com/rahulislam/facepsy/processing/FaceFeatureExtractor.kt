package com.rahulislam.facepsy.processing

import android.content.Context
import android.graphics.*
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.rahulislam.facepsy.data.FirebaseRefs
import kotlin.collections.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Per-image facial feature extraction shared by [ImageProcessingWorker] (photos) and
 * [VideoProcessingWorker] (video frames), so both produce identical feature JSON.
 *
 * For each face ML Kit finds it:
 * 1. runs the `AU_200.tflite` model (LiteRT) on a 200x200 grayscale face crop to
 *    estimate 12 facial action unit intensities ([ACTION_UNITS]);
 * 2. uploads left/right eye-region PNG crops to Cloud Storage `eyeRegion/{uid}/`;
 * 3. returns landmarks, contours, bounding box, head Euler angles, classification
 *    probabilities and AUs as one JSON object (see `docs/data-schema.md`).
 *
 * Create one instance per worker run and [close] it: the detector and the AU model are
 * loaded once and reused for every image.
 */
class FaceFeatureExtractor(private val context: Context) : Closeable {

    private val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .build())

    /** Loaded on first use; null if the model can't be read (AUs are then left empty). */
    private val auModel: Interpreter? by lazy {
        try {
            Interpreter(FileUtil.loadMappedFile(context, AU_MODEL_ASSET))
        } catch (e: IOException) {
            Log.e(TAG, "Error reading model", e)
            null
        }
    }

    /** In-flight eye-crop uploads, oldest first, with the frame they belong to. */
    private val pendingUploads = ArrayDeque<Pair<Int, Task<*>>>()

    /**
     * Detects faces in [image] and returns one feature JSON object per face.
     *
     * @param frame the same frame as upright full-color crops, for the face and eye crops.
     * @param sourceName stored as `fileName` in the feature JSON.
     * @param imageBaseName used to name the eye crops (`{imageBaseName}_LEFT.png`).
     * @param frameIndex tags the eye-crop uploads, see [oldestPendingFrame].
     * Throws if detection fails or an eye contour is missing / outside the frame.
     */
    fun analyze(image: InputImage, frame: FrameCropper, sourceName: String, imageBaseName: String, frameIndex: Int = 0): JSONArray {
        val faces = Tasks.await(detector.process(image))

        val jsonArray = JSONArray()
        for (face in faces) {
            val auObj = computeActionUnits(face, frame)
            uploadEyeRegion(frame, face, FaceContour.LEFT_EYE, "LEFT", imageBaseName, frameIndex)
            uploadEyeRegion(frame, face, FaceContour.RIGHT_EYE, "RIGHT", imageBaseName, frameIndex)
            jsonArray.put(buildFaceJson(face, sourceName, auObj))
        }
        throttleUploads()
        return jsonArray
    }

    /** Blocks only while more than [MAX_UPLOADS_IN_FLIGHT] eye crops are uploading. */
    private fun throttleUploads() {
        dropFinishedUploads()
        while (pendingUploads.size > MAX_UPLOADS_IN_FLIGHT) {
            awaitQuietly(pendingUploads.removeFirst().second)
        }
    }

    /** Waits until every eye-crop upload started so far has finished (success or not). */
    fun awaitUploads() {
        while (pendingUploads.isNotEmpty()) awaitQuietly(pendingUploads.removeFirst().second)
    }

    /** Frame index of the oldest eye-crop upload still running, or null if none. */
    fun oldestPendingFrame(): Int? {
        dropFinishedUploads()
        return pendingUploads.firstOrNull()?.first
    }

    private fun dropFinishedUploads() {
        while (pendingUploads.isNotEmpty() && pendingUploads.first().second.isComplete) pendingUploads.removeFirst()
    }

    private fun awaitQuietly(task: Task<*>) {
        try {
            Tasks.await(task)
        } catch (e: Exception) {
            Log.i(TAG, "FAIL")
        }
    }

    override fun close() {
        detector.close()
        auModel?.close()
    }

    /** Returns a desaturated copy of [bmpOriginal]. */
    private fun toGrayscale(bmpOriginal: Bitmap): Bitmap {
        val bmpGrayscale = Bitmap.createBitmap(bmpOriginal.width, bmpOriginal.height, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        Canvas(bmpGrayscale).drawBitmap(bmpOriginal, 0f, 0f, paint)
        return bmpGrayscale
    }

    /**
     * Converts a [AU_MODEL_INPUT_SIZE]² bitmap to the model's float input: one channel
     * (the red byte of each pixel), normalized to roughly [-1, 1].
     */
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
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
     * Crops [face] from [frame], converts it to grayscale, resizes it to
     * [AU_MODEL_INPUT_SIZE]² and runs the action-unit model. Returns `{AU01: float, ...}`,
     * or an empty object if the face box is out of bounds or the model can't be loaded.
     * (Cropping before the grayscale conversion gives the same pixels as converting the
     * whole frame first, since the conversion is per pixel.)
     */
    private fun computeActionUnits(face: Face, frame: FrameCropper): JSONObject {
        val auObj = JSONObject()
        val box = face.boundingBox
        Log.i(TAG, "${frame.width} ${frame.height} ${box.left + box.width()} ${box.top} ${box.width()} ${box.height()}")
        if (box.left >= 0 && box.top >= 0 && box.top + box.height() <= frame.height && box.left + box.width() <= frame.width) {
            val cropFace = toGrayscale(frame.crop(box))
            val scaledBitmap = Bitmap.createScaledBitmap(cropFace, AU_MODEL_INPUT_SIZE, AU_MODEL_INPUT_SIZE, true)
            val model = auModel ?: return auObj

            val probabilityBuffer = TensorBuffer.createFixedSize(intArrayOf(1, ACTION_UNITS.size), DataType.FLOAT32)
            model.run(convertBitmapToByteBuffer(scaledBitmap), probabilityBuffer.buffer)
            ACTION_UNITS.forEachIndexed { index, auName ->
                auObj.put(auName, probabilityBuffer.getFloatValue(index))
            }
        }
        return auObj
    }

    /**
     * Crops the eye described by [contourType] (with [EYE_CROP_MARGIN_PX] of margin on
     * each side) from [frame] and starts uploading it as a PNG to
     * `eyeRegion/{uid}/{imageBaseName}_{side}.png`. Throws if the contour is missing or
     * the crop falls outside the frame.
     */
    private fun uploadEyeRegion(frame: FrameCropper, face: Face, contourType: Int, side: String, imageBaseName: String, frameIndex: Int) {
        val eyeContour = face.getContour(contourType)?.points!!

        val left = eyeContour[0].x.toInt() - EYE_CROP_MARGIN_PX
        val top = eyeContour[4].y.toInt() - EYE_CROP_MARGIN_PX
        val width = eyeContour[8].x.toInt() - eyeContour[0].x.toInt() + 2 * EYE_CROP_MARGIN_PX
        val height = eyeContour[12].y.toInt() - eyeContour[4].y.toInt() + 2 * EYE_CROP_MARGIN_PX

        val eyeCrop = frame.crop(Rect(left, top, left + width, top + height))

        val uid = FirebaseAuth.getInstance().currentUser?.uid.toString()
        val eyeRegionRef = FirebaseStorage.getInstance().reference
                .child(FirebaseRefs.Storage.eyeRegion(uid, imageBaseName, side))

        val baos = ByteArrayOutputStream()
        eyeCrop.compress(Bitmap.CompressFormat.PNG, 100, baos)

        pendingUploads.addLast(frameIndex to eyeRegionRef.putBytes(baos.toByteArray())
                .addOnFailureListener { Log.i(TAG, "FAIL") }
                .addOnSuccessListener { Log.i(TAG, FirebaseAuth.getInstance().currentUser?.uid.toString()) })
    }

    /** Serializes ML Kit's results for [face] plus the action units [auObj]. */
    private fun buildFaceJson(face: Face, sourceName: String, auObj: JSONObject): JSONObject {
        val jsonLandMarksArray = JSONArray()
        for (landmark in face.allLandmarks) {
            jsonLandMarksArray.put(JSONObject()
                    .put("type", landmark.landmarkType)
                    .put("x", landmark.position.x)
                    .put("y", landmark.position.y))
        }

        val jsonContoursArray = JSONArray()
        for (contour in face.allContours) {
            for (point in contour.points) {
                jsonContoursArray.put(JSONObject().put("x", point.x).put("y", point.y))
            }
        }

        val jsonHeadEulerObj = JSONObject()
                .put("X", face.headEulerAngleX)
                .put("Y", face.headEulerAngleY) // Head is rotated to the right rotY degrees
                .put("Z", face.headEulerAngleZ) // Head is tilted sideways rotZ degrees

        val jsonClassificationObj = JSONObject()
                .put("leftEyeOpenProbability", face.leftEyeOpenProbability)
                .put("rightEyeOpenProbability", face.rightEyeOpenProbability)
                .put("smilingProbability", face.smilingProbability)

        return JSONObject()
                .put("fileName", sourceName)
                .put("landmarks", jsonLandMarksArray)
                .put("contours", jsonContoursArray)
                .put("boundingBox", face.boundingBox.flattenToString())
                .put("headEulerAngle", jsonHeadEulerObj)
                .put("classification", jsonClassificationObj)
                .put("au", auObj)
    }

    companion object {
        private const val TAG = "FaceFeatureExtractor"

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

        /** Eye-crop uploads allowed to run in the background before analysis waits. */
        private const val MAX_UPLOADS_IN_FLIGHT = 40
    }
}
