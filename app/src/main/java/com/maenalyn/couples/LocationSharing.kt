package com.maenalyn.couples

import android.annotation.SuppressLint
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** The latest voluntarily shared location for one member. No route history is stored. */
data class SharedLocation(
    val uid: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val updatedAtMillis: Long
)

object LocationRepo {
    private val db get() = FirebaseFirestore.getInstance()

    private fun locations(code: String) =
        db.collection("couples").document(code).collection("locations")

    fun observeLocations(code: String): Flow<List<SharedLocation>> = callbackFlow {
        var lastKnownLocations: List<SharedLocation> = emptyList()
        val registration = locations(code).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val result = snapshot?.documents.orEmpty().mapNotNull { document ->
                val latitude = document.getDouble("latitude") ?: return@mapNotNull null
                val longitude = document.getDouble("longitude") ?: return@mapNotNull null
                val updatedAt = document.getTimestamp(
                    "updatedAt",
                    com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )?.toDate()?.time ?: return@mapNotNull null
                SharedLocation(
                    uid = document.id,
                    latitude = latitude,
                    longitude = longitude,
                    accuracyMeters = (document.getDouble("accuracyMeters") ?: 0.0).toFloat(),
                    updatedAtMillis = updatedAt
                )
            }

            // Do not briefly erase markers when Firestore first emits an empty cache snapshot.
            // An explicit local delete or an empty server snapshot is still allowed to clear them.
            val isUnconfirmedCachedEmpty = snapshot?.metadata?.isFromCache == true &&
                !snapshot.metadata.hasPendingWrites() &&
                result.isEmpty() &&
                lastKnownLocations.isNotEmpty()

            if (isUnconfirmedCachedEmpty) {
                trySend(lastKnownLocations)
            } else {
                lastKnownLocations = result
                trySend(result)
            }
        }
        awaitClose { registration.remove() }
    }

    fun publish(code: String, uid: String, location: Location) =
        locations(code).document(uid).set(
            mapOf(
                "uid" to uid,
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "accuracyMeters" to location.accuracy.toDouble(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

    fun remove(code: String, uid: String) = locations(code).document(uid).delete()
}

/** Publishes device location only while the activity is in the foreground. */
class ForegroundLocationPublisher(context: Context) {
    private companion object {
        const val MAX_LOCATION_AGE_MS = 120_000L
    }
    private val clientContext = context.applicationContext
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(clientContext)
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun start(onLocation: (Location) -> Unit) {
        if (callback != null) return
        val nextCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.takeIf {
                    System.currentTimeMillis() - it.time <= MAX_LOCATION_AGE_MS
                }?.let(onLocation)
            }
        }
        callback = nextCallback
        val powerManager = clientContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryManager = clientContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val lowPower = powerManager.isPowerSaveMode || batteryLevel in 0..20
        val interval = if (lowPower) 60_000L else 20_000L
        val minInterval = if (lowPower) 30_000L else 12_000L
        // Do not gate updates by movement: a stationary phone still needs to refresh
        // its shared-location timestamp so the partner's marker remains current.
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval)
            .setMinUpdateIntervalMillis(minInterval)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            client.requestLocationUpdates(request, nextCallback, Looper.getMainLooper())
                .addOnFailureListener { callback = null }
        } catch (_: SecurityException) {
            callback = null
        }
    }

    fun stop() {
        val activeCallback = callback ?: return
        client.removeLocationUpdates(activeCallback)
        callback = null
    }
}
