package com.maenalyn.couples

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.Source
import java.util.concurrent.ConcurrentHashMap

object FeatureRepo {
    const val MEMORY_PAGE_SIZE = 36L
    private val db get() = FirebaseFirestore.getInstance()
    private val safeMemoryFileName = Regex("^[A-Za-z0-9_-]{1,60}\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE)
    private val memoryCursors = ConcurrentHashMap<String, DocumentSnapshot>()
    private val memoryPagingStarted = ConcurrentHashMap.newKeySet<String>()
    private val root get() = db.collection("couples")
    private fun couple(code: String) = root.document(code)

    private fun appendActivity(
        batch: com.google.firebase.firestore.WriteBatch,
        code: String,
        actorUid: String,
        type: String,
        title: String,
        summary: String,
        targetCollection: String,
        targetId: String,
        createdAt: Long = System.currentTimeMillis()
    ) {
        val ref = couple(code).collection("activity").document()
        batch.set(ref, mapOf(
            "actorUid" to actorUid,
            "type" to type,
            "title" to title.take(100),
            "summary" to summary.take(180),
            "targetCollection" to targetCollection,
            "targetId" to targetId,
            "createdAt" to createdAt
        ))
    }

    fun observeActivity(code: String): Flow<List<ActivityEntry>> = callbackFlow {
        val registration = couple(code).collection("activity")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) trySend(snapshot.documents.map { doc ->
                    ActivityEntry(
                        id = doc.id,
                        actorUid = doc.getString("actorUid") ?: "",
                        type = doc.getString("type") ?: "update",
                        title = doc.getString("title") ?: "A little update",
                        summary = doc.getString("summary") ?: "",
                        targetCollection = doc.getString("targetCollection") ?: "",
                        targetId = doc.getString("targetId") ?: "",
                        createdAt = doc.getLong("createdAt") ?: 0L
                    )
                })
            }
        awaitClose { registration.remove() }
    }

    fun observeEvents(code: String): Flow<List<CalendarEvent>> = callbackFlow {
        val registration = couple(code).collection("events").orderBy("dateEpochDay")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) trySend(snapshot.documents.map { doc ->
                    CalendarEvent(
                        id = doc.id,
                        title = doc.getString("title") ?: "",
                        description = doc.getString("description") ?: "",
                        dateEpochDay = doc.getLong("dateEpochDay") ?: 0L,
                        allDay = doc.getBoolean("allDay") ?: true,
                        startTime = doc.getString("startTime") ?: "",
                        endTime = doc.getString("endTime") ?: "",
                        reminderMinutes = (doc.getLong("reminderMinutes") ?: 0L).toInt(),
                        createdBy = doc.getString("createdBy") ?: "",
                        createdAt = doc.getLong("createdAt") ?: 0L
                    )
                })
            }
        awaitClose { registration.remove() }
    }

    fun addEvent(code: String, uid: String, event: CalendarEventDraft): Task<Void> {
        val now = System.currentTimeMillis()
        val ref = couple(code).collection("events").document()
        val batch = db.batch()
        batch.set(ref, mapOf(
            "title" to event.title.trim(),
            "description" to event.description.trim(),
            "dateEpochDay" to event.dateEpochDay,
            "allDay" to event.allDay,
            "startTime" to event.startTime,
            "endTime" to event.endTime,
            "reminderMinutes" to event.reminderMinutes,
            "createdBy" to uid,
            "createdAt" to now,
            "updatedAt" to now
        ))
        appendActivity(batch, code, uid, "event_created", "Added a plan", event.title, "events", ref.id, now)
        return batch.commit()
    }

    fun updateEvent(code: String, id: String, uid: String, event: CalendarEventDraft): Task<Void> {
        val now = System.currentTimeMillis()
        val batch = db.batch()
        batch.update(couple(code).collection("events").document(id), mapOf(
            "title" to event.title.trim(),
            "description" to event.description.trim(),
            "dateEpochDay" to event.dateEpochDay,
            "allDay" to event.allDay,
            "startTime" to event.startTime,
            "endTime" to event.endTime,
            "reminderMinutes" to event.reminderMinutes,
            "updatedAt" to now
        ))
        appendActivity(batch, code, uid, "event_updated", "Updated a plan", event.title, "events", id, now)
        return batch.commit()
    }

    fun deleteEvent(code: String, id: String): Task<Void> =
        couple(code).collection("events").document(id).delete()

    fun observeNotes(code: String): Flow<List<LoveNote>> = callbackFlow {
        val registration = couple(code).collection("notes")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) trySend(snapshot.documents.map { doc ->
                    LoveNote(
                        id = doc.id,
                        title = doc.getString("title") ?: "",
                        text = doc.getString("text") ?: "",
                        createdBy = doc.getString("createdBy") ?: "",
                        createdAt = doc.getLong("createdAt") ?: 0L,
                        pinned = doc.getBoolean("pinned") ?: false
                    )
                })
            }
        awaitClose { registration.remove() }
    }

    fun addNote(code: String, uid: String, title: String, text: String): Task<Void> {
        val now = System.currentTimeMillis()
        val ref = couple(code).collection("notes").document()
        val batch = db.batch()
        batch.set(ref, mapOf(
            "title" to title.trim(), "text" to text.trim(), "createdBy" to uid,
            "createdAt" to now, "updatedAt" to now, "pinned" to false
        ))
        appendActivity(batch, code, uid, "note_created", "Sent a love note", title.ifBlank { text }, "notes", ref.id, now)
        return batch.commit()
    }

    fun setNotePinned(code: String, id: String, pinned: Boolean): Task<Void> =
        couple(code).collection("notes").document(id).update(
            mapOf("pinned" to pinned, "updatedAt" to System.currentTimeMillis())
        )

    fun deleteNote(code: String, id: String): Task<Void> =
        couple(code).collection("notes").document(id).delete()

    private fun mapMemory(doc: DocumentSnapshot): Memory {
        val storagePath = doc.getString("storagePath") ?: ""
        val fileName = listOf(doc.getString("fileName"), storagePath.substringAfterLast('/'))
            .filterNotNull().firstOrNull { safeMemoryFileName.matches(it) }
            ?: "${Integer.toHexString(doc.id.hashCode())}.jpg"
        val photoUrl = doc.getString("photoUrl")
            ?.takeIf { it.startsWith("https://firebasestorage.googleapis.com/") }
            .orEmpty()
        val createdAt = doc.getLong("createdAt") ?: 0L
        val legacyAlbumId = java.time.YearMonth.from(
            java.time.Instant.ofEpochMilli(createdAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        ).toString()
        return Memory(
            id = doc.id,
            caption = doc.getString("caption") ?: "",
            photoUrl = photoUrl,
            storagePath = storagePath,
            fileName = fileName,
            status = doc.getString("status") ?: "pending",
            storageMode = doc.getString("storageMode") ?: "legacy_storage",
            createdBy = doc.getString("createdBy") ?: "",
            createdAt = createdAt,
            favorite = doc.getBoolean("favorite") ?: false,
            sizeBytes = doc.getLong("sizeBytes") ?: 0L,
            albumId = doc.getString("albumId")?.takeIf { it.isNotBlank() } ?: legacyAlbumId
        )
    }

    fun observeMemoryAlbums(code: String): Flow<List<MemoryAlbum>> = callbackFlow {
        val registration = couple(code).collection("memoryAlbums")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) trySend(snapshot.documents.map { doc ->
                    MemoryAlbum(
                        id = doc.id,
                        name = doc.getString("name") ?: "Untitled album",
                        createdBy = doc.getString("createdBy") ?: "",
                        createdAt = doc.getLong("createdAt") ?: 0L,
                        updatedAt = doc.getLong("updatedAt") ?: 0L
                    )
                })
            }
        awaitClose { registration.remove() }
    }

    fun createMemoryAlbum(code: String, albumId: String, name: String, uid: String): Task<Void> {
        val now = System.currentTimeMillis()
        return couple(code).collection("memoryAlbums").document(albumId).set(mapOf(
            "name" to name.trim().take(48),
            "createdBy" to uid,
            "createdAt" to now,
            "updatedBy" to uid,
            "updatedAt" to now
        ))
    }

    fun renameMemoryAlbum(code: String, albumId: String, name: String, uid: String): Task<Void> {
        val ref = couple(code).collection("memoryAlbums").document(albumId)
        val now = System.currentTimeMillis()
        return ref.get().continueWithTask { result ->
            if (!result.isSuccessful) throw result.exception ?: IllegalStateException("Couldn't check this album.")
            if (result.result?.exists() == true) {
                ref.update(mapOf("name" to name.trim().take(48), "updatedBy" to uid, "updatedAt" to now))
            } else {
                ref.set(mapOf(
                    "name" to name.trim().take(48),
                    "createdBy" to uid,
                    "createdAt" to now,
                    "updatedBy" to uid,
                    "updatedAt" to now
                ))
            }
        }
    }

    fun moveMemories(code: String, memoryIds: List<String>, albumId: String): Task<Void> {
        require(memoryIds.size <= 450) { "Move at most 450 photos in one operation." }
        val batch = db.batch()
        memoryIds.distinct().forEach { id ->
            batch.update(couple(code).collection("memories").document(id), "albumId", albumId)
        }
        return batch.commit()
    }

    fun observeMemoryAlbumCovers(code: String): Flow<Map<String, String>> = callbackFlow {
        val registration = couple(code).collection("memoryAlbumCovers")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    trySend(snapshot.documents.mapNotNull { document ->
                        val coverId = document.getString("coverMemoryId")
                        if (coverId.isNullOrBlank()) null else document.id to coverId
                    }.toMap())
                }
            }
        awaitClose { registration.remove() }
    }

    fun setMemoryAlbumCover(
        code: String,
        albumId: String,
        memoryId: String,
        uid: String
    ): Task<Void> = couple(code).collection("memoryAlbumCovers").document(albumId).set(
        mapOf(
            "coverMemoryId" to memoryId,
            "updatedBy" to uid,
            "updatedAt" to System.currentTimeMillis()
        )
    )

    fun observeMemories(code: String): Flow<List<Memory>> = callbackFlow {
        memoryCursors.remove(code)
        memoryPagingStarted.remove(code)
        val registration = couple(code).collection("memories")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(MEMORY_PAGE_SIZE)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    // Keep the original first-page boundary stable after older pages
                    // have been requested; new photos must not move the paging cursor.
                    if (!memoryPagingStarted.contains(code)) {
                        snapshot.documents.lastOrNull()?.let { memoryCursors[code] = it }
                    }
                    trySend(snapshot.documents.map(::mapMemory))
                }
            }
        awaitClose {
            registration.remove()
            memoryCursors.remove(code)
            memoryPagingStarted.remove(code)
        }
    }

    suspend fun loadOlderMemories(code: String): List<Memory> {
        val cursor = memoryCursors[code] ?: return emptyList()
        memoryPagingStarted.add(code)
        val snapshot = couple(code).collection("memories")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .startAfter(cursor)
            .limit(MEMORY_PAGE_SIZE)
            .get(Source.SERVER).await()
        snapshot.documents.lastOrNull()?.let { memoryCursors[code] = it }
        return snapshot.documents.map(::mapMemory)
    }

    fun createMemoryPending(
        code: String,
        id: String,
        uid: String,
        caption: String,
        fileName: String,
        albumId: String,
        createdAt: Long
    ): Task<Void> = couple(code).collection("memories").document(id).set(
        mapOf(
            "caption" to caption.trim(),
            "fileName" to fileName,
            "albumId" to albumId,
            "photoUrl" to "",
            "storagePath" to "",
            "storageMode" to "firestore_blob",
            "status" to "pending",
            "createdBy" to uid,
            "createdAt" to createdAt,
            "favorite" to false,
            "sizeBytes" to 0L
        )
    )

    fun uploadMemoryBlob(code: String, id: String, uid: String, imageBytes: ByteArray, createdAt: Long): Task<Void> =
        couple(code).collection("memories").document(id).collection("content").document("image")
            .set(mapOf(
                "image" to Blob.fromBytes(imageBytes),
                "contentType" to "image/jpeg",
                "createdBy" to uid,
                "createdAt" to createdAt
            ))

    fun uploadMemoryThumbnail(code: String, id: String, uid: String, imageBytes: ByteArray, createdAt: Long): Task<Void> =
        couple(code).collection("memories").document(id).collection("content").document("thumbnail")
            .set(mapOf(
                "image" to Blob.fromBytes(imageBytes),
                "contentType" to "image/jpeg",
                "createdBy" to uid,
                "createdAt" to createdAt
            ))

    fun getMemoryBlob(code: String, id: String) = couple(code)
        .collection("memories").document(id).collection("content").document("image").get()

    fun getMemoryThumbnail(code: String, id: String) = couple(code)
        .collection("memories").document(id).collection("content").document("thumbnail").get()

    fun finishMemoryUpload(code: String, id: String, uid: String, sizeBytes: Long, thumbnailBytes: Long): Task<Void> {
        val now = System.currentTimeMillis()
        val batch = db.batch()
        batch.update(couple(code).collection("memories").document(id), mapOf(
            "status" to "ready",
            "sizeBytes" to sizeBytes,
            "thumbnailBytes" to thumbnailBytes
        ))
        appendActivity(batch, code, uid, "memory_created", "Added a memory", "A new photo was added to your memories.", "memories", id, now)
        return batch.commit()
    }

    fun setMemoryFavorite(code: String, id: String, favorite: Boolean): Task<Void> =
        couple(code).collection("memories").document(id).update("favorite", favorite)

    fun deleteMemory(code: String, id: String): Task<Void> {
        val memory = couple(code).collection("memories").document(id)
        return memory.collection("content").document("image").delete()
            .continueWithTask { imageTask ->
                if (!imageTask.isSuccessful) throw imageTask.exception ?: IllegalStateException("Couldn't delete photo data.")
                memory.collection("content").document("thumbnail").delete()
            }.continueWithTask { thumbnailTask ->
                if (!thumbnailTask.isSuccessful) throw thumbnailTask.exception ?: IllegalStateException("Couldn't delete thumbnail data.")
                memory.delete()
            }
    }

    fun createActivity(
        code: String,
        uid: String,
        type: String,
        title: String,
        summary: String,
        targetCollection: String,
        targetId: String
    ): Task<Void> {
        val ref = couple(code).collection("activity").document()
        return ref.set(mapOf(
            "actorUid" to uid,
            "type" to type,
            "title" to title.take(100),
            "summary" to summary.take(180),
            "targetCollection" to targetCollection,
            "targetId" to targetId,
            "createdAt" to System.currentTimeMillis()
        ))
    }
}
