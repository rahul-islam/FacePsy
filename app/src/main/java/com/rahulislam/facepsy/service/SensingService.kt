package com.rahulislam.facepsy.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.view.accessibility.AccessibilityEventCompat
import androidx.core.view.accessibility.AccessibilityManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.ktx.Firebase
import com.lyft.kronos.KronosClock
import com.rahulislam.facepsy.MainActivity
import com.rahulislam.facepsy.R
import com.rahulislam.facepsy.data.FirebaseRefs
import com.rahulislam.facepsy.data.FirebaseRefs.Collections
import com.rahulislam.facepsy.data.FirebaseRefs.ConfigDocs
import com.rahulislam.facepsy.data.FirebaseRefs.RealtimeDb
import com.rahulislam.facepsy.receiver.CaptureTriggerReceiver
import com.rahulislam.facepsy.receiver.ScreenEventLogReceiver
import com.rahulislam.facepsy.util.logService
import kotlinx.coroutines.*
import kotlin.collections.HashMap

/**
 * Long-running foreground service that keeps passive data collection alive.
 *
 * Started by [MainActivity] after sign-in, by
 * [com.rahulislam.facepsy.receiver.BootReceiver] after a reboot, and by
 * [CrashRestartHandler] after a crash. While running it:
 * - mirrors the study configuration from Firestore `config/ *` into the companion
 *   fields ([appTrackingList], [triggerDuration], [stroopConfig], [surveyConfig]);
 * - keeps an NTP-synced [kronosClock] used to timestamp every record;
 * - publishes presence to Realtime Database `/status/{uid}` and `users/{uid}.online`;
 * - registers [CaptureTriggerReceiver] and [ScreenEventLogReceiver] at runtime;
 * - holds a partial wake lock so collection is not paused by Doze.
 */
class SensingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false

    private lateinit var captureTriggerReceiver: CaptureTriggerReceiver
    private lateinit var screenEventLogReceiver: ScreenEventLogReceiver

    override fun onBind(intent: Intent): IBinder? {
        logService("Some component want to bind with the service")
        // We don't provide binding, so return null
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logService("onStartCommand executed with startId: $startId")
        if (intent != null) {
            val action = intent.action
            logService("using an intent with action $action")
            when (action) {
                ServiceAction.START.name -> startService()
                ServiceAction.STOP.name -> stopService()
                else -> logService("This should never happen. No action in the received intent")
            }
        } else {
            // Restarted by the system after the process died (START_STICKY). Set everything up
            // again; otherwise the capture receivers would stay unregistered.
            logService("with a null intent. It has been probably restarted by the system.")
            startService()
        }
        // by returning this we make sure the service is restarted if the system kills the service
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        logService("The service has been created".toUpperCase())
        val notification = createNotification()
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)

        notifyIfAccessibilityDisabled(applicationContext)

        listenToConfig(ConfigDocs.TRIGGERS) { snapshot ->
            Log.d(TAG, "Current data: ${snapshot.data?.get("apps")?.javaClass?.kotlin}")
            val apps: ArrayList<Map<String, String>> = snapshot.data?.get("apps") as ArrayList<Map<String, String>>
            for (app in apps) {
                val appTrackingListCopy = appTrackingList.toMutableSet()
                if (app["enable"] as Boolean) {
                    app["packageName"]?.let { appTrackingListCopy.add(it) }
                } else {
                    app["packageName"]?.let { appTrackingListCopy.remove(it) }
                }
                appTrackingList = appTrackingListCopy.toTypedArray()
            }
        }

        listenToConfig(ConfigDocs.TRIGGER_DURATION) { snapshot ->
            triggerDuration = snapshot.data as HashMap<String, Long>
        }

        listenToConfig(ConfigDocs.STROOP_TASK) { snapshot ->
            stroopConfig = snapshot.data as HashMap<String, Int>
            Log.i(TAG, stroopConfig.toString())
        }

        listenToConfig(ConfigDocs.SURVEY) { snapshot ->
            surveyConfig = snapshot.data as HashMap<String, String>
            Log.i(TAG, surveyConfig.toString())
        }

    }

    /**
     * Subscribes to live updates of `config/[docId]` and calls [onData] with every
     * snapshot that exists. Listen errors and missing documents are only logged.
     */
    private fun listenToConfig(docId: String, onData: (DocumentSnapshot) -> Unit) {
        FirebaseRefs.firestore.collection(Collections.CONFIG).document(docId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    onData(snapshot)
                } else {
                    Log.d(TAG, "Current data: null")
                }
            }
    }

    /**
     * Marks the participant as `destroyed` in Realtime Database, then immediately
     * schedules a restart and kills the process (see [CrashRestartHandler]).
     */
    override fun onDestroy() {
        super.onDestroy()
        logService("The service has been destroyed".toUpperCase())
        Toast.makeText(this, "Service destroyed", Toast.LENGTH_SHORT).show()

        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            Firebase.database.getReference(RealtimeDb.status(user.uid)).setValue("destroyed")
        }

        CrashRestartHandler.restartServiceAndExit(this.baseContext)
    }

    private fun startService() {
        if (isServiceStarted) return
        logService("Starting the foreground service task")
        Toast.makeText(this, "FacePsy is running on background.", Toast.LENGTH_SHORT).show()
        isServiceStarted = true
        setServiceState(this, ServiceState.STARTED)

        // we need this lock so our service gets not affected by Doze Mode
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                    acquire()
                }
            }

        // Heartbeat loop: logs once per minute while the service is started.
        GlobalScope.launch(Dispatchers.IO) {
            while (isServiceStarted) {
                launch(Dispatchers.IO) {
                    logService("Service heartbeat")
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
            logService("End of the loop for the service")
        }

        notifyIfAccessibilityDisabled(applicationContext)

        Thread.setDefaultUncaughtExceptionHandler(CrashRestartHandler(this))

        trackPresence()

        captureTriggerReceiver = CaptureTriggerReceiver()
        registerReceiver(captureTriggerReceiver, captureTriggerReceiver.filter)

        screenEventLogReceiver = ScreenEventLogReceiver()
        registerReceiver(screenEventLogReceiver, screenEventLogReceiver.filter)
    }

    /**
     * On every (re)connection to Realtime Database: arranges for `/status/{uid}` to be
     * set to `offline` on disconnect, then sets it to `online` and merges
     * `{online: true}` into Firestore `users/{uid}`.
     */
    private fun trackPresence() {
        val user = FirebaseAuth.getInstance().currentUser
        val realtimeDb = Firebase.database

        val usersRef = FirebaseRefs.firestore.collection(Collections.USERS)
        val onlineRef = realtimeDb.getReference(RealtimeDb.INFO_CONNECTED)

        onlineRef.addValueEventListener(object : ValueEventListener {

            override fun onDataChange(dataSnapshot: DataSnapshot) {
                // Called once with the initial value and again whenever the connection state changes.
                Log.d(TAG, "Value is:")
                if (user != null) {
                    Log.i(TAG, "Presence: user ${user.uid}")
                    realtimeDb.getReference(RealtimeDb.status(user.uid)).onDisconnect().setValue("offline").onSuccessTask {
                        Log.i(TAG, "Presence: onDisconnect handler registered")
                        val data = hashMapOf("online" to true)
                        usersRef.document("${user.uid}")
                                .set(data, SetOptions.merge())
                        realtimeDb.getReference(RealtimeDb.status(user.uid)).setValue("online")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Failed to read value
                Log.w(TAG, "Failed to read value.", error.toException())
            }
        })
    }

    private fun stopService() {
        logService("Stopping the foreground service")
        Toast.makeText(this, "FacePsy background service stopping", Toast.LENGTH_SHORT).show()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            logService("Service stopped without being started: ${e.message}")
        }
        isServiceStarted = false
        setServiceState(this, ServiceState.STOPPED)
    }

    /** Builds the persistent "Data collection is active" notification (creating its channel on API 26+). */
    private fun createNotification(): Notification {
        // depending on the Android API that we're dealing with we will have
        // to use a specific method to create the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Endless Service notifications channel",
                NotificationManager.IMPORTANCE_HIGH
            ).let {
                it.description = "Endless Service channel"
                it.enableLights(true)
                it.lightColor = Color.RED
                it.enableVibration(false)
                it.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
                it
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, 0)
        }

        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(
            this,
            NOTIFICATION_CHANNEL_ID
        ) else Notification.Builder(this)

        return builder
            .setContentTitle("FacePsy")
            .setContentText("Data collection is active")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setTicker("Please do not close this")
            .setPriority(Notification.PRIORITY_HIGH) // for under android 26 compatibility
            .build()
    }

    /**
     * Returns true if any accessibility service of this package is enabled. Checks the
     * secure setting first, then falls back to two AccessibilityManager APIs.
     */
    @Synchronized
    private fun isAccessibilityEnabled(context: Context): Boolean {
        var enabled = false
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

        // Try to fetch active accessibility services directly from Android OS database instead of broken API...
        val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (settingValue != null) {
            if (settingValue.contains(context.packageName)) {
                enabled = true
            }
        }
        if (!enabled) {
            try {
                val enabledServices = AccessibilityManagerCompat.getEnabledAccessibilityServiceList(accessibilityManager, AccessibilityEventCompat.TYPES_ALL_MASK)
                enabled = enabledServices.any { it.id.contains(context.packageName) }
            } catch (e: NoSuchMethodError) {
            }
        }
        if (!enabled) {
            try {
                val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityEvent.TYPES_ALL_MASK)
                enabled = enabledServices.any { it.id.contains(context.packageName) }
            } catch (e: NoSuchMethodError) {
            }
        }

        Log.i(TAG, enabled.toString())
        return enabled
    }

    /**
     * If [com.rahulislam.facepsy.FacePsyAccessibilityService] is not enabled, posts a
     * notification that opens the accessibility settings. Returns whether it is enabled.
     */
    @Synchronized
    fun notifyIfAccessibilityDisabled(c: Context): Boolean {
        if (!isAccessibilityEnabled(c)) {
            val builder = NotificationCompat.Builder(c, NOTIFICATION_CHANNEL_ID)
            builder.setSmallIcon(R.mipmap.ic_launcher)
            builder.setContentTitle("Please enable FacePsy")
            builder.setContentText("Tap here to activate accessibility service")
            builder.setAutoCancel(true)
            builder.setOnlyAlertOnce(true) // notify the user only once
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setChannelId(NOTIFICATION_CHANNEL_ID)
            val accessibilitySettings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            accessibilitySettings.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val clickIntent = PendingIntent.getActivity(c, 0, accessibilitySettings, PendingIntent.FLAG_UPDATE_CURRENT)
            builder.setContentIntent(clickIntent)
            val notificationManager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(ACCESSIBILITY_NOTIFICATION_ID, builder.build())
            return false
        }
        return true
    }

    companion object {
        const val TAG = "SensingService"

        // Persisted/visible identifiers kept from the original EndlessService; do not change.
        private const val NOTIFICATION_CHANNEL_ID = "ENDLESS SERVICE CHANNEL"
        private const val WAKE_LOCK_TAG = "EndlessService::lock"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val ACCESSIBILITY_NOTIFICATION_ID = 42

        private const val HEARTBEAT_INTERVAL_MS = 1L * 60 * 1000

        /** Package names whose launch triggers a capture (from `config/triggers`). */
        var appTrackingList = arrayOf<String>()

        /** Capture duration in ms per trigger (from `config/triggerDuration`). */
        var triggerDuration = HashMap<String, Long>()

        /** Stroop task settings, e.g. `rounds` (from `config/stroopTask`). */
        var stroopConfig = HashMap<String, Int>()

        /** Survey links `preLink` / `postLink` (from `config/survey`). */
        var surveyConfig = HashMap<String, String>()

        /**
         * NTP-synced clock used for every stored timestamp. Initialized in
         * [com.rahulislam.facepsy.FacePsyApplication.onCreate], before any component runs.
         */
        lateinit var kronosClock: KronosClock
    }
}
