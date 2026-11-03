package com.maenalyn.couples

import android.content.Context
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object PartnerActivityNotifications {
    private val db get() = FirebaseFirestore.getInstance()

    fun observe(
        context: Context,
        coupleCode: String,
        currentUid: String,
        enabled: () -> Boolean
    ): Flow<Unit> = callbackFlow {
        val root = db.collection("couples").document(coupleCode)
        var memberNames: Map<String, String> = emptyMap()
        var hasServerBaseline = false
        val listeners = mutableListOf<ListenerRegistration>()

        listeners += root.addSnapshotListener { snapshot, _ ->
            if (snapshot != null) {
                memberNames = (snapshot.get("memberNames") as? Map<*, *>)
                    ?.entries
                    ?.mapNotNull { (key, value) -> if (key is String && value is String) key to value else null }
                    ?.toMap()
                    .orEmpty()
            }
        }

        listeners += root.collection("activity")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                if (!hasServerBaseline) {
                    if (!snapshot.metadata.isFromCache) hasServerBaseline = true
                    return@addSnapshotListener
                }
                if (!enabled()) return@addSnapshotListener
                snapshot.documentChanges.forEach { change ->
                    if (change.type != DocumentChange.Type.ADDED) return@forEach
                    val doc = change.document
                    if (doc.metadata.hasPendingWrites()) return@forEach
                    val actorUid = doc.getString("actorUid") ?: return@forEach
                    if (actorUid == currentUid) return@forEach
                    val title = doc.getString("title")?.takeIf { it.isNotBlank() } ?: "Shared activity"
                    val summary = doc.getString("summary")?.takeIf { it.isNotBlank() } ?: "Open Us to see what changed."
                    val actorName = memberNames[actorUid]?.trim()?.takeIf { it.isNotBlank() } ?: "Your person"
                    val category = when (doc.getString("type")) {
                        "todo_created", "bucket_created", "todo_completed" -> "lists"
                        "event_created", "event_updated" -> "events"
                        "note_created" -> "notes"
                        "memory_created" -> "memories"
                        "daily_answered", "daily_checkin" -> "daily"
                        else -> "other"
                    }
                    UsNotifications.showPartnerUpdate(
                        context = context,
                        title = "$actorName ${title.replaceFirstChar { it.lowercase() }}",
                        body = summary,
                        notificationKey = "$coupleCode:activity:${doc.id}",
                        category = category
                    )
                }
            }
        trySend(Unit)
        awaitClose { listeners.forEach { it.remove() } }
    }
}
