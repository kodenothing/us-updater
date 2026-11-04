package com.maenalyn.couples

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.android.gms.tasks.Task
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val LIVE_WIDGET_MAX_IMAGE_BYTES = 700 * 1024
private const val LIVE_WIDGET_MAX_THUMBNAIL_BYTES = 80 * 1024
private const val LIVE_WIDGET_STATE_DOCUMENT = "current"

data class LivePhotoWidgetState(
    val activePhotoId: String = "",
    val updatedBy: String = "",
    val updatedAt: Long = 0L
)

private object LivePhotoWidgetFiles {
    fun root(context: Context) = File(context.filesDir, "live_photo_widget")
    fun cache(context: Context) = File(root(context), "current.jpg")
    fun preview(context: Context) = File(root(context), "preview.jpg")
    fun uploads(context: Context) = File(root(context), "uploads")
    fun preferences(context: Context) = context.getSharedPreferences("us", Context.MODE_PRIVATE)
}

object LivePhotoWidgetRepo {
    private val db get() = FirebaseFirestore.getInstance()

    private fun couple(code: String) = db.collection("couples").document(code)
    private fun state(code: String) = couple(code).collection("livePhotoWidget").document(LIVE_WIDGET_STATE_DOCUMENT)
    private fun photos(code: String) = couple(code).collection("livePhotoWidgetPhotos")

