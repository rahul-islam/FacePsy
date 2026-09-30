package com.rahulislam.facepsy.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Whether the participant's [SensingService] should be running. Persisted so that
 * [com.rahulislam.facepsy.receiver.BootReceiver] can restart it after a reboot.
 */
enum class ServiceState {
    STARTED,
    STOPPED,
}

// Stored values; do not change (existing installs read them back).
private const val PREFS_NAME = "SPYSERVICE_KEY"
private const val KEY_SERVICE_STATE = "SPYSERVICE_STATE"

/** Persists the desired [state] of [SensingService]. */
fun setServiceState(context: Context, state: ServiceState) {
    val sharedPrefs = getPreferences(context)
    sharedPrefs.edit().let {
        it.putString(KEY_SERVICE_STATE, state.name)
        it.apply()
    }
}

/** Returns the last persisted [ServiceState], defaulting to [ServiceState.STOPPED]. */
fun getServiceState(context: Context): ServiceState {
    val sharedPrefs = getPreferences(context)
    val value = sharedPrefs.getString(KEY_SERVICE_STATE, ServiceState.STOPPED.name)
    return ServiceState.valueOf(value)
}

private fun getPreferences(context: Context): SharedPreferences {
    return context.getSharedPreferences(PREFS_NAME, 0)
}
