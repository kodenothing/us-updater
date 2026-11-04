package com.maenalyn.couples

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

const val MAX_SAVED_PLACES = 25
const val MIN_PLACE_GEOFENCE_RADIUS_METERS = 100
const val MAX_PLACE_GEOFENCE_RADIUS_METERS = 1500

data class SavedPlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val createdBy: String,
    val createdAtMillis: Long
)

data class PlaceTransitionEvent(
    val id: String,
    val eventKey: String,
    val actorUid: String,
    val placeId: String,
    val placeName: String,
    val transition: String,
    val createdAtMillis: Long
)

object PlacesRepo {
    private val db get() = FirebaseFirestore.getInstance()
    private fun couple(code: String) = db.collection("couples").document(code)
    private fun places(code: String) = couple(code).collection("places")
    private fun placeEvents(code: String) = couple(code).collection("placeEvents")

    fun observePlaces(code: String) = kotlinx.coroutines.flow.callbackFlow<List<SavedPlace>> {
        var lastKnownPlaces: List<SavedPlace> = emptyList()
        val registration = places(code).orderBy("createdAt").addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val result = snapshot?.documents.orEmpty().mapNotNull { doc ->
                val name = doc.getString("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val latitude = doc.getDouble("latitude") ?: return@mapNotNull null
                val longitude = doc.getDouble("longitude") ?: return@mapNotNull null
                SavedPlace(
                    id = doc.id,
                    name = name,
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = (doc.getLong("radiusMeters") ?: 150L).toInt(),
                    createdBy = doc.getString("createdBy") ?: "",
                    createdAtMillis = doc.getLong("createdAt") ?: 0L
                )
            }

            // Avoid dropping saved pins when the listener first reports an empty local cache.
            // Explicit deletes (pending local writes) and confirmed empty server results clear them.
            val isUnconfirmedCachedEmpty = snapshot?.metadata?.isFromCache == true &&
                !snapshot.metadata.hasPendingWrites() &&
                result.isEmpty() &&
                lastKnownPlaces.isNotEmpty()

            if (isUnconfirmedCachedEmpty) {
                trySend(lastKnownPlaces)
            } else {
                lastKnownPlaces = result
                trySend(result)
            }
        }
        awaitClose { registration.remove() }
    }

    fun observeEvents(code: String) = kotlinx.coroutines.flow.callbackFlow<List<PlaceTransitionEvent>> {
        val registration = placeEvents(code).orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(50).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val result = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    val actorUid = doc.getString("actorUid") ?: return@mapNotNull null
                    val placeId = doc.getString("placeId") ?: return@mapNotNull null
                    val transition = doc.getString("transition") ?: return@mapNotNull null
                    PlaceTransitionEvent(
                        id = doc.id,
                        eventKey = doc.getString("eventKey") ?: doc.id,
                        actorUid = actorUid,
                        placeId = placeId,
                        placeName = doc.getString("placeName") ?: "a saved place",
                        transition = transition,
                        createdAtMillis = doc.getLong("createdAt") ?: 0L
                    )
                }
                trySend(result)
            }
        awaitClose { registration.remove() }
    }

    suspend fun addPlace(
        code: String,
        uid: String,
        nameInput: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int
    ): String {
        val name = nameInput.trim()
        if (name.isBlank() || name.length > 40) throw IllegalArgumentException("Use a place name between 1 and 40 characters.")
        if (!latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0) {
            throw IllegalArgumentException("That map location is invalid.")
        }
        if (radiusMeters !in MIN_PLACE_GEOFENCE_RADIUS_METERS..MAX_PLACE_GEOFENCE_RADIUS_METERS) {
            throw IllegalArgumentException("Choose a radius from 100 to 1,500 meters.")
        }
        val existing = places(code).limit((MAX_SAVED_PLACES + 1).toLong()).get().await()
        if (existing.size() >= MAX_SAVED_PLACES) throw IllegalArgumentException("You can save up to $MAX_SAVED_PLACES shared places.")
        val ref = places(code).document()
        ref.set(mapOf(
            "name" to name,
            "latitude" to latitude,
            "longitude" to longitude,
            "radiusMeters" to radiusMeters,
            "createdBy" to uid,
            "createdAt" to System.currentTimeMillis()
        )).await()
        return ref.id
    }

    suspend fun deletePlace(code: String, placeId: String) {
        places(code).document(placeId).delete().await()
    }

    suspend fun getPlace(code: String, placeId: String): SavedPlace? {
        val doc = places(code).document(placeId).get().await()
        if (!doc.exists()) return null
        return SavedPlace(
            id = doc.id,
            name = doc.getString("name") ?: return null,
            latitude = doc.getDouble("latitude") ?: return null,
            longitude = doc.getDouble("longitude") ?: return null,
            radiusMeters = (doc.getLong("radiusMeters") ?: 150L).toInt(),
            createdBy = doc.getString("createdBy") ?: "",
            createdAtMillis = doc.getLong("createdAt") ?: 0L
        )
    }

    suspend fun publishTransition(
        code: String,
        uid: String,
        place: SavedPlace,
        transition: String,
        eventTime: Long
    ) {
        if (transition !in setOf("entered", "left")) return
        val eventRef = placeEvents(code).document()
        val eventKey = eventRef.id
        eventRef.set(mapOf(
            "eventKey" to eventKey,
            "actorUid" to uid,
            "placeId" to place.id,
            "placeName" to place.name,
            "transition" to transition,
            "createdAt" to eventTime
        )).await()
        val cutoff = eventTime - TimeUnit.DAYS.toMillis(30)
        runCatching {
            placeEvents(code).whereLessThan("createdAt", cutoff).limit(100).get().await()
                .documents.filter { it.getString("actorUid") == uid }
                .forEach { it.reference.delete() }
        }
    }
}

