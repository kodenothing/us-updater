package com.maenalyn.couples

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import android.graphics.LinearGradient
import android.graphics.Shader
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor

private const val MAP_TAG = "OurMap"

private const val US_MAP_STYLE = "https://tiles.openfreemap.org/styles/positron"
private const val LOCATION_FRESH_MS = 90_000L
private const val FALLBACK_MAP_STYLE = "https://demotiles.maplibre.org/style.json"

private data class RoadRoute(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

private data class RouteRequestKey(
    val fromUid: String,
    val toUid: String,
    val fromLat: Int,
    val fromLon: Int,
    val toLat: Int,
    val toLon: Int
)

// Free OSRM demo server: driving routes, no API key. Please keep requests rare (max 1/second).
private suspend fun fetchRoadRoute(
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double
): RoadRoute? = withContext(Dispatchers.IO) {

    var connection: HttpURLConnection? = null

    try {
        val url = URL(
            "https://router.project-osrm.org/route/v1/driving/" +
                    "$fromLon,$fromLat;$toLon,$toLat" +
                    "?overview=full&geometries=geojson" +
                    "&alternatives=false&steps=false"
        )

        val conn = url.openConnection() as HttpURLConnection
        connection = conn

        conn.connectTimeout = 12_000
        conn.readTimeout = 20_000
        conn.requestMethod = "GET"
        conn.setRequestProperty(
            "User-Agent",
            "UsApp/2.4.2 (Android couples app)"
        )
        conn.setRequestProperty("Accept", "application/json")

        val status = conn.responseCode

        val responseStream = if (status in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream
        }

        val responseBody = responseStream
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()

        if (status != HttpURLConnection.HTTP_OK) {
            Log.e(
                MAP_TAG,
                "ROUTE HTTP ERROR: $status - ${responseBody.take(300)}"
            )
            return@withContext null
        }

        val json = JSONObject(responseBody)

        val code = json.optString("code")

        if (code != "Ok") {
            Log.e(
                MAP_TAG,
                "ROUTE API ERROR: $code - ${json.optString("message")}"
            )
            return@withContext null
        }

        val routes = json.optJSONArray("routes")

        if (routes == null || routes.length() == 0) {
            Log.e(MAP_TAG, "ROUTE ERROR: No routes returned")
            return@withContext null
        }

        val route = routes.getJSONObject(0)

        val coords = route
            .getJSONObject("geometry")
            .getJSONArray("coordinates")

        val points = ArrayList<LatLng>(coords.length())

        for (i in 0 until coords.length()) {
            val coordinate = coords.getJSONArray(i)

            points.add(
                LatLng(
                    coordinate.getDouble(1),
                    coordinate.getDouble(0)
                )
            )
        }

        if (points.size < 2) {
            Log.e(MAP_TAG, "ROUTE ERROR: Insufficient route coordinates")
            return@withContext null
        }

        val result = RoadRoute(
            points = points,
            distanceMeters = route.getDouble("distance"),
            durationSeconds = route.getDouble("duration")
        )

        Log.i(
            MAP_TAG,
            "ROUTE SUCCESS: ${points.size} points, " +
                    "${result.distanceMeters.roundToInt()} meters"
        )

        result

    } catch (e: CancellationException) {
        throw e

    } catch (e: Exception) {
        Log.e(
            MAP_TAG,
            "ROUTE REQUEST FAILED: ${e.javaClass.simpleName}: ${e.message}",
            e
        )
        null

    } finally {
        connection?.disconnect()
    }
}

private fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun OurMapTab(
    couple: CoupleState.Active?,
    currentUid: String?,
    sharedLocations: List<SharedLocation>,
    sharingEnabled: Boolean,
    permissionGranted: Boolean,
    keepSharingInBackground: Boolean,
    shareDurationMinutes: Int,
    shareExpiresAtMillis: Long,
    onShareDurationChange: (Int) -> Unit,
    savedPlaces: List<SavedPlace>,
    placeAlertsEnabled: Boolean,
    backgroundLocationPermissionGranted: Boolean,
    onTogglePlaceAlerts: (Boolean) -> Unit,
    onSavePlace: (String, Double, Double, Int) -> Unit,
    onDeletePlace: (String) -> Unit,
    onToggleSharing: (Boolean) -> Unit,
    onToggleBackgroundSharing: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val myName = couple?.memberNames?.get(currentUid).orEmpty().ifBlank { "You" }
    val partnerUid = couple?.memberIds?.firstOrNull { it != currentUid }
    val partnerName = couple?.memberNames?.get(partnerUid).orEmpty().ifBlank { "Your person" }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var recenterRequest by remember { mutableIntStateOf(0) }
    var pendingPlaceCoordinate by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showSavePlaceDialog by remember { mutableStateOf(false) }
    var newPlaceName by remember { mutableStateOf("") }
    var newPlaceRadius by remember { mutableIntStateOf(150) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(5_000L)
        }
    }

    fun isFresh(location: SharedLocation?): Boolean {
        if (location == null) return false
        val age = now - location.updatedAtMillis
        return age in -30_000L..LOCATION_FRESH_MS
    }

    val myLatest = sharedLocations.firstOrNull { it.uid == currentUid }
    val partnerLatest = sharedLocations.firstOrNull { it.uid == partnerUid }

    // Fresh fixes are required for distance and routing, but an older saved fix must
    // remain visible as a last-known marker while the Firestore sharing document exists.
    val myLocation = myLatest.takeIf(::isFresh)
    val partnerLocation = partnerLatest.takeIf(::isFresh)
    val bothSharing = myLocation != null && partnerLocation != null
    val distanceMeters = if (bothSharing) distanceBetween(myLocation!!, partnerLocation!!) else null
    val livePins = listOfNotNull(
        myLatest?.let {
            MapPin(
                it.uid, it.latitude, it.longitude,
                if (isFresh(it)) myName else "$myName · last known",
                isMine = true
            )
        },
        partnerLatest?.let {
            MapPin(
                it.uid, it.latitude, it.longitude,
                if (isFresh(it)) partnerName else "$partnerName · last known",
                isMine = false
            )
        }
    )
    val placePins = savedPlaces.map {
        MapPin("place:${it.id}", it.latitude, it.longitude, it.name, isPlace = true)
    }
    val pendingPin = pendingPlaceCoordinate?.let {
        MapPin("pending-place", it.first, it.second, "Selected place", isPlace = true, isPending = true)
    }
    val mapPins = livePins + placePins + listOfNotNull(pendingPin)

    var roadRoute by remember { mutableStateOf<RoadRoute?>(null) }
    var routeLoading by remember { mutableStateOf(false) }
    var lastRouteRequestAt by remember { mutableStateOf(0L) }

    val activeRoute = roadRoute

// Always use the same origin and destination on both phones.
    val routePair = listOfNotNull(myLocation, partnerLocation)
        .sortedBy { it.uid }

    val routeFrom = routePair.getOrNull(0)
    val routeTo = routePair.getOrNull(1)

    val routeKey = if (routeFrom != null && routeTo != null) {
        RouteRequestKey(
            fromUid = routeFrom.uid,
            toUid = routeTo.uid,
            fromLat = (routeFrom.latitude * 1000).roundToInt(),
            fromLon = (routeFrom.longitude * 1000).roundToInt(),
            toLat = (routeTo.latitude * 1000).roundToInt(),
            toLon = (routeTo.longitude * 1000).roundToInt()
        )
    } else null

    LaunchedEffect(routeKey) {
        val from = routeFrom
        val to = routeTo

        if (routeKey == null || from == null || to == null) {
            roadRoute = null
            routeLoading = false
            return@LaunchedEffect
        }

        routeLoading = true
        roadRoute = null

        try {
            val wait = 10_000L -
                    (System.currentTimeMillis() - lastRouteRequestAt)

            if (wait > 0) delay(wait)

            lastRouteRequestAt = System.currentTimeMillis()

            roadRoute = fetchRoadRoute(
                from.latitude,
                from.longitude,
                to.latitude,
                to.longitude
            )
        } finally {
            routeLoading = false
        }
    }

    var settingsExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val collapsedPanelHeight = 112.dp
        val expandedPanelHeight = minOf(maxHeight * 0.68f, 460.dp)
        val panelHeight by animateDpAsState(
            targetValue = if (settingsExpanded) expandedPanelHeight else collapsedPanelHeight,
            label = "map settings panel height"
        )

        Box(Modifier.fillMaxSize()) {
            MapLibreSurface(
                pins = mapPins,
                recenterRequest = recenterRequest,
                roadRoutePoints = activeRoute?.points,
                onMapLongPress = { latitude, longitude ->
                    if (savedPlaces.size < MAX_SAVED_PLACES) {
                        pendingPlaceCoordinate = latitude to longitude
                        newPlaceName = ""
                        newPlaceRadius = 150
                        showSavePlaceDialog = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            Card(
                modifier = Modifier.align(Alignment.TopCenter).padding(14.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface.copy(alpha = 0.96f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Place, contentDescription = null, tint = cs.primary)
                        Spacer(Modifier.size(8.dp))
                        Text("Our little map", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    if (distanceMeters != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (activeRoute != null) "${formatDistance(activeRoute.distanceMeters)} by road"
                                else "${formatDistance(distanceMeters)} apart",
                                style = MaterialTheme.typography.titleSmall,
                                color = cs.primary
                            )
                            Text(
                                when {
                                    activeRoute != null -> "Driving route · about ${formatDuration(activeRoute.durationSeconds)} without traffic"
                                    routeLoading -> "Finding the road route…"
                                    else -> "Straight-line distance (no road route available)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = when {
                                myLocation != null -> "Your location is live. Waiting for your person."
                                partnerLocation != null -> "$partnerName is sharing. Turn yours on to see the distance."
                                else -> "Share your locations to see where you are relative to each other."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = panelHeight + 20.dp),
                shape = CircleShape,
                color = cs.surface.copy(alpha = 0.96f),
                shadowElevation = 5.dp
            ) {
                IconButton(
                    onClick = { recenterRequest++ },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Fit both locations", tint = cs.primary)
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = panelHeight + 18.dp),
                shape = RoundedCornerShape(8.dp),
                color = cs.surface.copy(alpha = 0.88f)
            ) {
                Text(
                    "Our Map",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }
        }

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .height(panelHeight),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .pointerInput(settingsExpanded) {
                            var totalDrag = 0f
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDrag += dragAmount
                                },
                                onDragEnd = {
                                    when {
                                        totalDrag < -36f -> settingsExpanded = true
                                        totalDrag > 36f -> settingsExpanded = false
                                    }
                                    totalDrag = 0f
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.size(width = 38.dp, height = 4.dp),
                        shape = CircleShape,
                        color = cs.outlineVariant
                    ) {}
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settingsExpanded = !settingsExpanded }
                        .padding(start = 16.dp, end = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(cs.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.MyLocation, null, tint = cs.primary)
                    }
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Share my live location", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                !permissionGranted -> "Location permission is needed"
                                keepSharingInBackground -> "Sharing, even when the screen is off"
                                sharingEnabled -> "Sharing while Us is open"
                                else -> "Location sharing is off"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(checked = sharingEnabled, onCheckedChange = onToggleSharing)
                    IconButton(onClick = { settingsExpanded = !settingsExpanded }) {
                        Icon(
                            imageVector = if (settingsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (settingsExpanded) "Collapse map settings" else "Expand map settings",
                            tint = cs.primary
                        )
                    }
                }

                if (settingsExpanded) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Share duration", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = shareDurationMinutes == 15,
                                onClick = { onShareDurationChange(15) },
                                label = { Text("15 min") }
                            )
                            FilterChip(
                                selected = shareDurationMinutes == 60,
                                onClick = { onShareDurationChange(60) },
                                label = { Text("1 hour") }
                            )
                            FilterChip(
                                selected = shareDurationMinutes == 0,
                                onClick = { onShareDurationChange(0) },
                                label = { Text("Until stopped") }
                            )
                        }
                        if (sharingEnabled && shareExpiresAtMillis > System.currentTimeMillis()) {
                            val remaining = ((shareExpiresAtMillis - now) / 60_000L).coerceAtLeast(0L)
                            Text(
                                if (remaining > 0) "Sharing ends in about $remaining min" else "Sharing ends in less than a minute",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.primary
                            )
                        }

                        Divider(color = cs.outlineVariant.copy(alpha = 0.55f))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape).background(cs.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.BatteryChargingFull, null, tint = cs.secondary)
                            }
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Keep sharing with screen off", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (keepSharingInBackground) "Active · a persistent notification is shown"
                                    else "Optional · uses a visible Android notification",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = keepSharingInBackground,
                                onCheckedChange = onToggleBackgroundSharing,
                                enabled = sharingEnabled && permissionGranted
                            )
                        }

                        Divider(color = cs.outlineVariant.copy(alpha = 0.55f))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Arrival & departure alerts", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (backgroundLocationPermissionGranted) "Notify your partner when you arrive or leave a saved place"
                                    else "Optional · needs precise location and Allow all the time",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant
                                )
                            }
                            Switch(checked = placeAlertsEnabled, onCheckedChange = onTogglePlaceAlerts)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Saved places · ${savedPlaces.size}/$MAX_SAVED_PLACES", style = MaterialTheme.typography.titleSmall)
                                Text("Long-press the map to save a place", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                        if (savedPlaces.isEmpty()) {
                            Text("Save home, school, work, or somewhere special.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        } else {
                            savedPlaces.forEach { place ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Place, contentDescription = null, tint = cs.primary)
                                    Spacer(Modifier.size(9.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(place.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                        Text("${place.radiusMeters} m alert radius", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { onDeletePlace(place.id) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${place.name}", tint = cs.error)
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PersonLocationStatus(
                                name = myName,
                                location = myLatest,
                                isFresh = isFresh(myLatest),
                                isEnabled = sharingEnabled,
                                isMe = true,
                                now = now,
                                modifier = Modifier.weight(1f)
                            )
                            PersonLocationStatus(
                                name = partnerName,
                                location = partnerLatest,
                                isFresh = isFresh(partnerLatest),
                                isEnabled = null,
                                isMe = false,
                                now = now,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Text(
                            "Screen-off sharing uses a persistent Android notification. Saved-place alerts can be delayed by Android location services; they are not guaranteed to be instant.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                        if (sharingEnabled && !permissionGranted) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.LocationOff, null, tint = cs.error, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Allow location access in Android Settings to share.", color = cs.error, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSavePlaceDialog && pendingPlaceCoordinate != null) {
        AlertDialog(
            onDismissRequest = { showSavePlaceDialog = false; pendingPlaceCoordinate = null },
            title = { Text("Save shared place") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newPlaceName,
                        onValueChange = { newPlaceName = it.take(40) },
                        label = { Text("Place name") },
                        singleLine = true
                    )
                    Text("Alert radius", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(100, 150, 300, 500).forEach { radius ->
                            FilterChip(
                                selected = newPlaceRadius == radius,
                                onClick = { newPlaceRadius = radius },
                                label = { Text("${radius}m") }
                            )
                        }
                    }
                    Text("Android may take a few minutes to detect arrival or departure. A radius of 100–150 meters is recommended.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(
                    enabled = newPlaceName.trim().isNotEmpty() && savedPlaces.size < MAX_SAVED_PLACES,
                    onClick = {
                        val point = pendingPlaceCoordinate ?: return@Button
                        onSavePlace(newPlaceName.trim(), point.first, point.second, newPlaceRadius)
                        showSavePlaceDialog = false
                        pendingPlaceCoordinate = null
                    }
                ) { Text("Save place") }
            },
            dismissButton = {
                TextButton(onClick = { showSavePlaceDialog = false; pendingPlaceCoordinate = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PersonLocationStatus(
    name: String,
    location: SharedLocation?,
    isFresh: Boolean,
    isEnabled: Boolean?,
    isMe: Boolean,
    now: Long,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val age = location?.let { (now - it.updatedAtMillis).coerceAtLeast(0L) }
    val status = when {
        isFresh -> "Live · ${formatAge(age ?: 0L)}"
        location != null -> "Last seen ${formatAge(age ?: 0L)} ago"
        isEnabled == true -> "Waiting for GPS"
        isEnabled == false -> "Not sharing"
        else -> "No location available"
    }
    val accuracy = location?.accuracyMeters
    val accuracyLabel = when {
        accuracy == null -> null
        accuracy <= 25f -> "Good GPS"
        accuracy <= 100f -> "Approximate GPS"
        else -> "Low GPS accuracy"
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.padding(top = 4.dp).size(9.dp).clip(CircleShape)
                    .background(if (isFresh) if (isMe) cs.primary else Color(0xFF48A879) else cs.outlineVariant)
            )
            Spacer(Modifier.size(7.dp))
            Column {
                Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Text(status, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                if (accuracy != null && accuracyLabel != null) {
                    Text(
                        "$accuracyLabel · ±${accuracy.roundToInt()} m",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private data class MapPin(
    val uid: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val isPlace: Boolean = false,
    val isMine: Boolean = false,
    val isPending: Boolean = false
)

@Composable
private fun MapLibreSurface(
    pins: List<MapPin>,
    recenterRequest: Int,
    roadRoutePoints: List<LatLng>?,
    onMapLongPress: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // MapLibre docs: MapLibre.getInstance(context) must run before any MapView is created.
    remember { MapLibre.getInstance(context.applicationContext) }

    val mapState = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleLoaded = remember { mutableStateOf(false) }
    val mapLoading = remember { mutableStateOf(true) }
    val mapError = remember { mutableStateOf<String?>(null) }
    val mapAttempt = remember { mutableIntStateOf(0) }
    val fallbackStyleUsed = remember { mutableStateOf(false) }
    val markers = remember { mutableMapOf<String, Marker>() }
    val markerAnimators = remember { mutableMapOf<String, ValueAnimator>() }
    val routeLine = remember { mutableStateOf<Polyline?>(null) }
    val routeAnimator = remember { mutableStateOf<ValueAnimator?>(null) }
    val userHasMovedMap = remember { mutableStateOf(false) }
    val lastAutoFrameLocations = remember {
        mutableStateOf<Map<String, Pair<Double, Double>>>(emptyMap())
    }
    val focusedAnyLocation = remember { mutableStateOf(false) }
    val focusedFirstPair = remember { mutableStateOf(false) }
    val currentMapLongPress by rememberUpdatedState(onMapLongPress)

    // One MapView per attempt. Retry bumps mapAttempt, which builds a brand-new MapView.
    val mapView = remember(mapAttempt.intValue) {
        Log.i(MAP_TAG, "CREATE: MapView (attempt ${mapAttempt.intValue})")
        // TextureView mode draws like a normal view; the default GLSurfaceView is a separate
        // window layer that can fail to start inside Compose on some devices.
        val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
        MapView(context, options).apply {
            setBackgroundColor(AndroidColor.rgb(232, 236, 240))
        }
    }

    LaunchedEffect(mapAttempt.intValue, mapLoading.value) {
        if (mapLoading.value) {
            delay(20_000L)
            if (mapLoading.value && mapError.value == null) {
                mapLoading.value = false
                mapError.value = if (mapState.value == null) {
                    "The map view did not start (no map-ready callback)."
                } else {
                    "The map style/tiles did not load within 20 seconds."
                }
                Log.e(MAP_TAG, "TIMEOUT: ${mapError.value}")
            }
        }
    }

    fun loadMapStyle(map: MapLibreMap, url: String) {
        mapLoading.value = true
        mapError.value = null
        styleLoaded.value = false
        routeAnimator.value?.cancel()
        routeAnimator.value = null
        routeLine.value?.let { line ->
            runCatching { map.removePolyline(line) }
        }
        routeLine.value = null
        Log.i(MAP_TAG, "STYLE: requesting $url")
        map.setStyle(url) {
            Log.i(MAP_TAG, "STYLE: loaded ($url)")
            mapState.value = map
            mapLoading.value = false
            mapError.value = null
            styleLoaded.value = true
            lastAutoFrameLocations.value = emptyMap()
            userHasMovedMap.value = false
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(14.5995, 120.9842),
                    5.5
                )
            )
        }
    }

    Box(modifier = modifier) {
        key(mapView) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapView },
                update = { }
            )
        }

        if (mapLoading.value && mapError.value == null) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Loading map…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        mapError.value?.let { error ->
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text("Map couldn't load", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Check your internet connection, then try again. MapLibre reported: $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            fallbackStyleUsed.value = false
                            val map = mapState.value
                            if (map != null) {
                                loadMapStyle(map, US_MAP_STYLE)
                            } else {
                                // The map never became ready: build a brand-new MapView.
                                styleLoaded.value = false
                                mapError.value = null
                                mapLoading.value = true
                                mapAttempt.intValue = mapAttempt.intValue + 1
                            }
                        }
                    ) {
                        Icon(Icons.Filled.MyLocation, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Retry map")
                    }
                }
            }
        }
    }

    // Lifecycle: forwards onCreate/onStart/onResume/onPause/onStop/onDestroy to the MapView,
    // exactly the calls the MapLibre quickstart makes from an Activity.
    androidx.compose.runtime.DisposableEffect(mapView, lifecycleOwner) {
        var created = false
        var started = false
        var resumed = false
        var destroyed = false
        var longClickListener: MapLibreMap.OnMapLongClickListener? = null
        var cameraMoveStartedListener: MapLibreMap.OnCameraMoveStartedListener? = null

        val failureListener = MapView.OnDidFailLoadingMapListener { errorMessage ->
            Log.e(MAP_TAG, "FAIL: $errorMessage")
            if (!styleLoaded.value) {
                val map = mapState.value
                if (map != null && !fallbackStyleUsed.value) {
                    fallbackStyleUsed.value = true
                    Log.i(MAP_TAG, "STYLE: primary style failed, trying MapLibre demo style")
                    loadMapStyle(map, FALLBACK_MAP_STYLE)
                } else {
                    mapLoading.value = false
                    mapError.value = errorMessage.ifBlank { "Map tiles or map style could not be loaded." }
                }
            }
        }
        mapView.addOnDidFailLoadingMapListener(failureListener)

        // Documented: getMapAsync { map -> map.setStyle(...) }
        mapView.getMapAsync { map ->
            Log.i(MAP_TAG, "CALLBACK: MapLibre map is ready")
            mapState.value = map
            map.uiSettings.isCompassEnabled = true
            val cameraListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    userHasMovedMap.value = true
                    Log.i(MAP_TAG, "CAMERA: auto-follow paused after manual map gesture")
                }
            }
            cameraMoveStartedListener = cameraListener
            map.addOnCameraMoveStartedListener(cameraListener)
            val listener = MapLibreMap.OnMapLongClickListener { point ->
                currentMapLongPress(point.latitude, point.longitude)
                true
            }
            longClickListener = listener
            map.addOnMapLongClickListener(listener)
            loadMapStyle(map, US_MAP_STYLE)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> if (!created) {
                    mapView.onCreate(null); created = true
                    Log.i(MAP_TAG, "LIFECYCLE: MapView.onCreate(null)")
                }
                Lifecycle.Event.ON_START -> if (!started) {
                    mapView.onStart(); started = true
                    Log.i(MAP_TAG, "LIFECYCLE: MapView.onStart()")
                }
                Lifecycle.Event.ON_RESUME -> if (!resumed) {
                    mapView.onResume(); resumed = true
                    Log.i(MAP_TAG, "LIFECYCLE: MapView.onResume()")
                }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                Lifecycle.Event.ON_DESTROY -> if (!destroyed) {
                    if (resumed) { mapView.onPause(); resumed = false }
                    if (started) { mapView.onStop(); started = false }
                    mapView.onDestroy(); destroyed = true
                }
                else -> Unit
            }
        }
        // Adding the observer replays the events up to the current state (CREATE, START, RESUME).
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.removeOnDidFailLoadingMapListener(failureListener) }
            routeAnimator.value?.cancel()
            routeAnimator.value = null
            mapState.value?.let { map ->
                longClickListener?.let(map::removeOnMapLongClickListener)
                cameraMoveStartedListener?.let(map::removeOnCameraMoveStartedListener)
                routeLine.value?.let { line ->
                    runCatching { map.removePolyline(line) }
                }
            }
            longClickListener = null
            cameraMoveStartedListener = null
            routeLine.value = null
            lastAutoFrameLocations.value = emptyMap()
            runCatching {
                if (resumed) mapView.onPause()
                if (started) mapView.onStop()
                if (!destroyed) mapView.onDestroy()
            }
            markerAnimators.values.forEach { it.cancel() }
            markerAnimators.clear()
            markers.clear()
            focusedFirstPair.value = false
            focusedAnyLocation.value = false
            mapState.value = null
            styleLoaded.value = false
        }
    }

    LaunchedEffect(mapState.value, styleLoaded.value, pins, roadRoutePoints) {
        val libreMap = mapState.value ?: return@LaunchedEffect
        if (!styleLoaded.value) return@LaunchedEffect

        val requested = pins.associateBy { it.uid }
        (markers.keys - requested.keys).toList().forEach { uid ->
            markerAnimators.remove(uid)?.cancel()
            markers.remove(uid)?.let { libreMap.removeMarker(it) }
        }

        requested.values.forEach { pin ->
            val target = LatLng(pin.latitude, pin.longitude)
            val marker = markers[pin.uid]
            if (marker == null) {
                val iconFactory = IconFactory.getInstance(context)
                val markerIcon = iconFactory.fromBitmap(
                    createHeartMarkerBitmap(
                        context = context,
                        isMine = pin.isMine,
                        isPlace = pin.isPlace,
                        isPending = pin.isPending,
                        initial = pin.title.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
                    )
                )
                markers[pin.uid] = libreMap.addMarker(
                    MarkerOptions().position(target).title(pin.title)
                        .snippet(
                            when {
                                pin.isPending -> "Selected map location"
                                pin.isPlace -> "Saved shared place"
                                pin.title.endsWith(" · last known") -> "Last known location · updates delayed"
                                else -> "Live shared location"
                            }
                        )
                        .icon(markerIcon)
                )
            } else {
                marker.title = pin.title
                marker.snippet = when {
                    pin.isPending -> "Selected map location"
                    pin.isPlace -> "Saved shared place"
                    pin.title.endsWith(" · last known") -> "Last known location · updates delayed"
                    else -> "Live shared location"
                }
                libreMap.updateMarker(marker)
                val start = marker.position
                if (kotlin.math.abs(start.latitude - target.latitude) > 0.000001 ||
                    kotlin.math.abs(start.longitude - target.longitude) > 0.000001) {
                    markerAnimators.remove(pin.uid)?.cancel()
                    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 1_600L
                        interpolator = AccelerateDecelerateInterpolator()
                        addUpdateListener { valueAnimator ->
                            val fraction = valueAnimator.animatedValue as Float
                            marker.position = LatLng(
                                start.latitude + (target.latitude - start.latitude) * fraction,
                                start.longitude + (target.longitude - start.longitude) * fraction
                            )
                            libreMap.updateMarker(marker)
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                marker.position = target
                                libreMap.updateMarker(marker)
                                markerAnimators.remove(pin.uid)
                            }
                        })
                    }
                    markerAnimators[pin.uid] = animator
                    animator.start()
                }
            }
        }

        // Draw only a verified road route.
        val livePair = pins
            .filter { !it.isPlace && !it.isPending }
            .take(2)

        val roadPoints = roadRoutePoints

        if (livePair.size == 2 && roadPoints != null && roadPoints.size >= 2) {

            routeAnimator.value?.cancel()
            routeAnimator.value = null

            val currentLine = routeLine.value

            if (currentLine == null) {
                routeLine.value = libreMap.addPolyline(
                    PolylineOptions()
                        .addAll(roadPoints)
                        .color(AndroidColor.rgb(126, 94, 196))
                        .width(5f)
                        .alpha(0.88f)
                )
            } else if (currentLine.points != roadPoints) {
                currentLine.points = roadPoints
                libreMap.updatePolyline(currentLine)
            }

        } else {

            // Never display a fake straight line as a road route.
            routeAnimator.value?.cancel()
            routeAnimator.value = null

            routeLine.value?.let { line ->
                runCatching {
                    libreMap.removePolyline(line)
                }
            }

            routeLine.value = null
        }

        val focusPins = pins.filterNot { it.isPlace }.ifEmpty { pins }
        val nextLocations = focusPins.associate { it.uid to (it.latitude to it.longitude) }
        val previousLocations = lastAutoFrameLocations.value

        val membershipChanged = previousLocations.keys != nextLocations.keys
        val movedEnough = focusPins.any { pin ->
            val previous = previousLocations[pin.uid] ?: return@any true
            val previousLocation = SharedLocation(
                pin.uid, previous.first, previous.second, 0f, 0L
            )
            val currentLocation = SharedLocation(
                pin.uid, pin.latitude, pin.longitude, 0f, 0L
            )
            distanceBetween(previousLocation, currentLocation) >= 35.0
        }

        val shouldAutoFrame = previousLocations.isEmpty() || membershipChanged || movedEnough

        if (focusPins.isNotEmpty() && shouldAutoFrame && !userHasMovedMap.value) {
            framePins(libreMap, focusPins)
            lastAutoFrameLocations.value = nextLocations
            focusedFirstPair.value = focusPins.size >= 2
            focusedAnyLocation.value = true
        }
    }

    LaunchedEffect(mapState.value, styleLoaded.value, recenterRequest) {
        if (recenterRequest == 0 || !styleLoaded.value) return@LaunchedEffect
        val libreMap = mapState.value ?: return@LaunchedEffect
        val focusPins = pins.filterNot { it.isPlace }.ifEmpty { pins }

        if (focusPins.isNotEmpty()) {
            userHasMovedMap.value = false
            framePins(libreMap, focusPins)
            lastAutoFrameLocations.value = focusPins.associate {
                it.uid to (it.latitude to it.longitude)
            }
        }
    }
}

private fun createHeartMarkerBitmap(
    context: android.content.Context,
    isMine: Boolean,
    isPlace: Boolean,
    isPending: Boolean,
    initial: String
): Bitmap {
    val density = context.resources.displayMetrics.density
    val width = (76f * density).toInt().coerceAtLeast(1)
    val height = (120f * density).toInt().coerceAtLeast(1)
    val heartWidth = 64f * density
    val heartHeight = 62f * density
    val left = (width - heartWidth) / 2f
    val top = 2f * density
    val centerX = width / 2f
    val right = left + heartWidth
    val tipY = top + heartHeight * 0.94f

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)

    val path = Path().apply {
        // Start at the center indentation.
        moveTo(centerX, top + heartHeight * 0.28f)

        // Right rounded lobe.
        cubicTo(
            centerX + heartWidth * 0.10f, top + heartHeight * 0.02f,
            right, top + heartHeight * 0.02f,
            right, top + heartHeight * 0.34f
        )

        // Right side flowing into the bottom tip.
        cubicTo(
            right, top + heartHeight * 0.58f,
            centerX + heartWidth * 0.18f, top + heartHeight * 0.82f,
            centerX, tipY
        )

        // Bottom tip flowing into the left side.
        cubicTo(
            centerX - heartWidth * 0.18f, top + heartHeight * 0.82f,
            left, top + heartHeight * 0.58f,
            left, top + heartHeight * 0.34f
        )

        // Left rounded lobe returning to the center indentation.
        cubicTo(
            left, top + heartHeight * 0.02f,
            centerX - heartWidth * 0.10f, top + heartHeight * 0.02f,
            centerX, top + heartHeight * 0.28f
        )

        close()
    }

    val colors = when {
        isPending -> AndroidColor.rgb(245, 178, 82) to AndroidColor.rgb(218, 128, 48)
        isPlace -> AndroidColor.rgb(235, 137, 164) to AndroidColor.rgb(196, 83, 119)
        isMine -> AndroidColor.rgb(157, 127, 224) to AndroidColor.rgb(103, 73, 174)
        else -> AndroidColor.rgb(255, 132, 169) to AndroidColor.rgb(211, 67, 119)
    }

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = LinearGradient(
            left, top, right, tipY,
            colors.first, colors.second,
            Shader.TileMode.CLAMP
        )
        setShadowLayer(
            5f * density,
            0f,
            2.5f * density,
            AndroidColor.argb(95, 35, 24, 48)
        )
    }
    canvas.drawPath(path, fill)
    fill.clearShadowLayer()

    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.8f * density
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, border)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(1.5f * density, 0f, 1f * density, AndroidColor.argb(65, 50, 25, 55))
    }
    val labelY = top + heartHeight * 0.43f - (text.descent() + text.ascent()) / 2f
    canvas.drawText(
        initial.ifBlank { if (isPlace) "♥" else "U" },
        centerX,
        labelY,
        text
    )

    return bitmap
}

