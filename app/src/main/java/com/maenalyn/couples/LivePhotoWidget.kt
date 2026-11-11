package com.maenalyn.couples

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.RemoteViews
import androidx.exifinterface.media.ExifInterface
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
private const val LIVE_WIDGET_MAX_IMAGE_BYTES = 700 * 1024
private const val LIVE_WIDGET_MAX_THUMBNAIL_BYTES = 80 * 1024
private const val LIVE_WIDGET_STATE_DOCUMENT = "current"

data class LivePhotoGalleryEntry(
    val id: String,
    val ownerUid: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val thumbnailBytes: Long
)

data class LivePhotoGalleryPage(
    val entries: List<LivePhotoGalleryEntry>,
    val nextCursor: DocumentSnapshot?
)

data class LivePhotoGalleryMembers(
    val partnerUid: String,
    val partnerGender: String
)

data class LivePhotoWidgetState(
    val activePhotoId: String = "",
    val updatedBy: String = "",
    val updatedAt: Long = 0L
)

private object LivePhotoWidgetFiles {
    const val MY_UID_PREFERENCE = "livePhotoWidgetMyUid"
    const val PARTNER_UID_PREFERENCE = "livePhotoWidgetPartnerUid"
    const val PARTNER_GENDER_PREFERENCE = "livePhotoWidgetPartnerGender"

    fun root(context: Context) = File(context.filesDir, "live_photo_widget")
    fun cache(context: Context) = File(root(context), "current.jpg")
    fun preview(context: Context) = File(root(context), "preview.jpg")
    fun uploads(context: Context) = File(root(context), "uploads")
    fun preferences(context: Context) = context.getSharedPreferences("us", Context.MODE_PRIVATE)

    fun memberCache(context: Context, ownerUid: String): File {
        val token = Base64.encodeToString(
            ownerUid.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return File(File(root(context), "members"), "$token.jpg")
    }

    fun memberCachedIdPreference(ownerUid: String) = "livePhotoWidgetMemberCachedId_$ownerUid"
}

object LivePhotoWidgetRepo {
    private val db get() = FirebaseFirestore.getInstance()

    private fun couple(code: String) = db.collection("couples").document(code)
    private fun state(code: String) = couple(code).collection("livePhotoWidget").document(LIVE_WIDGET_STATE_DOCUMENT)
    private fun memberState(code: String, ownerUid: String) =
        couple(code).collection("livePhotoWidgetMembers").document(ownerUid)
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

    suspend fun loadGalleryMembers(code: String, currentUid: String): LivePhotoGalleryMembers {
        require(code.isNotBlank()) { "A shared space is required." }
        require(currentUid.isNotBlank()) { "Your account is not ready." }
        val snapshot = couple(code).get(Source.SERVER).await()
        if (!snapshot.exists()) throw IllegalStateException("Your shared space is unavailable.")
        val members = (snapshot.get("members") as? List<*>)?.filterIsInstance<String>().orEmpty()
        if (currentUid !in members || members.size != 2) {
            throw IllegalStateException("Our Instant Gallery is available when both partners are paired.")
        }
        val partnerUid = members.firstOrNull { it != currentUid }
            ?: throw IllegalStateException("Your partner could not be found.")
        val genders = (snapshot.get("memberGenders") as? Map<*, *>).orEmpty()
        val partnerGender = (genders[partnerUid] as? String)
            ?.takeIf { it in setOf("male", "female", "unspecified") }
            ?: "unspecified"
        return LivePhotoGalleryMembers(partnerUid, partnerGender)
    }

