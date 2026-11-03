package com.maenalyn.couples

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.workDataOf
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

object UsNotifications {
    const val CHANNEL_ID = "us_updates"
    private const val PARTNER_CHANNEL_ID = "us_partner_activity"
    private const val REMINDER_CHANNEL_ID = "us_reminders"
    private const val PARTNER_DND_CHANNEL_ID = "us_partner_activity_dnd"
    private const val REMINDER_DND_CHANNEL_ID = "us_reminders_dnd"
    private const val PLACE_ALERT_CHANNEL_ID = "us_saved_place_alerts"

    fun hasDndAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.isNotificationPolicyAccessGranted
    }

    fun isDndActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching {
            when (manager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL,
                NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> false
                else -> true
            }
        }.getOrDefault(false)
    }

    fun openDndAccessSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Shared updates", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Updates from your partner" }
            )
            manager.createNotificationChannel(
                NotificationChannel(PARTNER_CHANNEL_ID, "Partner activity", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Heads-up alerts when your partner adds something to Us" }
            )
            manager.createNotificationChannel(
                NotificationChannel(REMINDER_CHANNEL_ID, "Calendar reminders", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Reminders for your shared calendar" }
            )
            manager.createNotificationChannel(
                NotificationChannel(PLACE_ALERT_CHANNEL_ID, "Saved place alerts", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Arrival and departure updates from your partner at shared places" }
            )
            val dndBypassEnabled = hasDndAccess(context) &&
                context.getSharedPreferences("us", Context.MODE_PRIVATE)
                    .getBoolean("bypassDndNotifications", false)
            if (dndBypassEnabled) {
                manager.createNotificationChannel(
                    NotificationChannel(PARTNER_DND_CHANNEL_ID, "Important partner alerts", NotificationManager.IMPORTANCE_HIGH)
                        .apply {
                            description = "Partner activity alerts that you allow to interrupt Do Not Disturb"
                            setBypassDnd(true)
                        }
                )
                manager.createNotificationChannel(
                    NotificationChannel(REMINDER_DND_CHANNEL_ID, "Important calendar reminders", NotificationManager.IMPORTANCE_HIGH)
                        .apply {
                            description = "Calendar reminders that you allow to interrupt Do Not Disturb"
                            setBypassDnd(true)
                        }
                )
            } else {
                manager.deleteNotificationChannel(PARTNER_DND_CHANNEL_ID)
                manager.deleteNotificationChannel(REMINDER_DND_CHANNEL_ID)
            }
        }
    }

    fun showPartnerUpdate(
        context: Context,
        title: String,
        body: String,
        notificationKey: String,
        category: String = "other"
    ) {
        val prefs = context.getSharedPreferences("us", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("partnerNotifications", true)) return
        val categoryEnabled = when (category) {
            "lists" -> prefs.getBoolean("notifyLists", true)
            "events" -> prefs.getBoolean("notifyEvents", true)
            "notes" -> prefs.getBoolean("notifyNotes", true)
            "memories" -> prefs.getBoolean("notifyMemories", true)
            "daily" -> prefs.getBoolean("notifyDaily", true)
            else -> true
        }
        if (!categoryEnabled || isQuietHours(prefs)) return
        val safeTitle = if (prefs.getBoolean("privateNotificationPreviews", false)) "A little update from your person" else title
        val safeBody = if (prefs.getBoolean("privateNotificationPreviews", false)) "Open Us to see what changed." else body
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val seen = prefs.getStringSet("partnerNotificationIds", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!seen.add(notificationKey)) return
        while (seen.size > 120) seen.remove(seen.first())
        prefs.edit().putStringSet("partnerNotificationIds", seen).apply()
        show(context, safeTitle, safeBody, notificationKey.hashCode(), partner = true)
    }

    fun showPlaceAlert(
        context: Context,
        eventKey: String,
        placeName: String,
        entered: Boolean,
        partnerName: String
    ) {
        val prefs = context.getSharedPreferences("us", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("placeAlertsEnabled", false)) return
        if (!prefs.getBoolean("partnerNotifications", true) || isQuietHours(prefs)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val seen = prefs.getStringSet("seenPlaceAlertIds", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!seen.add(eventKey)) return
        while (seen.size > 100) seen.remove(seen.first())
        prefs.edit().putStringSet("seenPlaceAlertIds", seen).apply()
        val privatePreview = prefs.getBoolean("privateNotificationPreviews", false)
        val title = if (privatePreview) "A little location update" else if (entered) "$partnerName arrived" else "$partnerName left"
        val body = if (privatePreview) "Open Us to see the shared place update." else if (entered) "$partnerName arrived at $placeName." else "$partnerName left $placeName."
        createChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SHOW_SPLASH, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, eventKey.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, PLACE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(eventKey.hashCode(), notification)
        } catch (_: SecurityException) {
            // Android notification permission may be disabled.
        }
    }

    fun showOwnPlaceAlert(context: Context, eventKey: String, placeName: String, entered: Boolean) {
        val prefs = context.getSharedPreferences("us", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("placeAlertsEnabled", false)) return
        if (!prefs.getBoolean("partnerNotifications", true) || isQuietHours(prefs)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val body = if (entered) "You arrived at $placeName." else "You left $placeName."
        createChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, eventKey.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, PLACE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle(if (entered) "You arrived" else "You left")
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(eventKey.hashCode(), notification)
        } catch (_: SecurityException) { }
    }

    private fun isQuietHours(prefs: android.content.SharedPreferences): Boolean {
        if (!prefs.getBoolean("quietHoursEnabled", false)) return false
        fun toMinutes(value: String?, fallback: Int): Int {
            val parts = value.orEmpty().split(":")
            if (parts.size != 2) return fallback
            val hour = parts[0].toIntOrNull() ?: return fallback
            val minute = parts[1].toIntOrNull() ?: return fallback
            if (hour !in 0..23 || minute !in 0..59) return fallback
            return hour * 60 + minute
        }
        val start = toMinutes(prefs.getString("quietHoursStart", "22:00"), 22 * 60)
        val end = toMinutes(prefs.getString("quietHoursEnd", "07:00"), 7 * 60)
        val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        return if (start == end) true else if (start < end) now in start until end else now >= start || now < end
    }

    fun show(
        context: Context,
        title: String,
        body: String,
        id: Int,
        reminder: Boolean = false,
        partner: Boolean = false
    ) {
        createChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SHOW_SPLASH, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val bypassDnd = hasDndAccess(context) &&
            context.getSharedPreferences("us", Context.MODE_PRIVATE)
                .getBoolean("bypassDndNotifications", false)
        val channel = when {
            reminder && bypassDnd -> REMINDER_DND_CHANNEL_ID
            partner && bypassDnd -> PARTNER_DND_CHANNEL_ID
            reminder -> REMINDER_CHANNEL_ID
            partner -> PARTNER_CHANNEL_ID
            else -> CHANNEL_ID
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(if (reminder || partner) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (partner) NotificationCompat.CATEGORY_SOCIAL else NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Android 13+ notification permission has not been granted.
        }
    }
}

class CalendarReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString("title") ?: return Result.failure()
        val time = inputData.getString("time") ?: "Today"
        val eventId = inputData.getString("eventId") ?: title.hashCode().toString()
        UsNotifications.show(applicationContext, "A little reminder", "$title · $time", eventId.hashCode(), true)
        return Result.success()
    }
}