private fun framePins(map: MapLibreMap, pins: List<MapPin>) {
    if (pins.size >= 2) {
        val first = pins[0]
        val second = pins[1]
        val center = LatLng(
            (first.latitude + second.latitude) / 2.0,
            (first.longitude + second.longitude) / 2.0
        )
        val distance = distanceBetween(
            SharedLocation(first.uid, first.latitude, first.longitude, 0f, 0L),
            SharedLocation(second.uid, second.latitude, second.longitude, 0f, 0L)
        )
        val zoom = when {
            distance < 1_000 -> 13.0
            distance < 5_000 -> 11.0
            distance < 20_000 -> 9.0
            distance < 100_000 -> 7.0
            distance < 500_000 -> 5.0
            else -> 3.5
        }
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(center).zoom(zoom).build()
            ),
            650
        )
    } else {
        val pin = pins.first()
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(pin.latitude, pin.longitude))
                    .zoom(14.0)
                    .build()
            ),
            650
        )
    }
}

private fun distanceBetween(first: SharedLocation, second: SharedLocation): Double {
    val earthRadius = 6_371_000.0
    val lat1 = Math.toRadians(first.latitude)
    val lat2 = Math.toRadians(second.latitude)
    val deltaLat = Math.toRadians(second.latitude - first.latitude)
    val deltaLon = Math.toRadians(second.longitude - first.longitude)
    val a = sin(deltaLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
    return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun formatDistance(meters: Double): String = when {
    meters < 1_000 -> "${meters.roundToInt()} m"
    else -> "${"%.1f".format(meters / 1_000)} km"
}

private fun formatAge(milliseconds: Long): String = when {
    milliseconds < 60_000L -> "just now"
    milliseconds < 3_600_000L -> "${(milliseconds / 60_000L).toInt()}m"
    milliseconds < 86_400_000L -> "${(milliseconds / 3_600_000L).toInt()}h"
    else -> "${(milliseconds / 86_400_000L).toInt()}d"
}