object PlaceGeofenceManager {
    private const val REQUEST_CODE = 24041
    private const val PREFS = "us"
    private const val ALERTS_ENABLED = "placeAlertsEnabled"

    fun hasRequiredPermissions(context: Context): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && background
    }

    fun sync(context: Context, code: String, places: List<SavedPlace>) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ALERTS_ENABLED, false) || !hasRequiredPermissions(app)) {
            removeRegisteredFences(app)
            return
        }
        val client = LocationServices.getGeofencingClient(app)
        val pendingIntent = pendingIntent(app)
        client.removeGeofences(pendingIntent).addOnCompleteListener {
            if (places.isEmpty()) {
                prefs.edit().remove("placeGeofenceCode").apply()
                return@addOnCompleteListener
            }
            val fences = places.take(90).map { place ->
                Geofence.Builder()
                    .setRequestId(place.id)
                    .setCircularRegion(place.latitude, place.longitude, place.radiusMeters.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness(120_000)
                    .build()
            }
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(0)
                .addGeofences(fences)
                .build()
            runCatching { client.addGeofences(request, pendingIntent) }
                .onFailure { prefs.edit().putString("placeGeofenceError", it.message ?: "Registration failed").apply() }
                .getOrNull()
                ?.addOnSuccessListener {
                    prefs.edit().remove("placeGeofenceError").putString("placeGeofenceCode", code).apply()
                    places.forEach { prefs.edit().putString("placeName_${it.id}", it.name).apply() }
                }
                ?.addOnFailureListener { prefs.edit().putString("placeGeofenceError", it.message ?: "Registration failed").apply() }
        }
    }

    fun removeAll(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ALERTS_ENABLED, false)
            .apply()
        removeRegisteredFences(app)
    }

    private fun removeRegisteredFences(context: Context) {
        runCatching {
            LocationServices.getGeofencingClient(context.applicationContext)
                .removeGeofences(pendingIntent(context.applicationContext))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("placeGeofenceCode")
            .apply()
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PlaceGeofenceReceiver::class.java).setAction(ACTION_TRANSITION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    const val ACTION_TRANSITION = "com.maenalyn.couples.place.GEOFENCE_TRANSITION"
}

class PlaceGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PlaceGeofenceManager.ACTION_TRANSITION) return
        val geofenceEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofenceEvent.hasError()) return
        val transition = when (geofenceEvent.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "entered"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "left"
            else -> return
        }
        val prefs = context.getSharedPreferences("us", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("placeAlertsEnabled", false)) return
        val code = prefs.getString("code", null) ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val triggered = geofenceEvent.triggeringGeofences.orEmpty()
        if (triggered.isEmpty()) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(TimeUnit.SECONDS.toMillis(20)) {
                    triggered.forEach { fence ->
                        val name = prefs.getString("placeName_${fence.requestId}", null) ?: "a saved place"
                        val place = PlacesRepo.getPlace(code, fence.requestId) ?: return@forEach
                        PlacesRepo.publishTransition(code, uid, place, transition, System.currentTimeMillis())
                        UsNotifications.showOwnPlaceAlert(
                            context,
                            "${fence.requestId}:$uid:${System.currentTimeMillis()}",
                            name,
                            transition == "entered"
                        )
                    }
                }
            } catch (_: Exception) {
                // Geofence broadcasts are best-effort; the next map sync re-registers current places.
            } finally {
                pending.finish()
            }
        }
    }
}