object ReminderScheduler {
    fun refresh(context: Context, coupleCode: String, events: List<CalendarEvent>) {
        val work = WorkManager.getInstance(context)
        val tag = "calendar-reminder-$coupleCode"
        work.cancelAllWorkByTag(tag)
        val now = System.currentTimeMillis()
        events.forEach { event ->
            if (event.reminderMinutes <= 0) return@forEach
            val date = LocalDate.ofEpochDay(event.dateEpochDay)
            val time = if (event.allDay || event.startTime.isBlank()) LocalTime.of(9, 0)
            else runCatching { LocalTime.parse(event.startTime) }.getOrDefault(LocalTime.of(9, 0))
            val eventAt = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val fireAt = eventAt - event.reminderMinutes * 60_000L
            val delay = fireAt - now
            if (delay <= 0L) return@forEach
            val request = OneTimeWorkRequestBuilder<CalendarReminderWorker>()
                .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(
                    "title" to event.title,
                    "time" to if (event.allDay) date.toString() else "${date} at ${time}",
                    "eventId" to event.id
                ))
                .addTag(tag)
                .build()
            work.enqueueUniqueWork("reminder-$coupleCode-${event.id}", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

object MemoryWorkScheduler {
    fun upload(
        context: Context,
        code: String,
        id: String,
        uid: String,
        fileName: String,
        createdAt: Long
    ) {
        val request = OneTimeWorkRequestBuilder<MemoryUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(
                "code" to code, "id" to id, "uid" to uid,
                "fileName" to fileName, "createdAt" to createdAt
            ))
            .addTag("memory-upload-$code")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "memory-upload-$code-$id", ExistingWorkPolicy.KEEP, request
        )
    }

    fun cancel(context: Context, code: String, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("memory-upload-$code-$id")
    }
}

class MemoryUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val code = inputData.getString("code") ?: return Result.failure()
        val id = inputData.getString("id") ?: return Result.failure()
        val uid = inputData.getString("uid") ?: return Result.failure()
        val fileName = inputData.getString("fileName") ?: "$id.jpg"
        val createdAt = inputData.getLong("createdAt", System.currentTimeMillis())
        val file = File(File(applicationContext.filesDir, "memories"), fileName)
        if (!file.exists()) return Result.failure()
        return try {
            val memoryDoc = FirebaseFirestore.getInstance()
                .collection("couples").document(code).collection("memories").document(id)
            val pending = memoryDoc.get(com.google.firebase.firestore.Source.SERVER).await()
            if (!pending.exists()) return if (runAttemptCount < 15) Result.retry() else Result.failure()
            if (pending.getString("createdBy") != uid || pending.getString("storageMode") != "firestore_blob") {
                file.delete()
                return Result.failure()
            }
            if (pending.getString("status") == "ready") return Result.success()
            if (pending.getString("status") != "pending") return Result.failure()

            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            if (bytes.isEmpty() || bytes.size > MAX_MEMORY_IMAGE_BYTES) {
                file.delete()
                return Result.failure()
            }

            val content = memoryDoc.collection("content")
            val imageDoc = content.document("image")
            val existingImage = imageDoc.get(com.google.firebase.firestore.Source.SERVER).await()
            if (!existingImage.exists()) {
                FeatureRepo.uploadMemoryBlob(code, id, uid, bytes, createdAt).await()
            }

            val thumbnailBytes = withContext(Dispatchers.IO) { createMemoryThumbnail(bytes) }
            val thumbnailDoc = content.document("thumbnail")
            val existingThumbnail = thumbnailDoc.get(com.google.firebase.firestore.Source.SERVER).await()
            if (!existingThumbnail.exists()) {
                FeatureRepo.uploadMemoryThumbnail(code, id, uid, thumbnailBytes, createdAt).await()
            }
            FeatureRepo.finishMemoryUpload(code, id, uid, bytes.size.toLong(), thumbnailBytes.size.toLong()).await()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is com.google.firebase.firestore.FirebaseFirestoreException &&
                error.code in setOf(
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.NOT_FOUND,
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
                )) {
                Result.failure()
            } else if (runAttemptCount < 15) Result.retry() else Result.failure()
        }
    }
}