    fun observeCurrent(code: String): Flow<LivePhotoWidgetState> = callbackFlow {
        val registration = state(code).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            if (!snapshot.exists()) {
                trySend(LivePhotoWidgetState())
            } else {
                trySend(
                    LivePhotoWidgetState(
                        activePhotoId = snapshot.getString("activePhotoId").orEmpty(),
                        updatedBy = snapshot.getString("updatedBy").orEmpty(),
                        updatedAt = snapshot.getLong("updatedAt") ?: 0L
                    )
                )
            }
        }
        awaitClose { registration.remove() }
    }

    fun createPendingPhoto(
        code: String,
        id: String,
        uid: String,
        createdAt: Long
    ): Task<Void> = photos(code).document(id).set(
        mapOf(
            "createdBy" to uid,
            "createdAt" to createdAt,
            "status" to "pending",
            "sizeBytes" to 0L,
            "thumbnailBytes" to 0L
        )
    )

    fun getPhoto(code: String, id: String) = photos(code).document(id).get(Source.SERVER)

    fun getBlob(code: String, id: String, name: String) =
        photos(code).document(id).collection("content").document(name).get(Source.SERVER)

    fun uploadBlob(
        code: String,
        id: String,
        uid: String,
        name: String,
        bytes: ByteArray,
        createdAt: Long
    ): Task<Void> = photos(code).document(id).collection("content").document(name).set(
        mapOf(
            "image" to Blob.fromBytes(bytes),
            "contentType" to "image/jpeg",
            "createdBy" to uid,
            "createdAt" to createdAt
        )
    )

    suspend fun activatePhoto(
        code: String,
        id: String,
        uid: String,
        imageBytes: Long,
        thumbnailBytes: Long
    ): String? {
        val photoRef = photos(code).document(id)
        val stateRef = state(code)
        return db.runTransaction { transaction ->
            val currentSnapshot = transaction.get(stateRef)
            val photoSnapshot = transaction.get(photoRef)
            if (!photoSnapshot.exists() || photoSnapshot.getString("createdBy") != uid) {
                throw IllegalStateException("The new shared photo is no longer available.")
            }
            if (photoSnapshot.getString("status") == "ready") {
                return@runTransaction photoSnapshot.getString("previousPhotoId")
                    ?.takeIf { it.isNotBlank() }
            }
            if (photoSnapshot.getString("status") != "pending") {
                throw IllegalStateException("The new shared photo is not ready to publish.")
            }

            val previousId = currentSnapshot.getString("activePhotoId").orEmpty()
            transaction.update(
                photoRef,
                mapOf(
                    "status" to "ready",
                    "sizeBytes" to imageBytes,
                    "thumbnailBytes" to thumbnailBytes,
                    "previousPhotoId" to previousId
                )
            )
            transaction.set(
                stateRef,
                mapOf(
                    "activePhotoId" to id,
                    "updatedBy" to uid,
                    "updatedAt" to System.currentTimeMillis()
                )
            )
            previousId.takeIf { it.isNotBlank() && it != id }
        }.await()
    }

    suspend fun removeCurrentPhoto(context: Context, code: String, uid: String): String? {
        val stateRef = state(code)
        val previousId = db.runTransaction { transaction ->
            val snapshot = transaction.get(stateRef)
            val oldId = snapshot.getString("activePhotoId").orEmpty()
            transaction.set(
                stateRef,
                mapOf(
                    "activePhotoId" to "",
                    "updatedBy" to uid,
                    "updatedAt" to System.currentTimeMillis()
                )
            )
            oldId.takeIf { it.isNotBlank() }
        }.await()
        if (previousId != null) {
            try {
                deletePhoto(code, previousId)
            } catch (_: Exception) {
                LivePhotoWidgetDeleteWorker.enqueue(context, code, previousId)
            }
        }
        return previousId
    }

    suspend fun deletePhoto(code: String, id: String) {
        val content = photos(code).document(id).collection("content")
        content.document("image").delete().await()
        content.document("thumbnail").delete().await()
        photos(code).document(id).delete().await()
    }

    suspend fun loadThumbnail(context: Context, code: String, id: String): Bitmap? {
        val cached = LivePhotoWidgetFiles.preview(context)
        val prefs = LivePhotoWidgetFiles.preferences(context)
        if (cached.exists() && prefs.getString("livePhotoWidgetPreviewCachedId", null) == id) {
            BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
        }
        val snapshot = getBlob(code, id, "thumbnail").await()
        val bytes = snapshot.getBlob("image")?.toBytes()
            ?: getBlob(code, id, "image").await().getBlob("image")?.toBytes()
            ?: return null
        if (bytes.isEmpty() || bytes.size > LIVE_WIDGET_MAX_IMAGE_BYTES) return null
        return withContext(Dispatchers.IO) {
            cached.parentFile?.mkdirs()
            val temporary = File(cached.parentFile, "current.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(cached)) {
                cached.writeBytes(bytes)
                temporary.delete()
            }
            prefs.edit().putString("livePhotoWidgetPreviewCachedId", id).apply()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}

object LivePhotoWidgetUploadScheduler {
    suspend fun enqueueFromUri(context: Context, code: String, uid: String, uri: Uri): String {
        val id = UUID.randomUUID().toString()
        val file = File(LivePhotoWidgetFiles.uploads(context).apply { mkdirs() }, "$id.jpg")
        try {
            compressPhoto(context, uri, file)
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            if (uri.authority == "${context.packageName}.fileprovider") {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
        val request = OneTimeWorkRequestBuilder<LivePhotoWidgetUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    "code" to code,
                    "uid" to uid,
                    "photoId" to id,
                    "filePath" to file.absolutePath,
                    "createdAt" to System.currentTimeMillis()
                )
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag("live-photo-widget")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "live-photo-upload-$code-$id",
            ExistingWorkPolicy.KEEP,
            request
        )
        return id
    }

    private suspend fun compressPhoto(context: Context, uri: Uri, destination: File) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("This photo format can't be opened. Choose a JPEG, PNG, or WebP image.")
        }

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 1600) sampleSize *= 2
        var bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } ?: throw IllegalArgumentException("Couldn't read that photo. Please choose it again.")

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
                    if (bytes.isNotEmpty() && bytes.size <= LIVE_WIDGET_MAX_IMAGE_BYTES) {
                        accepted = bytes
                        break
                    }
                }
                if (accepted != null) {
                    destination.writeBytes(accepted)
                    return@withContext
                }
                if (minOf(bitmap.width, bitmap.height) <= 480) {
                    throw IllegalArgumentException("This photo is too detailed to fit the 700 KB shared widget limit. Choose a smaller photo.")
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
}

class LivePhotoWidgetUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val code = inputData.getString("code") ?: return Result.failure()
        val uid = inputData.getString("uid") ?: return Result.failure()
        val id = inputData.getString("photoId") ?: return Result.failure()
        val file = File(inputData.getString("filePath") ?: return Result.failure())
        val createdAt = inputData.getLong("createdAt", System.currentTimeMillis())
        if (!file.exists()) return Result.failure()

        return try {
            val photo = LivePhotoWidgetRepo.getPhoto(code, id).await()
            if (!photo.exists()) {
                LivePhotoWidgetRepo.createPendingPhoto(code, id, uid, createdAt).await()
            } else if (photo.getString("createdBy") != uid) {
                return Result.failure()
            }

            val latest = LivePhotoWidgetRepo.getPhoto(code, id).await()
            if (latest.getString("status") != "ready") {
                val imageBytes = withContext(Dispatchers.IO) { file.readBytes() }
                if (imageBytes.isEmpty() || imageBytes.size > LIVE_WIDGET_MAX_IMAGE_BYTES) return Result.failure()
                val imageSnapshot = LivePhotoWidgetRepo.getBlob(code, id, "image").await()
                if (!imageSnapshot.exists()) {
                    LivePhotoWidgetRepo.uploadBlob(code, id, uid, "image", imageBytes, createdAt).await()
                }
                val thumbnailBytes = createLivePhotoThumbnail(imageBytes)
                val thumbnailSnapshot = LivePhotoWidgetRepo.getBlob(code, id, "thumbnail").await()
                if (!thumbnailSnapshot.exists()) {
                    LivePhotoWidgetRepo.uploadBlob(code, id, uid, "thumbnail", thumbnailBytes, createdAt).await()
                }
                val previousId = LivePhotoWidgetRepo.activatePhoto(
                    code, id, uid, imageBytes.size.toLong(), thumbnailBytes.size.toLong()
                )
                if (!previousId.isNullOrBlank() && previousId != id) {
                    LivePhotoWidgetRepo.deletePhoto(code, previousId)
                }
            } else {
                val previousId = latest.getString("previousPhotoId").orEmpty()
                if (previousId.isNotBlank() && previousId != id) {
                    LivePhotoWidgetRepo.deletePhoto(code, previousId)
                }
            }

            withContext(Dispatchers.IO) { file.delete() }
            LivePhotoWidgetSyncWorker.enqueue(applicationContext, code)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is FirebaseFirestoreException &&
                error.code in setOf(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    FirebaseFirestoreException.Code.NOT_FOUND,
                    FirebaseFirestoreException.Code.INVALID_ARGUMENT
                )
            ) Result.failure()
            else Result.retry()
        }
    }
}

