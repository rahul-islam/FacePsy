package com.rahulislam.facepsy.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat.startForegroundService
import com.rahulislam.facepsy.util.logService
import java.io.IOException
import kotlin.system.exitProcess

/**
 * Process-wide uncaught exception handler that keeps data collection alive:
 * instead of letting the app die, it restarts [SensingService] and exits the process.
 *
 * Installed by [com.rahulislam.facepsy.MainActivity] and [SensingService].
 */
class CrashRestartHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    @SuppressLint("LongLogTag")
    override fun uncaughtException(t: Thread?, ex: Throwable?) {
        Log.e(TAG,
                "*--------------- <APP> just ran into an Unhandled Exception ---------------*")
        Log.e(TAG, "Unhandled Exception: ")

        restartServiceAndExit(context)
    }

    companion object {
        const val TAG = "CrashRestartHandler"

        /**
         * Starts [SensingService] again (API 26+), schedules an alarm with a restart
         * intent one second from now, then terminates the process with exit code 2.
         *
         * Shared by [CrashRestartHandler] and [SensingService.onDestroy].
         */
        fun restartServiceAndExit(context: Context) {
            try {
                val intent = Intent(context, SensingService::class.java).also {
                    it.action = ServiceAction.START.name
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        logService("Starting the service in >=26 Mode")
                        startForegroundService(context, it)
                    }
                    logService("Starting the service in < 26 Mode")
                }

                intent.putExtra("crash", true)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        or Intent.FLAG_ACTIVITY_NEW_TASK)

                // NOTE: legacy behavior, see docs/known-issues.md — this wraps a Service
                // intent in PendingIntent.getActivity().
                val pendingIntent = PendingIntent.getActivity(context, 0, intent, intent.flags)

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent)

                exitProcess(2)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
