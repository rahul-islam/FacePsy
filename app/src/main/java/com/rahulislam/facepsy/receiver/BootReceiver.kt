package com.rahulislam.facepsy.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.rahulislam.facepsy.service.SensingService
import com.rahulislam.facepsy.service.ServiceAction
import com.rahulislam.facepsy.service.ServiceState
import com.rahulislam.facepsy.service.getServiceState
import com.rahulislam.facepsy.util.logService

/**
 * Restarts [SensingService] after the device boots, if it was running before
 * (see [com.rahulislam.facepsy.service.ServiceStateStore]). Declared in the manifest.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && getServiceState(context) == ServiceState.STARTED) {
            Intent(context, SensingService::class.java).also {
                it.action = ServiceAction.START.name
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    logService("Starting the service in >=26 Mode from a BroadcastReceiver")
                    context.startForegroundService(it)
                    return
                }
                logService("Starting the service in < 26 Mode from a BroadcastReceiver")
                context.startService(it)
            }
        }
    }
}