private fun createLivePhotoThumbnail(original: ByteArray): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Couldn't read the photo preview.")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(
        original,
        0,
        original.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    ) ?: error("Couldn't create the photo preview.")
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
                if (bytes.isNotEmpty() && bytes.size <= LIVE_WIDGET_MAX_THUMBNAIL_BYTES) {
                    if (ownsWorking && !working.isRecycled) working.recycle()
                    return bytes
                }
            }
            if (minOf(working.width, working.height) <= 72) {
                if (ownsWorking && !working.isRecycled) working.recycle()
                error("Couldn't compress the preview below 80 KB.")
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


class LivePhotoWidgetDeleteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val code = inputData.getString("code") ?: return Result.failure()
        val photoId = inputData.getString("photoId") ?: return Result.failure()
        return try {
            LivePhotoWidgetRepo.deletePhoto(code, photoId)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, code: String, photoId: String) {
            val request = OneTimeWorkRequestBuilder<LivePhotoWidgetDeleteWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(workDataOf("code" to code, "photoId" to photoId))
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("live-photo-widget")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "live-photo-delete-$code-$photoId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

class LivePhotoWidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val code = inputData.getString("code") ?: return Result.failure()
        return try {
            val state = FirebaseFirestore.getInstance()
                .collection("couples").document(code)
                .collection("livePhotoWidget").document(LIVE_WIDGET_STATE_DOCUMENT)
                .get(Source.SERVER).await()
            val photoId = state.getString("activePhotoId").orEmpty()
            val cache = LivePhotoWidgetFiles.cache(applicationContext)
            val prefs = LivePhotoWidgetFiles.preferences(applicationContext)
            if (photoId.isBlank()) {
                withContext(Dispatchers.IO) {
                    cache.delete()
                    LivePhotoWidgetFiles.preview(applicationContext).delete()
                }
                prefs.edit()
                    .remove("livePhotoWidgetCachedId")
                    .remove("livePhotoWidgetPreviewCachedId")
                    .apply()
                LivePhotoWidgetProvider.updateAll(applicationContext, scheduleSync = false)
                return Result.success()
            }

            val photo = FirebaseFirestore.getInstance()
                .collection("couples").document(code)
                .collection("livePhotoWidgetPhotos").document(photoId)
                .get(Source.SERVER).await()
            if (!photo.exists() || photo.getString("status") != "ready") return Result.retry()
            val content = FirebaseFirestore.getInstance()
                .collection("couples").document(code)
                .collection("livePhotoWidgetPhotos").document(photoId)
                .collection("content")
            val image = content.document("image").get(Source.SERVER).await()
            val bytes = image.getBlob("image")?.toBytes()
                ?: content.document("thumbnail").get(Source.SERVER).await().getBlob("image")?.toBytes()
                ?: return Result.retry()
            if (bytes.isEmpty() || bytes.size > LIVE_WIDGET_MAX_IMAGE_BYTES) return Result.failure()

            withContext(Dispatchers.IO) {
                cache.parentFile?.mkdirs()
                val temporary = File(cache.parentFile, "current.tmp")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(cache)) {
                    cache.writeBytes(bytes)
                    temporary.delete()
                }
            }
            prefs.edit().putString("livePhotoWidgetCachedId", photoId).apply()
            LivePhotoWidgetProvider.updateAll(applicationContext, scheduleSync = false)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is FirebaseFirestoreException &&
                error.code in setOf(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    FirebaseFirestoreException.Code.INVALID_ARGUMENT
                )
            ) Result.failure() else Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, code: String) {
            val request = OneTimeWorkRequestBuilder<LivePhotoWidgetSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(workDataOf("code" to code))
                .addTag("live-photo-widget")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "live-photo-widget-sync-$code",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

class LivePhotoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        updateOne(context, manager, appWidgetId)
    }

    override fun onDisabled(context: Context) {
        val code = context.getSharedPreferences("us", Context.MODE_PRIVATE).getString("code", null)
        if (!code.isNullOrBlank()) {
            WorkManager.getInstance(context).cancelUniqueWork("live-photo-widget-periodic-$code")
        }
        super.onDisabled(context)
    }

    companion object {
        fun clearLocalCache(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag("live-photo-widget")
            LivePhotoWidgetFiles.root(context).deleteRecursively()
            LivePhotoWidgetFiles.preferences(context).edit()
                .remove("livePhotoWidgetCachedId")
                .remove("livePhotoWidgetPreviewCachedId")
                .apply()
            updateAll(context, scheduleSync = false)
        }

        fun updateAll(context: Context, scheduleSync: Boolean = true) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, LivePhotoWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { updateOne(context, manager, it) }
            if (scheduleSync && ids.isNotEmpty()) {
                val code = context.getSharedPreferences("us", Context.MODE_PRIVATE).getString("code", null)
                if (!code.isNullOrBlank()) {
                    LivePhotoWidgetSyncWorker.enqueue(context, code)
                    val periodic = PeriodicWorkRequestBuilder<LivePhotoWidgetSyncWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(
                            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                        )
                        .setInputData(workDataOf("code" to code))
                        .addTag("live-photo-widget")
                        .build()
                    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                        "live-photo-widget-periodic-$code",
                        ExistingPeriodicWorkPolicy.UPDATE,
                        periodic
                    )
                }
            }
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_live_photo)
            val cache = LivePhotoWidgetFiles.cache(context)
            val cachedId = LivePhotoWidgetFiles.preferences(context)
                .getString("livePhotoWidgetCachedId", null)
            val bitmap = if (cache.exists() && !cachedId.isNullOrBlank()) {
                BitmapFactory.decodeFile(cache.absolutePath)
            } else null
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.live_photo_image, bitmap)
                views.setViewVisibility(R.id.live_photo_image, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.live_photo_placeholder, android.view.View.GONE)
            } else {
                views.setViewVisibility(R.id.live_photo_image, android.view.View.GONE)
                views.setViewVisibility(R.id.live_photo_placeholder, android.view.View.VISIBLE)
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                2406,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_live_photo_root, pending)
            manager.updateAppWidget(id, views)
        }
    }
}

