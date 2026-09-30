package com.rahulislam.facepsy

import android.app.Application
import com.lyft.kronos.AndroidClockFactory
import com.rahulislam.facepsy.service.SensingService

/**
 * Application entry point; runs before any activity, service or receiver.
 *
 * Initializes the shared NTP clock here because [FacePsyAccessibilityService] and the
 * receivers can run before [SensingService] (e.g. right after a reboot) and all of them
 * timestamp records with [SensingService.kronosClock].
 */
class FacePsyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SensingService.kronosClock = AndroidClockFactory.createKronosClock(applicationContext)
        SensingService.kronosClock.syncInBackground()
    }
}