private fun createMemoryThumbnail(original: ByteArray): ByteArray {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(original, 0, original.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) error("Couldn't decode the photo to create its preview.")
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > 640) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(
        original, 0, original.size,
        BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
    ) ?: error("Couldn't decode the photo to create its preview.")
    try {
        var working = bitmap
        var ownsWorking = false
        while (true) {
            val ratio = minOf(1f, 320f / maxOf(working.width, working.height))
            if (ratio < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    working,
                    (working.width * ratio).toInt().coerceAtLeast(1),
                    (working.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
                if (ownsWorking && !working.isRecycled) working.recycle()
                working = scaled
                ownsWorking = true
            }
            for (quality in listOf(65, 55, 45, 35, 25)) {
                val output = ByteArrayOutputStream()
                working.compress(Bitmap.CompressFormat.JPEG, quality, output)
                val bytes = output.toByteArray()
                if (bytes.isNotEmpty() && bytes.size <= 80 * 1024) {
                    if (ownsWorking && !working.isRecycled) working.recycle()
                    return bytes
                }
            }
            if (minOf(working.width, working.height) <= 72) {
                if (ownsWorking && !working.isRecycled) working.recycle()
                error("Couldn't compress the photo preview below 80 KB.")
            }
            val scaled = Bitmap.createScaledBitmap(
                working,
                (working.width * 0.72f).toInt().coerceAtLeast(1),
                (working.height * 0.72f).toInt().coerceAtLeast(1),
                true
            )
            if (ownsWorking && !working.isRecycled) working.recycle()
            working = scaled
            ownsWorking = true
        }
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

const val MAX_MEMORY_IMAGE_BYTES = 700 * 1024
