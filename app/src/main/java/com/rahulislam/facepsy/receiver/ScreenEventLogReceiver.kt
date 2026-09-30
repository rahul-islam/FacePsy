package com.rahulislam.facepsy.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.rahulislam.facepsy.data.FirebaseRefs
import com.rahulislam.facepsy.data.FirebaseRefs.Collections
import com.rahulislam.facepsy.service.SensingService

/**
 * Logs screen on / screen off / unlock events to Firestore `phoneUsageData` as
 * `{user_id, action, timestamp}`.
 *
 * Registered at runtime by [SensingService] (screen on/off can only be received by
 * runtime-registered receivers) and also declared in the manifest for `USER_PRESENT`.
 */
class ScreenEventLogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "intent")
        val appUsageData = hashMapOf(
                "user_id" to FirebaseAuth.getInstance().currentUser?.uid,
                "action" to intent.action.toString(),
                "timestamp" to SensingService.kronosClock.getCurrentTimeMs()
        )
        FirebaseRefs.firestore.collection(Collections.PHONE_USAGE_DATA)
                .add(appUsageData)
                .addOnSuccessListener { documentReference ->
                    Log.d(TAG, "DocumentSnapshot added with ID: ${documentReference.id}")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Error adding document", e)
                }
    }

    /** Filter used for the runtime registration in [SensingService]. */
    val filter: IntentFilter
        get() {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            return filter
        }

    companion object {
        const val TAG = "ScreenEventLogReceiver"
    }
}
