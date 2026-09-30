package com.rahulislam.facepsy.processing

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.rahulislam.facepsy.data.FirebaseRefs
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Receives the PNG eye-region crops produced by [FaceFeatureExtractor]. */
interface EyeCropSink {
    /**
     * @param imageBaseName name of the photo or video frame the crop came from.
     * @param faceIndex index of the face in that image.
     * @param side `LEFT` or `RIGHT`.
     */
    fun add(imageBaseName: String, faceIndex: Int, side: String, png: ByteArray)
}

/**
 * Photos: uploads each crop to `eyeRegion/{uid}/{imageBaseName}_{side}.png` (the original
 * layout). Call [awaitUploads] before finishing.
 */
class DirectUploadEyeCropSink : EyeCropSink {
    private val pending = ArrayList<Task<*>>()

    override fun add(imageBaseName: String, faceIndex: Int, side: String, png: ByteArray) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.toString()
        pending.add(FirebaseStorage.getInstance().reference
                .child(FirebaseRefs.Storage.eyeRegion(uid, imageBaseName, side))
                .putBytes(png)
                .addOnFailureListener { Log.i(TAG, "FAIL") }
                .addOnSuccessListener { Log.i(TAG, FirebaseAuth.getInstance().currentUser?.uid.toString()) })
    }

    fun awaitUploads() {
        for (task in pending) {
            try {
                Tasks.await(task)
            } catch (e: Exception) {
                Log.i(TAG, "FAIL")
            }
        }
        pending.clear()
    }

    companion object {
        private const val TAG = "EyeCropSink"
    }
}

/**
 * Video: collects all crops of a session into one local zip (entries
 * `{imageBaseName}_{faceIndex}_{side}.png`), uploaded once when the session is done.
 * PNGs are already compressed, so entries are stored without extra compression.
 */
class ZipEyeCropSink(val file: File) : EyeCropSink, Closeable {
    private val zip = ZipOutputStream(FileOutputStream(file)).apply { setLevel(Deflater.NO_COMPRESSION) }

    var count = 0
        private set

    override fun add(imageBaseName: String, faceIndex: Int, side: String, png: ByteArray) {
        zip.putNextEntry(ZipEntry("${imageBaseName}_${faceIndex}_$side.png"))
        zip.write(png)
        zip.closeEntry()
        count++
    }

    override fun close() {
        zip.close()
    }
}