@Composable
fun LivePhotoWidgetCard(code: String, uid: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember(code) { mutableStateOf(LivePhotoWidgetState()) }
    var preview by remember(current.activePhotoId) { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(code) {
        LivePhotoWidgetRepo.observeCurrent(code).collect { state ->
            current = state
            LivePhotoWidgetSyncWorker.enqueue(context, code)
        }
    }

    LaunchedEffect(code, current.activePhotoId) {
        preview = null
        val id = current.activePhotoId
        if (id.isNotBlank()) {
            preview = runCatching { LivePhotoWidgetRepo.loadThumbnail(context, code, id) }.getOrNull()
        }
    }

    fun submitPhoto(uri: Uri) {
        if (working || uid.isBlank()) return
        scope.launch {
            working = true
            feedback = null
            try {
                LivePhotoWidgetUploadScheduler.enqueueFromUri(context, code, uid, uri)
                feedback = "Photo queued. Both widgets will update after it syncs."
            } catch (error: Exception) {
                feedback = error.message ?: "Couldn't prepare this photo."
            } finally {
                working = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) submitPhoto(uri) }

    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = cameraUri
        cameraUri = null
        if (saved && uri != null) submitPhoto(uri)
        else feedback = "No photo was captured."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Live Photo Widget", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "One shared photo on both home screens",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview!!.asImageBitmap(),
                        contentDescription = "Current shared Live Photo Widget image",
                        modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (current.activePhotoId.isBlank()) "Choose your first shared photo" else "Loading shared photo…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (current.activePhotoId.isNotBlank()) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !working,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Pick a photo")
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val file = File(context.cacheDir, "live-widget-${UUID.randomUUID()}.jpg")
                            file.createNewFile()
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            cameraUri = uri
                            camera.launch(uri)
                        }.onFailure {
                            feedback = "Couldn't open the camera. Please try again."
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Take a photo")
                }
            }

            if (working) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Preparing photo…", style = MaterialTheme.typography.bodySmall)
                }
            }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Add it: Home screen → Widgets → Us → Live Photo",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (current.activePhotoId.isNotBlank()) {
                    TextButton(onClick = { confirmRemove = true }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                        Text("Remove")
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove shared widget photo?") },
            text = { Text("This removes the active photo and its Firestore image documents. Both home screen widgets will return to the empty state.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        working = true
                        try {
                            LivePhotoWidgetRepo.removeCurrentPhoto(context, code, uid)
                            feedback = "Shared widget photo removed."
                        } catch (error: Exception) {
                            feedback = error.message ?: "Couldn't remove the shared photo."
                        } finally {
                            working = false
                        }
                    }
                }) { Text("Remove photo") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}