    /**
     * Loads a descending page of ready Our Instant captures for one member.
     *
     * The query is scoped to the couple. Owner filtering is applied to the returned
     * metadata so this can use Firestore's automatic createdAt index and does not
     * introduce a composite-index deployment requirement.
     */
    suspend fun loadGalleryPage(
        code: String,
        ownerUid: String,
        pageSize: Int = 30,
        after: DocumentSnapshot? = null
    ): LivePhotoGalleryPage {
        require(code.isNotBlank()) { "A shared space is required." }
        require(ownerUid.isNotBlank()) { "A photo owner is required." }
        require(pageSize in 1..60) { "Gallery page size must be between 1 and 60." }

        var query = photos(code)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
        if (after != null) query = query.startAfter(after)

        val snapshot = query.get(Source.SERVER).await()
        val entries = snapshot.documents.mapNotNull { document ->
            val createdBy = document.getString("createdBy") ?: return@mapNotNull null
            val createdAt = document.getLong("createdAt") ?: return@mapNotNull null
            if (createdBy != ownerUid || document.getString("status") != "ready") {
                return@mapNotNull null
            }
            LivePhotoGalleryEntry(
                id = document.id,
                ownerUid = createdBy,
                createdAt = createdAt,
                sizeBytes = document.getLong("sizeBytes") ?: 0L,
                thumbnailBytes = document.getLong("thumbnailBytes") ?: 0L
            )
        }
        val cursor = if (snapshot.documents.size == pageSize) snapshot.documents.lastOrNull() else null
        return LivePhotoGalleryPage(entries = entries, nextCursor = cursor)
    }

    /**
     * Loads an image or thumbnail for a ready history item. A missing preferred
     * rendition falls back to the other stored rendition.
     */
    suspend fun loadGalleryImageBytes(
        code: String,
        photoId: String,
        preferThumbnail: Boolean
    ): ByteArray? {
        val metadata = getPhoto(code, photoId).await()
        if (!metadata.exists() || metadata.getString("status") != "ready") return null

        val preferred = if (preferThumbnail) "thumbnail" else "image"
        val fallback = if (preferThumbnail) "image" else "thumbnail"
        val preferredBytes = getBlob(code, photoId, preferred).await()
            .getBlob("image")?.toBytes()
        if (preferredBytes != null && preferredBytes.isNotEmpty() &&
            preferredBytes.size <= LIVE_WIDGET_MAX_IMAGE_BYTES
        ) return preferredBytes

        val fallbackBytes = getBlob(code, photoId, fallback).await()
            .getBlob("image")?.toBytes()
        return fallbackBytes?.takeIf {
            it.isNotEmpty() && it.size <= LIVE_WIDGET_MAX_IMAGE_BYTES
        }
    }

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
        val memberStateRef = memberState(code, uid)
        return db.runTransaction { transaction ->
            // All transaction reads happen before writes. The couple-wide pointer
            // preserves the legacy shared widget while the owner pointer drives
            // the new independent My Instant / Partner Instant widgets.
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
            val now = System.currentTimeMillis()
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
                    "updatedAt" to now
                )
            )
            transaction.set(
                memberStateRef,
                mapOf(
                    "activePhotoId" to id,
                    "updatedBy" to uid,
                    "updatedAt" to now
                )
            )
            previousId.takeIf { it.isNotBlank() && it != id }
        }.await()
    }

    /**
     * Clears only the legacy couple-wide pointer. The captured image remains in
     * the owner's gallery and may still be displayed by that member's independent
     * widget, so it must not be deleted here.
     */
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
        LivePhotoWidgetSyncWorker.enqueue(context, code)
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
    suspend fun enqueueFromUri(
        context: Context,
        code: String,
        uid: String,
        uri: Uri,
        mirrorFrontCamera: Boolean = false
    ): String {
        val id = UUID.randomUUID().toString()
        val file = File(LivePhotoWidgetFiles.uploads(context).apply { mkdirs() }, "$id.jpg")
        try {
            compressPhoto(context, uri, file, mirrorFrontCamera)
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

    private suspend fun compressPhoto(
        context: Context,
        uri: Uri,
        destination: File,
        mirrorHorizontally: Boolean
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("This photo format can't be opened. Choose a JPEG, PNG, or WebP image.")
        }
        val rotationDegrees = resolver.openInputStream(uri)?.use {
            ExifInterface(it).rotationDegrees
        } ?: 0

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
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
                bitmap.recycle()
                bitmap = rotated
            }
            // CameraX mirrors the front-camera preview, but saved files are not
            // guaranteed to be mirrored. Flip the processed copy so the shared
            // widget matches what the person saw in the selfie preview.
            if (mirrorHorizontally) {
                val mirrorMatrix = Matrix().apply { setScale(-1f, 1f) }
                val mirrored = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, mirrorMatrix, true
                )
                if (mirrored !== bitmap) {
                    bitmap.recycle()
                    bitmap = mirrored
                }
            }
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
        if (LivePhotoWidgetFiles.preferences(applicationContext).getString("code", null) != code) {
            withContext(Dispatchers.IO) { file.delete() }
            return Result.success()
        }

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
                if (LivePhotoWidgetFiles.preferences(applicationContext).getString("code", null) != code) {
                    withContext(Dispatchers.IO) { file.delete() }
                    return Result.success()
                }
                // Publishing a new capture changes only the current widget pointer.
                // The previous ready photo remains in livePhotoWidgetPhotos for the gallery.
                LivePhotoWidgetRepo.activatePhoto(
                    code, id, uid, imageBytes.size.toLong(), thumbnailBytes.size.toLong()
                )
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

