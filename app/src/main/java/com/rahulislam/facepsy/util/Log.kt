package com.rahulislam.facepsy.util

import android.util.Log

/** Logcat tag shared by the background service, boot receiver and crash handler. */
const val SERVICE_LOG_TAG = "ENDLESS-SERVICE"

/** Debug-logs a service lifecycle message under [SERVICE_LOG_TAG]. */
fun logService(msg: String) {
    Log.d(SERVICE_LOG_TAG, msg)
}
