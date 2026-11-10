package com.maenalyn.couples

import android.app.Application
import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.messaging.FirebaseMessaging
import com.maenalyn.couples.chat.SupabaseChatClientProvider
import com.maenalyn.couples.chat.SupabaseChatDiagnostics
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

class MainVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("us", Context.MODE_PRIVATE)
    private val pendingWriteCounter = AtomicInteger(0)
    private val authUid = MutableStateFlow(Repo.currentUid())
    private val firebaseAuthReady = MutableStateFlow(false)
    private val coupleRetryRequests = MutableStateFlow(0)
    private val locationServiceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLocationSharingPreferences()
        }
    }
    private var latestMemories: List<Memory> = emptyList()
    private val olderMemories = mutableListOf<Memory>()
    private var memoryReachedEnd = false
    private val loadingOlderMemories = java.util.concurrent.atomic.AtomicBoolean(false)
    private var dailyWatchJob: Job? = null
    private var dailyWatchCode: String? = null
    private val chatDiagnosticsStartedFor = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        accountEmail.value = firebaseAuth.currentUser?.email
        authUid.value = firebaseAuth.currentUser?.uid
        firebaseAuthReady.value = true
        myNickname.value = authUid.value?.let { couple.value?.memberNames?.get(it) }.orEmpty()
        val currentCode = code.value
        val uid = firebaseAuth.currentUser?.uid
        if (currentCode != null && uid != null) {
            savePushToken(currentCode, uid)
            if (couple.value == null && coupleLoadError.value != null) {
                coupleRetryRequests.value += 1
            }
        }
    }

    val code = MutableStateFlow(prefs.getString("code", null))
    val couple = MutableStateFlow<CoupleState.Active?>(null)
    val coupleLoadError = MutableStateFlow<String?>(null)
    val items = MutableStateFlow<List<Item>>(emptyList())
    val events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val notes = MutableStateFlow<List<LoveNote>>(emptyList())
    val memories = MutableStateFlow<List<Memory>>(emptyList())
    val memoryAlbums = MutableStateFlow<List<MemoryAlbum>>(emptyList())
    val memoryAlbumCovers = MutableStateFlow<Map<String, String>>(emptyMap())
    val activity = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val dailyUs = MutableStateFlow(DailyUsState())
    val sharedLocations = MutableStateFlow<List<SharedLocation>>(emptyList())
    val savedPlaces = MutableStateFlow<List<SavedPlace>>(emptyList())
    val placeEvents = MutableStateFlow<List<PlaceTransitionEvent>>(emptyList())
    val placeAlertsEnabled = MutableStateFlow(prefs.getBoolean("placeAlertsEnabled", false))
    val shareLocationEnabled = MutableStateFlow(prefs.getBoolean("shareLiveLocation", false))
    val keepLocationSharingInBackground = MutableStateFlow(
        prefs.getBoolean("shareLiveLocation", false) && prefs.getBoolean("keepLocationSharingInBackground", false)
    )
    val shareDurationMinutes = MutableStateFlow(prefs.getInt("shareLocationDurationMinutes", 0))
    val shareLocationExpiresAtMillis = MutableStateFlow(prefs.getLong("shareLocationExpiresAtMillis", 0L))
    val hasMoreMemories = MutableStateFlow(true)
    val loadingMoreMemories = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val accountEmail = MutableStateFlow(Repo.currentEmail())
    val pendingWrites = MutableStateFlow(0)
    val backupPreview = MutableStateFlow<BackupManager.BackupPreview?>(null)

    val themeMode = MutableStateFlow(prefs.getInt("themeMode", 0))
    val palette = MutableStateFlow(prefs.getInt("palette", 0))
    val showMilestones = MutableStateFlow(prefs.getBoolean("milestones", true))
    val showBreakdown = MutableStateFlow(prefs.getBoolean("breakdown", true))
    val animateHeart = MutableStateFlow(prefs.getBoolean("heart", true))
    val appTitle = MutableStateFlow(prefs.getString("title", "Us 💜") ?: "Us 💜")
    val myNickname = MutableStateFlow("")
    val partnerNotificationsEnabled = MutableStateFlow(prefs.getBoolean("partnerNotifications", true))
    val notifyLists = MutableStateFlow(prefs.getBoolean("notifyLists", true))
    val notifyEvents = MutableStateFlow(prefs.getBoolean("notifyEvents", true))
    val notifyNotes = MutableStateFlow(prefs.getBoolean("notifyNotes", true))
    val notifyMemories = MutableStateFlow(prefs.getBoolean("notifyMemories", true))
    val notifyDaily = MutableStateFlow(prefs.getBoolean("notifyDaily", true))
    val quietHoursEnabled = MutableStateFlow(prefs.getBoolean("quietHoursEnabled", false))
    val quietHoursStart = MutableStateFlow(prefs.getString("quietHoursStart", "22:00") ?: "22:00")
    val quietHoursEnd = MutableStateFlow(prefs.getString("quietHoursEnd", "07:00") ?: "07:00")
    val privateNotificationPreviews = MutableStateFlow(prefs.getBoolean("privateNotificationPreviews", false))
    val heartSizeDp = MutableStateFlow(40)
    val heartAnimationDurationMs = MutableStateFlow(2000)
    val appLockEnabled = MutableStateFlow(prefs.getBoolean("appLock", false))

    init {
        prefs.edit()
            .putInt("heartSizeDp", 40)
            .putInt("heartAnimationDurationMs", 2000)
            .apply()
        UsNotifications.createChannels(app)
        ContextCompat.registerReceiver(
            app,
            locationServiceStatusReceiver,
            IntentFilter(LocationSharingService.ACTION_STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener)
        viewModelScope.launch {
            code.collectLatest { c ->
                if (c == null) {
                    LivePhotoWidgetProvider.clearLocalCache(getApplication())
                    couple.value = null
                    coupleLoadError.value = null
                    myNickname.value = ""
                    items.value = emptyList()
                    events.value = emptyList()
                    notes.value = emptyList()
                    memories.value = emptyList()
                    memoryAlbums.value = emptyList()
                    memoryAlbumCovers.value = emptyMap()
                    activity.value = emptyList()
                    dailyWatchJob?.cancel()
                    dailyWatchCode = null
                    dailyUs.value = DailyUsState()
                    sharedLocations.value = emptyList()
                    savedPlaces.value = emptyList()
                    placeEvents.value = emptyList()
                    PlaceGeofenceManager.removeAll(getApplication())
                    placeAlertsEnabled.value = false
                    shareLocationEnabled.value = false
                    keepLocationSharingInBackground.value = false
                    prefs.edit().putBoolean("shareLiveLocation", false)
                        .putBoolean("keepLocationSharingInBackground", false).apply()
                    latestMemories = emptyList()
                    olderMemories.clear()
                    memoryReachedEnd = false
                    hasMoreMemories.value = true
                } else coroutineScope {
                    // FirebaseAuth restores the persisted account asynchronously. Do not attach
                    // member-only Firestore listeners until its initial state is known.
                    firebaseAuthReady.first { it }
                    if (authUid.value.isNullOrBlank()) {
                        coupleLoadError.value =
                            "Your saved sign-in isn't available on this phone. Your pairing code has been kept. Restore the account linked to this shared space."
                        authUid.first { !it.isNullOrBlank() }
                        coupleLoadError.value = null
                    }
                    registerPushToken(c)

                    // Pairing may be restored after a widget was already pinned. Its
                    // original AppWidget onUpdate then had no couple code and could not
                    // register periodic sync, so ensure the durable worker here as well.
                    LivePhotoWidgetProvider.ensureBackgroundSyncScheduled(getApplication(), c)

                    launch {
                        try {
                            LivePhotoWidgetRepo.observeCurrent(c).collectLatest {
                                LivePhotoWidgetSyncWorker.enqueue(getApplication(), c)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            message.value = error.message ?: "Live Photo Widget will refresh when the connection returns."
                        }
                    }
                    launch {
                        coupleRetryRequests.collectLatest {
                            coupleLoadError.value = null
                            val uid = authUid.value
                            if (uid.isNullOrBlank()) {
                                coupleLoadError.value =
                                    "Your saved sign-in isn't available on this phone. Your pairing code has been kept. Retry or use account recovery."
                                return@collectLatest
                            }
                            try {
                                Repo.observeCouple(c).collect { state ->
                                    when (state) {
                                        is CoupleState.Active -> {
                                            coupleLoadError.value = null
                                            if (
                                                BuildConfig.DEBUG &&
                                                SupabaseChatClientProvider.isConfigured &&
                                                chatDiagnosticsStartedFor.add(c)
                                            ) {
                                                launch {
                                                    SupabaseChatDiagnostics.verifyPrivateRoomAccess(c)
                                                }
                                            }
                                            couple.value = state
                                            appTitle.value = state.coupleName
                                            myNickname.value = authUid.value?.let { state.memberNames[it] }.orEmpty()
                                            prefs.edit()
                                                .putString("title", state.coupleName)
                                                .putLong("anniversaryCache", state.anniversaryEpochDay ?: DEFAULT_ANNIVERSARY)
                                                .apply()
                                            DaysTogetherWidgetProvider.updateAll(getApplication())
                                        }
                                        CoupleState.Gone -> {
                                            clearPairing()
                                            message.value = "Your shared space no longer exists. You're now unpaired."
                                        }
                                        is CoupleState.Unavailable -> {
                                            if (couple.value == null) {
                                                coupleLoadError.value = state.message
                                            } else {
                                                message.value = state.message
                                            }
                                        }
                                    }
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                coupleLoadError.value =
                                    "Couldn't verify your shared space. Your pairing is still saved. Please retry."
                            }
                        }
                    }
                    launch { Repo.observeItems(c).collect { items.value = it } }
                    launch {
                        FeatureRepo.observeEvents(c).collect { value ->
                            events.value = value
                            ReminderScheduler.refresh(getApplication(), c, value)
                        }
                    }
                    launch { FeatureRepo.observeNotes(c).collect { notes.value = it } }
                    launch { FeatureRepo.observeActivity(c).collect { activity.value = it } }
                    launch {
                        try {
                            LocationRepo.observeLocations(c).collect { sharedLocations.value = it }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            message.value = error.message ?: "Couldn't load shared locations. Check your connection."
                        }
                    }
                    launch {
                        try {
                            PlacesRepo.observePlaces(c).collect { places ->
                                savedPlaces.value = places
                                if (placeAlertsEnabled.value) {
                                    PlaceGeofenceManager.sync(getApplication(), c, places)
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            message.value = error.message ?: "Couldn't load shared places. Check your connection."
                        }
                    }
                    launch {
                        try {
                            PlacesRepo.observeEvents(c).collect { events ->
                                placeEvents.value = events
                                val uid = Repo.currentUid()
                                if (uid != null && placeAlertsEnabled.value) {
                                    val partnerEvents = events.filter {
                                        it.actorUid != uid &&
                                            it.createdAtMillis > System.currentTimeMillis() - 10 * 60_000L
                                    }
                                    partnerEvents.forEach { event ->
                                        val partnerName = couple.value?.memberNames?.get(event.actorUid)
                                            .orEmpty().ifBlank { "Your person" }
                                        UsNotifications.showPlaceAlert(
                                            getApplication(), event.eventKey, event.placeName,
                                            event.transition == "entered", partnerName
                                        )
                                    }
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            message.value = error.message ?: "Couldn't load place activity. Check your connection."
                        }
                    }
                    launch {
                        FeatureRepo.observeMemoryAlbums(c).collect { memoryAlbums.value = it }
                    }
                    launch {
                        FeatureRepo.observeMemoryAlbumCovers(c).collect { memoryAlbumCovers.value = it }
                    }
                    launch {
                        FeatureRepo.observeMemories(c).collect { latest ->
                            latestMemories = latest
                            val latestIds = latest.mapTo(mutableSetOf()) { it.id }
                            memories.value = latest + olderMemories.filter { it.id !in latestIds }
                            hasMoreMemories.value = !memoryReachedEnd && latest.size >= FeatureRepo.MEMORY_PAGE_SIZE.toInt()
                        }
                    }
                    launch {
                        authUid.collectLatest { uid ->
                            if (uid != null) {
                                PartnerActivityNotifications.observe(
                                    context = getApplication(),
                                    coupleCode = c,
                                    currentUid = uid,
                                    enabled = { partnerNotificationsEnabled.value }
                                ).collect { }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun registerPushToken(c: String) {
        val uid = Repo.currentUid() ?: return
        savePushToken(c, uid)
    }

    private fun savePushToken(c: String, uid: String) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("couples").document(c).collection("devices").document(uid)
                .set(mapOf(
                    "uid" to uid,
                    "token" to token,
                    "updatedAt" to System.currentTimeMillis(),
                    "notificationsEnabled" to partnerNotificationsEnabled.value
                ))
        }
    }

    fun retryCoupleCheck() {
        if (code.value != null) coupleRetryRequests.value += 1
    }

    private fun savePairing(c: String) {
        coupleLoadError.value = null
        prefs.edit().putString("code", c).apply()
        code.value = c
        registerPushToken(c)
        DaysTogetherWidgetProvider.updateAll(getApplication())
    }

    private fun clearPairing() {
        val oldCode = code.value
        LocationSharingService.stop(getApplication(), disableSharing = true)
        if (oldCode != null) {
            Repo.currentUid()?.let { LocationRepo.remove(oldCode, it) }
            androidx.work.WorkManager.getInstance(getApplication()).cancelAllWorkByTag("calendar-reminder-$oldCode")
            androidx.work.WorkManager.getInstance(getApplication()).cancelAllWorkByTag("memory-upload-$oldCode")
        }
        File(getApplication<Application>().filesDir, "memories").deleteRecursively()
        File(getApplication<Application>().filesDir, "memory_thumbnails").deleteRecursively()
        dailyWatchJob?.cancel()
        dailyWatchCode = null
        dailyUs.value = DailyUsState()
        sharedLocations.value = emptyList()
        savedPlaces.value = emptyList()
        placeEvents.value = emptyList()
        PlaceGeofenceManager.removeAll(getApplication())
        placeAlertsEnabled.value = false
        prefs.edit().putBoolean("placeAlertsEnabled", false).apply()
        shareLocationEnabled.value = false
        keepLocationSharingInBackground.value = false
        prefs.edit().putBoolean("shareLiveLocation", false)
            .putBoolean("keepLocationSharingInBackground", false)
            .putLong("shareLocationExpiresAtMillis", 0L).apply()
        shareLocationExpiresAtMillis.value = 0L
        LocationShareExpirationWorker.cancel(getApplication())
        activity.value = emptyList()
        prefs.edit().remove("code").remove("anniversaryCache").apply()
        code.value = null
        DaysTogetherWidgetProvider.updateAll(getApplication())
    }

    private fun authError(error: FirebaseAuthException): String = when (error.errorCode) {
        "ERROR_INVALID_EMAIL" -> "Enter a valid email address."
        "ERROR_EMAIL_ALREADY_IN_USE", "ERROR_CREDENTIAL_ALREADY_IN_USE" ->
            "That email is already linked to another account. Use account recovery instead."
        "ERROR_WEAK_PASSWORD" -> "Choose a stronger password with at least 6 characters."
        "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND" ->
            "Email or password is incorrect."
        "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Please try again later."
        "ERROR_OPERATION_NOT_ALLOWED" -> "Email/password sign-in is not enabled in Firebase."
        "ERROR_PROVIDER_ALREADY_LINKED" -> "An email sign-in method is already linked to this account."
        "ERROR_NETWORK_REQUEST_FAILED" -> "Couldn't connect. Check your internet and try again."
        else -> "Account error. Please check your details and try again."
    }

    private fun firestoreError(error: Exception?): String = when (error) {
        is FirebaseFirestoreException -> when (error.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "You don't have permission to make this change. Check your pairing and Firebase rules."
            FirebaseFirestoreException.Code.UNAVAILABLE -> "You're offline. Your change will retry when you reconnect."
            FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
                "This operation needs a valid Firestore configuration."
            else -> "Couldn't save your change (${error.code}). Please try again."
        }
        else -> "Couldn't save your change. Check your connection and try again."
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            message.value = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PairingException) {
                message.value = e.message
            } catch (e: FirebaseAuthException) {
                message.value = authError(e)
            } catch (e: FirebaseFirestoreException) {
                message.value = firestoreError(e)
            } catch (e: Exception) {
                message.value = e.message ?: "Something went wrong. Please try again."
            } finally {
                busy.value = false
            }
        }
    }

    private fun trackWrite(task: Task<*>) {
        pendingWrites.value = pendingWriteCounter.incrementAndGet()
        task.addOnCompleteListener { result ->
            pendingWrites.value = pendingWriteCounter.decrementAndGet().coerceAtLeast(0)
            if (!result.isSuccessful) message.value = firestoreError(result.exception)
        }
    }

    fun watchDailyUs(dateKey: String) {
        val c = code.value ?: return
        if (dailyUs.value.dateKey == dateKey && dailyWatchCode == c && dailyWatchJob?.isActive == true) return
        dailyUs.value = DailyUsState(dateKey = dateKey)
        dailyWatchJob?.cancel()
        dailyWatchCode = c
        dailyWatchJob = viewModelScope.launch {
            couple.collectLatest { active ->
                if (active == null) return@collectLatest
                val uid = Repo.currentUid() ?: return@collectLatest
                val partnerUid = active.memberIds.firstOrNull { it != uid }
                try {
                    runCatching { DailyUsRepo.ensureDay(c, dateKey) }
                        .onFailure { error -> message.value = error.message ?: "Today's prompt will sync when you're online." }
                    DailyUsRepo.observeDay(c, dateKey, uid, partnerUid).collect { dailyUs.value = it }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    message.value = error.message ?: "Couldn't load today's Daily Us prompt. Check your connection."
                }
            }
        }
    }

    fun saveDailyAnswer(answer: String) {
        val value = answer.trim()
        if (value.isBlank()) {
            message.value = "Write a little answer before saving."
            return
        }
        if (value.length > 1000) {
            message.value = "Keep your answer to 1,000 characters or fewer."
            return
        }
        val c = code.value ?: return
        val uid = Repo.currentUid() ?: return
        val key = dailyUs.value.dateKey
        if (key.isBlank()) return
        trackWrite(DailyUsRepo.saveAnswer(c, uid, key, value, dailyUs.value.myAnswerCreatedAt))
    }

    fun saveDailyCheckin(mood: String, note: String) {
        if (mood !in DailyUsRepo.moods || note.length > 240) {
            message.value = "Choose a mood and keep your note to 240 characters or fewer."
            return
        }
        val c = code.value ?: return
        val uid = Repo.currentUid() ?: return
        val key = dailyUs.value.dateKey
        if (key.isBlank()) return
        trackWrite(DailyUsRepo.saveCheckin(c, uid, key, mood, note))
    }

    fun setPlaceAlertsEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled && !PlaceGeofenceManager.hasRequiredPermissions(app)) {
            message.value = "Allow precise location and Allow all the time before enabling place alerts."
            return
        }
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            message.value = "Allow notifications before enabling arrival and departure alerts."
            return
        }
        placeAlertsEnabled.value = enabled
        prefs.edit().putBoolean("placeAlertsEnabled", enabled).apply()
        val currentCode = code.value
        if (enabled && !currentCode.isNullOrBlank()) {
            PlaceGeofenceManager.sync(app, currentCode, savedPlaces.value)
        } else {
            PlaceGeofenceManager.removeAll(app)
        }
    }

    fun refreshPlaceAlertRegistration() {
        if (!placeAlertsEnabled.value) return
        val currentCode = code.value ?: return
        PlaceGeofenceManager.sync(getApplication(), currentCode, savedPlaces.value)
    }

    fun savePlace(name: String, latitude: Double, longitude: Double, radiusMeters: Int) {
        run {
            val currentCode = code.value ?: throw PairingException("Pair your phones before saving shared places.")
            val uid = Repo.currentUid() ?: throw PairingException("Your account isn't ready. Please try again.")
            PlacesRepo.addPlace(currentCode, uid, name, latitude, longitude, radiusMeters)
            message.value = "Saved $name to your shared places."
        }
    }

    fun deletePlace(placeId: String) {
        run {
            val currentCode = code.value ?: return@run
            PlacesRepo.deletePlace(currentCode, placeId)
            message.value = "Shared place removed."
        }
    }

    fun setLocationSharingDuration(minutes: Int) {
        if (minutes !in setOf(0, 15, 60)) return
        shareDurationMinutes.value = minutes
        val now = System.currentTimeMillis()
        val expiresAt = if (shareLocationEnabled.value && minutes > 0) now + minutes * 60_000L else 0L
        shareLocationExpiresAtMillis.value = expiresAt
        prefs.edit()
            .putInt("shareLocationDurationMinutes", minutes)
            .putLong("shareLocationExpiresAtMillis", expiresAt)
            .apply()
        if (expiresAt > 0L) LocationShareExpirationWorker.schedule(getApplication(), expiresAt)
        else LocationShareExpirationWorker.cancel(getApplication())
    }

    fun setLocationSharingEnabled(enabled: Boolean) {
        shareLocationEnabled.value = enabled
        val minutes = shareDurationMinutes.value
        val expiresAt = if (enabled && minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        shareLocationExpiresAtMillis.value = expiresAt
        prefs.edit()
            .putBoolean("shareLiveLocation", enabled)
            .putLong("shareLocationExpiresAtMillis", expiresAt)
            .apply()
        if (expiresAt > 0L) LocationShareExpirationWorker.schedule(getApplication(), expiresAt)
        else LocationShareExpirationWorker.cancel(getApplication())
        if (!enabled) {
            keepLocationSharingInBackground.value = false
            prefs.edit().putBoolean("keepLocationSharingInBackground", false).apply()
            LocationSharingService.stop(getApplication(), disableSharing = true)
            removeMySharedLocation()
        }
    }

    fun setKeepLocationSharingInBackground(enabled: Boolean) {
        val app = getApplication<Application>()
        if (!enabled) {
            keepLocationSharingInBackground.value = false
            prefs.edit().putBoolean("keepLocationSharingInBackground", false).apply()
            LocationSharingService.stop(app, disableSharing = false)
            return
        }

        val currentCode = code.value
        val uid = Repo.currentUid()
        if (!shareLocationEnabled.value || currentCode.isNullOrBlank() || uid.isNullOrBlank()) {
            message.value = "Turn on live location sharing first."
            return
        }
        val hasLocation = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            message.value = "Allow location access before enabling screen-off sharing."
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            message.value = "Allow notifications so Android can show the required live-sharing notification."
            return
        }

        keepLocationSharingInBackground.value = true
        prefs.edit().putBoolean("keepLocationSharingInBackground", true).apply()
        try {
            LocationSharingService.start(app, currentCode, uid)
        } catch (_: Exception) {
            keepLocationSharingInBackground.value = false
            prefs.edit().putBoolean("keepLocationSharingInBackground", false).apply()
            message.value = "Couldn't start background sharing. Check location and notification permissions."
        }
    }

    fun refreshLocationSharingPreferences() {
        shareLocationEnabled.value = prefs.getBoolean("shareLiveLocation", false)
        shareDurationMinutes.value = prefs.getInt("shareLocationDurationMinutes", 0)
        shareLocationExpiresAtMillis.value = prefs.getLong("shareLocationExpiresAtMillis", 0L)
        if (shareLocationEnabled.value && shareLocationExpiresAtMillis.value > 0L) {
            if (shareLocationExpiresAtMillis.value <= System.currentTimeMillis()) {
                setLocationSharingEnabled(false)
                return
            }
            LocationShareExpirationWorker.schedule(getApplication(), shareLocationExpiresAtMillis.value)
        }
        keepLocationSharingInBackground.value = shareLocationEnabled.value &&
            prefs.getBoolean("keepLocationSharingInBackground", false)
        if (keepLocationSharingInBackground.value) {
            val currentCode = code.value
            val uid = Repo.currentUid()
            if (!currentCode.isNullOrBlank() && !uid.isNullOrBlank()) {
                runCatching { LocationSharingService.start(getApplication(), currentCode, uid) }
            }
        }
    }

    fun publishMyLocation(location: Location) {
        val c = code.value ?: return
        val uid = Repo.currentUid() ?: return
        LocationRepo.publish(c, uid, location).addOnFailureListener {
            message.value = "Couldn't update your shared location. Check your connection."
        }
    }

    fun removeMySharedLocation() {
        val c = code.value ?: return
        val uid = Repo.currentUid() ?: return
        LocationRepo.remove(c, uid)
    }

    fun generate() = run { savePairing(Repo.createCouple(Repo.ensureSignedIn())) }
    fun join(raw: String) = run { savePairing(Repo.joinCouple(Repo.ensureSignedIn(), raw)) }

    fun cancelPendingInvite() = run {
        val currentCode = code.value ?: return@run
        if (Repo.cancelPendingInvite(currentCode)) {
            clearPairing()
            message.value = null
        } else {
            message.value = "Your partner has already joined. Your shared space is ready."
        }
    }

    fun linkRecoveryEmail(email: String, password: String) = run {
        accountEmail.value = Repo.linkRecoveryEmail(email, password)
        message.value = "Account recovery is set up. Keep your password somewhere safe."
    }

    fun restoreAccount(email: String, password: String) = run {
        val (restoredCode, restoredEmail) = Repo.restoreCouple(email, password)
        accountEmail.value = restoredEmail
        savePairing(restoredCode)
        message.value = "Your account and shared data have been restored."
    }

    fun recoverCurrentPairing() = run {
        firebaseAuthReady.first { it }
        val restoredCode = Repo.recoverCurrentCouple()
        savePairing(restoredCode)
        message.value = "Your existing shared space has been restored."
    }

    fun requestDataDeletion() = run {
        val c = code.value ?: return@run
        val allApproved = Repo.approveDataDeletion(c)
        if (allApproved) {
            Repo.unpair(c)
            clearPairing()
            message.value = "Your shared space and its data were deleted."
        } else {
            message.value = "Your approval is saved. Your partner must approve before anything is deleted."
        }
    }

    fun unpair() = requestDataDeletion()

    fun addItem(type: String, text: String) {
        val c = code.value ?: return
        val value = text.trim()
        if (value.isBlank()) return
        if (value.length > 500) {
            message.value = "Keep list items to 500 characters or fewer."
            return
        }
        val uid = Repo.currentUid() ?: return
        trackWrite(Repo.addItem(c, uid, type, value))
    }

    fun toggle(item: Item) {
        val c = code.value ?: return
        val uid = Repo.currentUid() ?: return
        trackWrite(Repo.setDone(c, item.id, uid, !item.done))
    }
    fun remove(item: Item) { code.value?.let { trackWrite(Repo.deleteItem(it, item.id)) } }
    fun setAnniversary(epochDay: Long) {
        code.value?.let {
            trackWrite(Repo.setAnniversary(it, epochDay))
            prefs.edit().putLong("anniversaryCache", epochDay).apply()
            DaysTogetherWidgetProvider.updateAll(getApplication())
        }
    }

    fun addEvent(draft: CalendarEventDraft) {
        val c = code.value ?: return
        val uid = Repo.currentUid() ?: return
        if (draft.title.isBlank() || draft.title.length > 80 || draft.description.length > 500) {
            message.value = "Add a title (up to 80 characters) and keep details under 500 characters."
            return
        }
        trackWrite(FeatureRepo.addEvent(c, uid, draft))
    }

    fun updateEvent(id: String, draft: CalendarEventDraft) {
        val c = code.value ?: return
        if (draft.title.isBlank() || draft.title.length > 80 || draft.description.length > 500) {
            message.value = "Add a title (up to 80 characters) and keep details under 500 characters."
            return
        }
        val uid = Repo.currentUid() ?: return
        trackWrite(FeatureRepo.updateEvent(c, id, uid, draft))
    }

    fun deleteEvent(id: String) { code.value?.let { trackWrite(FeatureRepo.deleteEvent(it, id)) } }

    fun addNote(title: String, text: String) {
        val c = code.value ?: return
        val uid = Repo.currentUid() ?: return
        if (text.isBlank() || title.length > 80 || text.length > 2000) {
            message.value = "Write a note (up to 2,000 characters)."
            return
        }
        trackWrite(FeatureRepo.addNote(c, uid, title, text))
    }

    fun pinNote(note: LoveNote, pinned: Boolean) {
        code.value?.let { trackWrite(FeatureRepo.setNotePinned(it, note.id, pinned)) }
    }
    fun deleteNote(note: LoveNote) { code.value?.let { trackWrite(FeatureRepo.deleteNote(it, note.id)) } }

    fun addMemories(uris: List<Uri>, albumId: String, albumName: String?, caption: String) {
        val selectedUris = uris.distinct()
        if (selectedUris.isEmpty()) return
        viewModelScope.launch {
            val c = code.value
            if (c.isNullOrBlank()) {
                message.value = "Pair your account first."
                return@launch
            }
            val uid = Repo.currentUid()
            if (uid.isNullOrBlank()) {
                message.value = "Your account isn't ready. Please try again."
                return@launch
            }
            if (caption.length > 280) {
                message.value = "Keep captions under 280 characters."
                return@launch
            }

            try {
                if (!albumName.isNullOrBlank()) {
                    trackWrite(FeatureRepo.createMemoryAlbum(c, albumId, albumName, uid))
                }
                var saved = 0
                var failed = 0
                selectedUris.forEach { uri ->
                    val id = UUID.randomUUID().toString()
                    val fileName = "$id.jpg"
                    val folder = File(getApplication<Application>().filesDir, "memories").apply { mkdirs() }
                    val file = File(folder, fileName)
                    try {
                        compressMemoryPhoto(uri, file)
                        val createdAt = System.currentTimeMillis()
                        trackWrite(FeatureRepo.createMemoryPending(c, id, uid, caption, fileName, albumId, createdAt))
                        MemoryWorkScheduler.upload(getApplication(), c, id, uid, fileName, createdAt)
                        saved++
                    } catch (error: Exception) {
                        file.delete()
                        failed++
                    }
                }
                message.value = when {
                    saved == selectedUris.size -> "$saved photos added to the album. They'll sync when you're online."
                    saved > 0 -> "$saved of ${selectedUris.size} photos were prepared. $failed couldn't be added."
                    else -> "Couldn't prepare these photos. Please choose them again."
                }
            } catch (error: Exception) {
                message.value = error.message ?: "Couldn't create the album. Please try again."
            }
        }
    }

    fun renameMemoryAlbum(albumId: String, name: String) {
        val safeName = name.trim()
        if (safeName.isBlank()) {
            message.value = "Enter an album name."
            return
        }
        if (safeName.length > 48) {
            message.value = "Keep album names to 48 characters or fewer."
            return
        }
        val c = code.value ?: return
        val uid = Repo.currentUid() ?: return
        trackWrite(FeatureRepo.renameMemoryAlbum(c, albumId, safeName, uid))
    }

    fun moveMemoriesToAlbum(memoryIds: List<String>, albumId: String) {
        val c = code.value ?: return
        if (memoryIds.isEmpty()) return
        viewModelScope.launch {
            try {
                memoryIds.distinct().chunked(450).forEach { batch ->
                    FeatureRepo.moveMemories(c, batch, albumId).await()
                }
                message.value = "${memoryIds.distinct().size} photos moved to the selected album."
            } catch (error: Exception) {
                message.value = error.message ?: "Couldn't move the selected photos."
            }
        }
    }

    fun deleteMemories(selected: List<Memory>) {
        val c = code.value ?: return
        if (selected.isEmpty()) return
        viewModelScope.launch {
            var deleted = 0
            try {
                selected.distinctBy { it.id }.forEach { memory ->
                    MemoryWorkScheduler.cancel(getApplication(), c, memory.id)
                    FeatureRepo.deleteMemory(c, memory.id).await()
                    latestMemories = latestMemories.filterNot { it.id == memory.id }
                    olderMemories.removeAll { it.id == memory.id }
                    memories.value = memories.value.filterNot { it.id == memory.id }
                    File(File(getApplication<Application>().filesDir, "memories"), memory.fileName).delete()
                    File(File(getApplication<Application>().filesDir, "memory_thumbnails"), "${memory.id}.jpg").delete()
                    deleted++
                }
                message.value = "$deleted photos deleted."
            } catch (error: Exception) {
                message.value = if (deleted > 0) "$deleted photos deleted before an error occurred: ${error.message ?: "Please try again."}"
                    else error.message ?: "Couldn't delete the selected photos."
            }
        }
    }

    private suspend fun compressMemoryPhoto(uri: Uri, destination: File) = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw PairingException("This image format can't be opened. Choose a JPEG, PNG, or WebP photo.")
        }

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 1600) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.RGB_565 }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw PairingException("Couldn't read that photo. Choose it again.")

        try {
            if (maxOf(bitmap.width, bitmap.height) > 1600) {
                val ratio = 1600f / maxOf(bitmap.width, bitmap.height)
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
                bitmap.recycle()
                bitmap = scaled
            }

            while (true) {
                var accepted: ByteArray? = null
                for (quality in listOf(86, 78, 70, 62, 55)) {
                    val output = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    val bytes = output.toByteArray()
                    if (bytes.size <= MAX_MEMORY_IMAGE_BYTES) {
                        accepted = bytes
                        break
                    }
                }
                if (accepted != null) {
                    destination.writeBytes(accepted)
                    return@withContext
                }

                if (minOf(bitmap.width, bitmap.height) <= 480) {
                    throw PairingException("This photo is too detailed to fit the free 700 KB memory limit. Choose a smaller image.")
                }
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * 0.82f).toInt().coerceAtLeast(1),
                    (bitmap.height * 0.82f).toInt().coerceAtLeast(1),
                    true
                )
                bitmap.recycle()
                bitmap = scaled
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    suspend fun loadMemoryImage(memory: Memory): File? {
        val folder = File(getApplication<Application>().filesDir, "memories")
        val target = File(folder, memory.fileName)
        if (target.exists() && target.length() > 0L) return target
        val c = code.value ?: return null
        if (memory.storageMode != "firestore_blob") return null
        return try {
            val snapshot = FeatureRepo.getMemoryBlob(c, memory.id).await()
            val bytes = snapshot.getBlob("image")?.toBytes() ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_MEMORY_IMAGE_BYTES) return null
            withContext(Dispatchers.IO) {
                folder.mkdirs()
                target.writeBytes(bytes)
                target
            }
        } catch (error: Exception) {
            message.value = "Couldn't load this memory. Check your connection and try again."
            null
        }
    }

    suspend fun loadMemoryThumbnail(memory: Memory): File? {
        val context = getApplication<Application>()
        val folder = File(context.filesDir, "memory_thumbnails").apply { mkdirs() }
        val target = File(folder, "${memory.id}.jpg")
        if (target.exists() && target.length() > 0L) return target
        val c = code.value ?: return null
        if (memory.storageMode != "firestore_blob") return loadMemoryImage(memory)
        return try {
            val snapshot = FeatureRepo.getMemoryThumbnail(c, memory.id).await()
            val bytes = snapshot.getBlob("image")?.toBytes() ?: return null
            if (bytes.isEmpty() || bytes.size > 80 * 1024) return null
            withContext(Dispatchers.IO) { target.writeBytes(bytes); target }
        } catch (_: Exception) {
            null
        }
    }

    fun loadMoreMemories() {
        val c = code.value ?: return
        if (!hasMoreMemories.value || !loadingOlderMemories.compareAndSet(false, true)) return
        viewModelScope.launch {
            loadingMoreMemories.value = true
            try {
                val older = FeatureRepo.loadOlderMemories(c)
                val known = memories.value.mapTo(mutableSetOf()) { it.id }
                olderMemories.addAll(older.filter { it.id !in known })
                val latestIds = latestMemories.mapTo(mutableSetOf()) { it.id }
                memories.value = latestMemories + olderMemories.filter { it.id !in latestIds }
                memoryReachedEnd = older.size < FeatureRepo.MEMORY_PAGE_SIZE.toInt()
                hasMoreMemories.value = !memoryReachedEnd
            } catch (error: Exception) {
                message.value = "Couldn't load older memories. Connect to the internet and try again."
            } finally {
                loadingMoreMemories.value = false
                loadingOlderMemories.set(false)
            }
        }
    }

    fun setMemoryAlbumCover(albumId: String, memory: Memory) {
        val c = code.value ?: return
        val uid = Repo.currentUid()

        if (uid.isNullOrBlank()) {
            message.value = "Your account isn't ready. Please try again."
            return
        }

        trackWrite(FeatureRepo.setMemoryAlbumCover(c, albumId, memory.id, uid))
    }

    fun toggleMemoryFavorite(memory: Memory) {
        val c = code.value ?: return
        trackWrite(FeatureRepo.setMemoryFavorite(c, memory.id, !memory.favorite))
    }

    fun saveMemoryTo(destination: Uri, memory: Memory) {
        viewModelScope.launch {
            try {
                val file = loadMemoryImage(memory) ?: throw PairingException("Connect to the internet to download this photo.")
                withContext(Dispatchers.IO) {
                    val output = getApplication<Application>().contentResolver.openOutputStream(destination)
                        ?: throw PairingException("Couldn't open the selected destination.")
                    output.use { it.write(file.readBytes()) }
                }
                message.value = "Photo saved."
            } catch (error: Exception) {
                message.value = error.message ?: "Couldn't save this photo."
            }
        }
    }

    fun deleteMemory(memory: Memory) {
        code.value?.let { c ->
            MemoryWorkScheduler.cancel(getApplication(), c, memory.id)
            trackWrite(FeatureRepo.deleteMemory(c, memory.id))
            latestMemories = latestMemories.filterNot { it.id == memory.id }
            olderMemories.removeAll { it.id == memory.id }
            memories.value = memories.value.filterNot { it.id == memory.id }
            File(File(getApplication<Application>().filesDir, "memories"), memory.fileName).delete()
            File(File(getApplication<Application>().filesDir, "memory_thumbnails"), "${memory.id}.jpg").delete()
        }
    }

    fun exportBackup(destination: Uri, password: String) = run {
        val c = code.value ?: return@run
        BackupManager.export(getApplication(), c, destination, password)
        message.value = "Encrypted backup saved. Keep the password safe; it cannot be recovered."
    }

    fun inspectBackup(source: Uri, password: String) = run {
        val c = code.value ?: return@run
        backupPreview.value = BackupManager.preview(getApplication(), c, source, password)
    }

    fun clearBackupPreview() {
        backupPreview.value = null
    }

    fun restoreBackup(source: Uri, password: String) = run {
        val c = code.value ?: return@run
        val result = BackupManager.restore(getApplication(), c, source, password)
        backupPreview.value = null
        message.value = "Restore complete: ${result.restoredRecords} records, ${result.restoredMemories} photos. ${result.skippedExisting} existing items were skipped."
    }

    fun renameMyNickname(value: String) {
        val nickname = value.trim()
        if (nickname.isBlank()) {
            message.value = "Enter the nickname your partner should see."
            return
        }
        if (nickname.length > 24) {
            message.value = "Keep your nickname to 24 characters or fewer."
            return
        }
        val c = code.value ?: return
        val uid = Repo.currentUid() ?: return
        trackWrite(Repo.setMemberNickname(c, uid, nickname))
    }

    fun renameCouple(value: String) {
        val name = value.trim()
        if (name.isBlank()) {
            message.value = "Enter a name for your shared space."
            return
        }
        if (name.length > 32) {
            message.value = "Keep your shared name to 32 characters or fewer."
            return
        }
        val c = code.value ?: return
        trackWrite(Repo.setCoupleName(c, name))
    }

    fun setPartnerNotifications(enabled: Boolean) {
        prefs.edit().putBoolean("partnerNotifications", enabled).apply()
        partnerNotificationsEnabled.value = enabled
        val c = code.value
        val uid = Repo.currentUid()
        if (c != null && uid != null) savePushToken(c, uid)
    }

    fun setNotificationCategory(key: String, enabled: Boolean) {
        val safeKey = when (key) { "lists" -> "notifyLists"; "events" -> "notifyEvents"; "notes" -> "notifyNotes"; "memories" -> "notifyMemories"; "daily" -> "notifyDaily"; else -> return }
        prefs.edit().putBoolean(safeKey, enabled).apply()
        when (key) {
            "lists" -> notifyLists.value = enabled
            "events" -> notifyEvents.value = enabled
            "notes" -> notifyNotes.value = enabled
            "memories" -> notifyMemories.value = enabled
            "daily" -> notifyDaily.value = enabled
        }
    }

    fun setQuietHours(enabled: Boolean, start: String = quietHoursStart.value, end: String = quietHoursEnd.value) {
        val timePattern = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
        if (enabled && (!timePattern.matches(start) || !timePattern.matches(end))) {
            message.value = "Enter quiet hours in 24-hour time, such as 22:00 and 07:00."
            return
        }
        prefs.edit().putBoolean("quietHoursEnabled", enabled).putString("quietHoursStart", start).putString("quietHoursEnd", end).apply()
        quietHoursEnabled.value = enabled
        quietHoursStart.value = start
        quietHoursEnd.value = end
    }

    fun setPrivateNotificationPreviews(enabled: Boolean) {
        prefs.edit().putBoolean("privateNotificationPreviews", enabled).apply()
        privateNotificationPreviews.value = enabled
    }

    fun setThemeMode(i: Int) { prefs.edit().putInt("themeMode", i).apply(); themeMode.value = i }
    fun setPalette(i: Int) { prefs.edit().putInt("palette", i).apply(); palette.value = i }
    fun setShowMilestones(v: Boolean) { prefs.edit().putBoolean("milestones", v).apply(); showMilestones.value = v }
    fun setShowBreakdown(v: Boolean) { prefs.edit().putBoolean("breakdown", v).apply(); showBreakdown.value = v }
    fun setAnimateHeart(v: Boolean) { prefs.edit().putBoolean("heart", v).apply(); animateHeart.value = v }
    fun setAppLock(v: Boolean) { prefs.edit().putBoolean("appLock", v).apply(); appLockEnabled.value = v }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(locationServiceStatusReceiver) }
        FirebaseAuth.getInstance().removeAuthStateListener(authStateListener)
        super.onCleared()
    }

}
