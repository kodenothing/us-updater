package com.maenalyn.couples

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * User-started location foreground service. It runs only after a partner explicitly
 * enables screen-off sharing from the visible map screen and keeps a persistent
 * notification with a Stop action while location is being shared.
 */
class LocationSharingService : Service() {
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var activeCode: String? = null
    private var activeUid: String? = null
    private var coupleListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var lowPowerRequest = false
    private val serviceHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSharingAndSelf(disableSharing = true)
                return START_NOT_STICKY
            }
            ACTION_STOP_BACKGROUND -> {
                stopSharingAndSelf(disableSharing = false)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val code = intent.getStringExtra(EXTRA_CODE)
                val uid = intent.getStringExtra(EXTRA_UID)
                if (code.isNullOrBlank() || uid.isNullOrBlank()) {
                    stopSharingAndSelf(disableSharing = true)
                    return START_NOT_STICKY
                }
                beginSharing(code, uid)
                return START_STICKY
            }
            null -> {
                val code = prefs.getString(KEY_SESSION_CODE, prefs.getString(KEY_CODE, null))
                val uid = prefs.getString(KEY_SESSION_UID, FirebaseAuth.getInstance().currentUser?.uid)
                if (code.isNullOrBlank() || uid.isNullOrBlank()) {
                    stopSharingAndSelf(disableSharing = true)
                    return START_NOT_STICKY
                }
                beginSharing(code, uid)
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun beginSharing(code: String, uid: String) {
        if (activeCode == code && activeUid == uid && locationCallback != null) return

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        val sharingEnabled = prefs.getBoolean(KEY_SHARE_ENABLED, false)
        val backgroundEnabled = prefs.getBoolean(KEY_BACKGROUND_ENABLED, false)
        if (currentUid != uid || !sharingEnabled || !backgroundEnabled || !hasLocationPermission()) {
            stopSharingAndSelf(disableSharing = true)
            return
        }

        activeCode = code
        activeUid = uid
        prefs.edit()
            .putString(KEY_SESSION_CODE, code)
            .putString(KEY_SESSION_UID, uid)
            .apply()

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) {
            clearBackgroundPreference(disableSharing = false)
            stopSelf()
            return
        } catch (_: RuntimeException) {
            clearBackgroundPreference(disableSharing = false)
            stopSelf()
            return
        }

        watchAuthentication(uid)
        watchCoupleMembership(code, uid)
        requestLocationUpdates(force = true)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun watchAuthentication(uid: String) {
        if (authListener != null) return
        authListener = FirebaseAuth.AuthStateListener { auth ->
            if (auth.currentUser?.uid != uid) stopSharingAndSelf(disableSharing = true)
        }.also { FirebaseAuth.getInstance().addAuthStateListener(it) }
    }

    private fun watchCoupleMembership(code: String, uid: String) {
        coupleListener?.remove()
        coupleListener = FirebaseFirestore.getInstance()
            .collection("couples")
            .document(code)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                val members = snapshot?.get("members") as? List<*>
                if (snapshot == null) return@addSnapshotListener
                if (snapshot.metadata.isFromCache && !snapshot.exists()) return@addSnapshotListener
                if (!snapshot.exists() || members?.contains(uid) != true) {
                    stopSharingAndSelf(disableSharing = true)
                }
            }
    }

    private fun requestLocationUpdates(force: Boolean) {
        if (!hasLocationPermission()) {
            stopSharingAndSelf(disableSharing = true)
            return
        }

        val lowPower = shouldUseLowPower()
        if (!force && locationCallback != null && lowPower == lowPowerRequest) return

        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        lowPowerRequest = lowPower

        val interval = if (lowPower) 60_000L else 20_000L
        val minInterval = if (lowPower) 30_000L else 12_000L
        val minDistance = if (lowPower) 40f else 12f
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval)
            .setMinUpdateIntervalMillis(minInterval)
            .setMinUpdateDistanceMeters(minDistance)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                if (System.currentTimeMillis() - location.time > MAX_LOCATION_AGE_MS) return
                val code = activeCode ?: return
                val uid = activeUid ?: return
                LocationRepo.publish(code, uid, location)
                    .addOnFailureListener {
                        updateNotification("Location sync is temporarily unavailable")
                    }
                requestLocationUpdates(force = false)
            }
        }
        locationCallback = callback
        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnSuccessListener {
                    cancelLocationRetry()
                    updateNotification(if (lowPowerRequest) "Sharing location · battery saver" else "Sharing your live location")
                }
                .addOnFailureListener {
                    fusedClient.removeLocationUpdates(callback)
                    if (locationCallback === callback) locationCallback = null
                    updateNotification("Waiting for GPS or location permission")
                    scheduleLocationRetry()
                }
        } catch (_: SecurityException) {
            stopSharingAndSelf(disableSharing = true)
        }
    }

    private fun scheduleLocationRetry() {
        if (retryRunnable != null || activeCode == null || !prefs.getBoolean(KEY_BACKGROUND_ENABLED, false)) return
        val retry = Runnable {
            retryRunnable = null
            if (activeCode != null && prefs.getBoolean(KEY_BACKGROUND_ENABLED, false)) {
                requestLocationUpdates(force = true)
            }
        }
        retryRunnable = retry
        serviceHandler.postDelayed(retry, LOCATION_RETRY_DELAY_MS)
    }

    private fun cancelLocationRetry() {
        retryRunnable?.let(serviceHandler::removeCallbacks)
        retryRunnable = null
    }

    private fun shouldUseLowPower(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return powerManager.isPowerSaveMode || (level in 0..20)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LocationSharingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("Us is sharing your location")
            .setContentText("Your partner can see your latest location")
            .setContentIntent(contentPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_notification_heart, "Stop sharing", stopPendingIntent)
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("Us location sharing")
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_notification_heart,
                "Stop sharing",
                PendingIntent.getService(
                    this,
                    REQUEST_STOP,
                    Intent(this, LocationSharingService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live location sharing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible while Us shares your location with your partner, including when the screen is off."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun clearBackgroundPreference(disableSharing: Boolean) {
        val editor = prefs.edit()
            .putBoolean(KEY_BACKGROUND_ENABLED, false)
            .remove(KEY_SESSION_CODE)
            .remove(KEY_SESSION_UID)
        if (disableSharing) editor.putBoolean(KEY_SHARE_ENABLED, false)
        editor.apply()
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(packageName))
    }

    private fun stopSharingAndSelf(disableSharing: Boolean) {
        val code = activeCode ?: prefs.getString(KEY_SESSION_CODE, prefs.getString(KEY_CODE, null))
        val uid = activeUid ?: prefs.getString(KEY_SESSION_UID, FirebaseAuth.getInstance().currentUser?.uid)

        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        cancelLocationRetry()
        coupleListener?.remove()
        coupleListener = null
        authListener?.let { FirebaseAuth.getInstance().removeAuthStateListener(it) }
        authListener = null

        clearBackgroundPreference(disableSharing)
        if (!code.isNullOrBlank() && !uid.isNullOrBlank()) {
            LocationRepo.remove(code, uid)
        }
        activeCode = null
        activeUid = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        cancelLocationRetry()
        coupleListener?.remove()
        coupleListener = null
        authListener?.let { FirebaseAuth.getInstance().removeAuthStateListener(it) }
        authListener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.maenalyn.couples.location.START"
        const val ACTION_STOP = "com.maenalyn.couples.location.STOP"
        const val ACTION_STOP_BACKGROUND = "com.maenalyn.couples.location.STOP_BACKGROUND"
        const val ACTION_STATUS_CHANGED = "com.maenalyn.couples.location.STATUS_CHANGED"
        const val EXTRA_CODE = "location_couple_code"
        const val EXTRA_UID = "location_member_uid"
        const val PREFS_NAME = "us"
        const val KEY_CODE = "code"
        const val KEY_SHARE_ENABLED = "shareLiveLocation"
        const val KEY_BACKGROUND_ENABLED = "keepLocationSharingInBackground"
        const val KEY_SESSION_CODE = "locationServiceCode"
        const val KEY_SESSION_UID = "locationServiceUid"

        private const val CHANNEL_ID = "us_live_location_service"
        private const val NOTIFICATION_ID = 24031
        private const val REQUEST_STOP = 24032
        private const val REQUEST_OPEN = 24033
        private const val MAX_LOCATION_AGE_MS = 120_000L
        private const val LOCATION_RETRY_DELAY_MS = 30_000L

        fun start(context: Context, code: String, uid: String) {
            val intent = Intent(context, LocationSharingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CODE, code)
                putExtra(EXTRA_UID, uid)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, disableSharing: Boolean = false) {
            val action = if (disableSharing) ACTION_STOP else ACTION_STOP_BACKGROUND
            val intent = Intent(context, LocationSharingService::class.java).setAction(action)
            runCatching { context.startService(intent) }
        }
    }
}