private suspend fun syncMemberWidgetCaches(context: Context, code: String) {
    val prefs = LivePhotoWidgetFiles.preferences(context)
    val db = FirebaseFirestore.getInstance()
    val couple = db.collection("couples").document(code).get(Source.SERVER).await()
    val members = (couple.get("members") as? List<*>)?.filterIsInstance<String>().orEmpty()
    val currentUid = FirebaseAuth.getInstance().currentUser?.uid

    // WorkManager can start the process before Firebase Auth finishes restoring
    // its persisted session. Keep the last good owner mapping/cache in that case.
    if (currentUid.isNullOrBlank()) return

    if (!couple.exists() || currentUid !in members) {
        prefs.edit()
            .remove(LivePhotoWidgetFiles.MY_UID_PREFERENCE)
            .remove(LivePhotoWidgetFiles.PARTNER_UID_PREFERENCE)
            .remove(LivePhotoWidgetFiles.PARTNER_GENDER_PREFERENCE)
            .apply()
        MyInstantWidgetProvider.updateAll(context, scheduleSync = false)
        PartnerInstantWidgetProvider.updateAll(context, scheduleSync = false)
        return
    }

    val partnerUid = members.firstOrNull { it != currentUid }
    val genders = (couple.get("memberGenders") as? Map<*, *>).orEmpty()
    val partnerGender = (partnerUid?.let { genders[it] as? String })
        ?.takeIf { it in setOf("male", "female", "unspecified") }
        ?: "unspecified"

    prefs.edit()
        .putString(LivePhotoWidgetFiles.MY_UID_PREFERENCE, currentUid)
        .putString(LivePhotoWidgetFiles.PARTNER_UID_PREFERENCE, partnerUid)
        .putString(LivePhotoWidgetFiles.PARTNER_GENDER_PREFERENCE, partnerGender)
        .apply()

    val owners = listOfNotNull(currentUid, partnerUid)
    for (ownerUid in owners) {
        if (prefs.getString("code", null) != code) return

        val pointer = db.collection("couples").document(code)
            .collection("livePhotoWidgetMembers").document(ownerUid)
            .get(Source.SERVER).await()
        val photoId = pointer.getString("activePhotoId").orEmpty()
        val cache = LivePhotoWidgetFiles.memberCache(context, ownerUid)
        val cacheIdPreference = LivePhotoWidgetFiles.memberCachedIdPreference(ownerUid)

        if (photoId.isBlank()) {
            withContext(Dispatchers.IO) { cache.delete() }
            prefs.edit().remove(cacheIdPreference).apply()
            continue
        }
        if (cache.exists() && prefs.getString(cacheIdPreference, null) == photoId) {
            continue
        }

        val photo = db.collection("couples").document(code)
            .collection("livePhotoWidgetPhotos").document(photoId)
            .get(Source.SERVER).await()
        if (!photo.exists() ||
            photo.getString("status") != "ready" ||
            photo.getString("createdBy") != ownerUid
        ) {
            throw IllegalStateException("An Instant widget photo is not ready to display.")
        }

        val content = db.collection("couples").document(code)
            .collection("livePhotoWidgetPhotos").document(photoId)
            .collection("content")
        val image = content.document("image").get(Source.SERVER).await()
        val bytes = image.getBlob("image")?.toBytes()
            ?: content.document("thumbnail").get(Source.SERVER).await().getBlob("image")?.toBytes()
            ?: throw IllegalStateException("An Instant widget photo is missing its image.")
        if (bytes.isEmpty() || bytes.size > LIVE_WIDGET_MAX_IMAGE_BYTES) {
            throw IllegalStateException("An Instant widget photo has an invalid image size.")
        }

        if (prefs.getString("code", null) != code) return
        withContext(Dispatchers.IO) {
            cache.parentFile?.mkdirs()
            val temporary = File(cache.parentFile, cache.nameWithoutExtension + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(cache)) {
                cache.writeBytes(bytes)
                temporary.delete()
            }
        }
        prefs.edit().putString(cacheIdPreference, photoId).apply()
    }

    MyInstantWidgetProvider.updateAll(context, scheduleSync = false)
    PartnerInstantWidgetProvider.updateAll(context, scheduleSync = false)
}

class LivePhotoWidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val code = inputData.getString("code") ?: return Result.failure()
        val prefs = LivePhotoWidgetFiles.preferences(applicationContext)
        if (prefs.getString("code", null) != code) return Result.success()
        return try {
            syncMemberWidgetCaches(applicationContext, code)
            val state = FirebaseFirestore.getInstance()
                .collection("couples").document(code)
                .collection("livePhotoWidget").document(LIVE_WIDGET_STATE_DOCUMENT)
                .get(Source.SERVER).await()
            val photoId = state.getString("activePhotoId").orEmpty()
            val cache = LivePhotoWidgetFiles.cache(applicationContext)
            val prefs = LivePhotoWidgetFiles.preferences(applicationContext)
            if (photoId.isBlank()) {
                if (prefs.getString("code", null) != code) return Result.success()
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
            if (prefs.getString("code", null) != code) return Result.success()

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


private fun decodeWidgetBitmap(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    )
}

class LivePhotoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        OurInstantWidgetDesigns.remove(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
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
        MemberInstantWidgetRenderer.cancelPeriodicIfUnused(context)
        super.onDisabled(context)
    }

    companion object {
        fun clearLocalCache(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag("live-photo-widget")
            LivePhotoWidgetFiles.root(context).deleteRecursively()
            val prefs = LivePhotoWidgetFiles.preferences(context)
            val myUid = prefs.getString(LivePhotoWidgetFiles.MY_UID_PREFERENCE, null)
            val partnerUid = prefs.getString(LivePhotoWidgetFiles.PARTNER_UID_PREFERENCE, null)
            val editor = prefs.edit()
                .remove("livePhotoWidgetCachedId")
                .remove("livePhotoWidgetPreviewCachedId")
                .remove(LivePhotoWidgetFiles.MY_UID_PREFERENCE)
                .remove(LivePhotoWidgetFiles.PARTNER_UID_PREFERENCE)
                .remove(LivePhotoWidgetFiles.PARTNER_GENDER_PREFERENCE)
            myUid?.let { editor.remove(LivePhotoWidgetFiles.memberCachedIdPreference(it)) }
            partnerUid?.let { editor.remove(LivePhotoWidgetFiles.memberCachedIdPreference(it)) }
            editor.apply()
            updateAll(context, scheduleSync = false)
            MyInstantWidgetProvider.updateAll(context, scheduleSync = false)
            PartnerInstantWidgetProvider.updateAll(context, scheduleSync = false)
        }

        fun updateWidget(context: Context, appWidgetId: Int) {
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            val manager = AppWidgetManager.getInstance(context)
            updateOne(context, manager, appWidgetId)
        }

        fun updateAll(context: Context, scheduleSync: Boolean = true) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, LivePhotoWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { updateOne(context, manager, it) }
            if (scheduleSync) {
                val code = context.getSharedPreferences("us", Context.MODE_PRIVATE).getString("code", null)
                if (!code.isNullOrBlank()) {
                    ensureBackgroundSyncScheduled(context, code)
                }
            }
        }

        /**
         * Registers the durable refresh schedule for an already-pinned widget.
         *
         * This is intentionally called when a saved pairing is restored, too: a widget
         * may have been added before pairing existed, so its initial onUpdate could not
         * register work because there was no couple code yet.
         */
        fun ensureBackgroundSyncScheduled(context: Context, code: String) {
            val normalizedCode = code.trim()
            if (normalizedCode.isEmpty()) return

            val manager = AppWidgetManager.getInstance(context)
            if (!MemberInstantWidgetRenderer.hasAnyWidgets(context, manager)) return

            // Refresh once immediately, then let WorkManager maintain the periodic refresh.
            LivePhotoWidgetSyncWorker.enqueue(context, normalizedCode)

            val periodic = PeriodicWorkRequestBuilder<LivePhotoWidgetSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(workDataOf("code" to normalizedCode))
                .addTag("live-photo-widget")
                .build()

            // KEEP avoids resetting the periodic timer every time the app starts or a
            // widget is redrawn. WorkManager persists and reschedules this work itself.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "live-photo-widget-periodic-$normalizedCode",
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val style = OurInstantWidgetDesigns.load(context, id)
            val layout = OurInstantWidgetDesigns.layoutFor(style)
            val views = RemoteViews(context.packageName, layout)
            views.setTextViewText(R.id.live_photo_owner_label, "Our Instant")
            views.setViewVisibility(R.id.live_photo_owner_label, android.view.View.VISIBLE)
            val cache = LivePhotoWidgetFiles.cache(context)
            val cachedId = LivePhotoWidgetFiles.preferences(context)
                .getString("livePhotoWidgetCachedId", null)
            val bitmap = if (cache.exists() && !cachedId.isNullOrBlank()) {
                decodeWidgetBitmap(cache)
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

private object MemberInstantWidgetRenderer {
    private const val MY_UID_KEY = LivePhotoWidgetFiles.MY_UID_PREFERENCE
    private const val PARTNER_UID_KEY = LivePhotoWidgetFiles.PARTNER_UID_PREFERENCE

    fun hasAnyWidgets(context: Context, manager: AppWidgetManager): Boolean {
        val providers = listOf(
            LivePhotoWidgetProvider::class.java,
            MyInstantWidgetProvider::class.java,
            PartnerInstantWidgetProvider::class.java
        )
        return providers.any { provider ->
            manager.getAppWidgetIds(ComponentName(context, provider)).isNotEmpty()
        }
    }

    fun cancelPeriodicIfUnused(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        if (hasAnyWidgets(context, manager)) return
        val code = LivePhotoWidgetFiles.preferences(context).getString("code", null)
        if (!code.isNullOrBlank()) {
            WorkManager.getInstance(context).cancelUniqueWork("live-photo-widget-periodic-$code")
        }
    }

    fun updateWidget(context: Context, appWidgetId: Int, partner: Boolean) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        updateOne(context, AppWidgetManager.getInstance(context), appWidgetId, partner)
        val code = LivePhotoWidgetFiles.preferences(context).getString("code", null)
        if (!code.isNullOrBlank()) {
            LivePhotoWidgetProvider.ensureBackgroundSyncScheduled(context, code)
        }
    }

    fun updateAll(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        partner: Boolean,
        scheduleSync: Boolean
    ) {
        ids.forEach { updateOne(context, manager, it, partner) }
        if (scheduleSync && ids.isNotEmpty()) {
            val code = LivePhotoWidgetFiles.preferences(context).getString("code", null)
            if (!code.isNullOrBlank()) {
                LivePhotoWidgetProvider.ensureBackgroundSyncScheduled(context, code)
            }
        }
    }

    private fun updateOne(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        partner: Boolean
    ) {
        val prefs = LivePhotoWidgetFiles.preferences(context)
        val ownerUid = if (partner) {
            prefs.getString(PARTNER_UID_KEY, null)
        } else {
            prefs.getString(MY_UID_KEY, null)
                ?: FirebaseAuth.getInstance().currentUser?.uid
        }
        val cache = ownerUid?.let { LivePhotoWidgetFiles.memberCache(context, it) }
        val cachedId = ownerUid?.let {
            prefs.getString(LivePhotoWidgetFiles.memberCachedIdPreference(it), null)
        }
        val style = OurInstantWidgetDesigns.load(context, id)
        val views = RemoteViews(context.packageName, OurInstantWidgetDesigns.layoutFor(style))
        val ownerLabel = if (!partner) {
            "My Instant"
        } else {
            when (prefs.getString(LivePhotoWidgetFiles.PARTNER_GENDER_PREFERENCE, "unspecified")) {
                "female" -> "Her Instant"
                "male" -> "His Instant"
                else -> "Partner Instant"
            }
        }
        views.setTextViewText(R.id.live_photo_owner_label, ownerLabel)
        views.setViewVisibility(R.id.live_photo_owner_label, android.view.View.VISIBLE)
        val bitmap = if (cache != null && cache.exists() && !cachedId.isNullOrBlank()) {
            decodeWidgetBitmap(cache)
        } else null

        if (bitmap != null) {
            views.setImageViewBitmap(R.id.live_photo_image, bitmap)
            views.setViewVisibility(R.id.live_photo_image, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.live_photo_placeholder, android.view.View.GONE)
        } else {
            views.setTextViewText(
                R.id.live_photo_placeholder,
                "$ownerLabel will appear here 💜\nOpen Us → Our Instant to capture a photo"
            )
            views.setViewVisibility(R.id.live_photo_image, android.view.View.GONE)
            views.setViewVisibility(R.id.live_photo_placeholder, android.view.View.VISIBLE)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_live_photo_root, pending)
        manager.updateAppWidget(id, views)
    }
}

class MyInstantWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        MemberInstantWidgetRenderer.updateAll(context, manager, ids, partner = false, scheduleSync = true)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        OurInstantWidgetDesigns.remove(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        MemberInstantWidgetRenderer.updateWidget(context, appWidgetId, partner = false)
    }

    override fun onDisabled(context: Context) {
        MemberInstantWidgetRenderer.cancelPeriodicIfUnused(context)
        super.onDisabled(context)
    }

    companion object {
        fun updateWidget(context: Context, appWidgetId: Int) =
            MemberInstantWidgetRenderer.updateWidget(context, appWidgetId, partner = false)

        fun updateAll(context: Context, scheduleSync: Boolean = true) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MyInstantWidgetProvider::class.java))
            MemberInstantWidgetRenderer.updateAll(context, manager, ids, partner = false, scheduleSync = scheduleSync)
        }
    }
}

class PartnerInstantWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        MemberInstantWidgetRenderer.updateAll(context, manager, ids, partner = true, scheduleSync = true)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        OurInstantWidgetDesigns.remove(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        MemberInstantWidgetRenderer.updateWidget(context, appWidgetId, partner = true)
    }

    override fun onDisabled(context: Context) {
        MemberInstantWidgetRenderer.cancelPeriodicIfUnused(context)
        super.onDisabled(context)
    }

    companion object {
        fun updateWidget(context: Context, appWidgetId: Int) =
            MemberInstantWidgetRenderer.updateWidget(context, appWidgetId, partner = true)

        fun updateAll(context: Context, scheduleSync: Boolean = true) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PartnerInstantWidgetProvider::class.java))
            MemberInstantWidgetRenderer.updateAll(context, manager, ids, partner = true, scheduleSync = scheduleSync)
        }
    }
}
