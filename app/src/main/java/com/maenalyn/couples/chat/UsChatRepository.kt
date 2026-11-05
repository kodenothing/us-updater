package com.maenalyn.couples.chat

import com.google.firebase.auth.FirebaseAuth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
internal data class UsChatRoom(
    val id: String,
    @SerialName("firebase_couple_code")
    val firebaseCoupleCode: String
)

@Serializable
internal data class UsChatMessage(
    val id: String,
    @SerialName("room_id")
    val roomId: String,
    @SerialName("sender_uid")
    val senderUid: String,
    @SerialName("message_type")
    val messageType: String = "text",
    val body: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("client_message_id")
    val clientMessageId: String? = null,
    @Transient
    val isPending: Boolean = false,
    @Transient
    val sendError: String? = null
)

@Serializable
internal data class UsChatRoomRead(
    @SerialName("room_id")
    val roomId: String,
    @SerialName("reader_uid")
    val readerUid: String,
    @SerialName("last_read_message_id")
    val lastReadMessageId: String? = null,
    @SerialName("last_read_at")
    val lastReadAt: String? = null
)

internal data class UsChatMessagePage(
    val messages: List<UsChatMessage>,
    val hasMore: Boolean
)

@Serializable
private data class UsChatMessageInsert(
    @SerialName("room_id")
    val roomId: String,
    @SerialName("sender_uid")
    val senderUid: String,
    val body: String,
    @SerialName("client_message_id")
    val clientMessageId: String
)

@Serializable
private data class UsChatRoomReadInsert(
    @SerialName("room_id")
    val roomId: String,
    @SerialName("reader_uid")
    val readerUid: String,
    @SerialName("last_read_message_id")
    val lastReadMessageId: String
)

@Serializable
private data class UsChatRoomReadUpdate(
    @SerialName("last_read_message_id")
    val lastReadMessageId: String
)

/**
 * All reads and writes use the current Firebase ID token through the shared
 * Supabase client. Supabase RLS remains the authorization boundary.
 */
internal object UsChatRepository {
    const val MESSAGE_PAGE_SIZE = 50

    suspend fun findRoom(coupleCode: String): UsChatRoom {
        val rooms = SupabaseChatClientProvider.get()
            .from("chat_rooms")
            .select {
                filter {
                    eq("firebase_couple_code", coupleCode)
                }
            }
            .decodeList<UsChatRoom>()

        check(rooms.size == 1 && rooms.single().firebaseCoupleCode == coupleCode) {
            "Couldn't find the private chat room for this shared space."
        }
        return rooms.single()
    }

    suspend fun loadLatestMessages(roomId: String): UsChatMessagePage {
        val rows = SupabaseChatClientProvider.get()
            .from("chat_messages")
            .select {
                filter {
                    eq("room_id", roomId)
                }
                order(column = "created_at", order = Order.DESCENDING)
                order(column = "id", order = Order.DESCENDING)
                limit(count = (MESSAGE_PAGE_SIZE + 1).toLong())
            }
            .decodeList<UsChatMessage>()

        val hasMore = rows.size > MESSAGE_PAGE_SIZE
        return UsChatMessagePage(
            messages = rows.take(MESSAGE_PAGE_SIZE).reversed(),
            hasMore = hasMore
        )
    }

    /**
     * Keyset pagination avoids offset drift when new messages arrive while the
     * user is browsing older history. The UUID is the deterministic tie-breaker
     * when multiple messages share the same created_at timestamp.
     */
    suspend fun loadMessagesBefore(
        roomId: String,
        before: UsChatMessage
    ): UsChatMessagePage {
        val rows = SupabaseChatClientProvider.get()
            .from("chat_messages")
            .select {
                filter {
                    eq("room_id", roomId)
                    or {
                        lt("created_at", before.createdAt)
                        and {
                            eq("created_at", before.createdAt)
                            lt("id", before.id)
                        }
                    }
                }
                order(column = "created_at", order = Order.DESCENDING)
                order(column = "id", order = Order.DESCENDING)
                limit(count = (MESSAGE_PAGE_SIZE + 1).toLong())
            }
            .decodeList<UsChatMessage>()

        val hasMore = rows.size > MESSAGE_PAGE_SIZE
        return UsChatMessagePage(
            messages = rows.take(MESSAGE_PAGE_SIZE).reversed(),
            hasMore = hasMore
        )
    }

    /**
     * The client-generated UUID is protected by a unique database index. A
     * retry after a lost HTTP response therefore cannot create a second row.
     */
    suspend fun sendMessage(
        roomId: String,
        senderUid: String,
        body: String,
        clientMessageId: String
    ): UsChatMessage {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        check(!currentUid.isNullOrBlank() && currentUid == senderUid) {
            "Your Firebase account changed. Reopen Us Chat and try again."
        }

        val normalizedBody = body.trim()
        require(normalizedBody.isNotEmpty()) { "Write a message first." }
        require(normalizedBody.length <= 4000) {
            "Messages must be 4,000 characters or fewer."
        }

        SupabaseChatClientProvider.get()
            .from("chat_messages")
            .upsert(
                UsChatMessageInsert(
                    roomId = roomId,
                    senderUid = senderUid,
                    body = normalizedBody,
                    clientMessageId = clientMessageId
                )
            ) {
                onConflict = "room_id,sender_uid,client_message_id"
                ignoreDuplicates = true
            }

        val saved = SupabaseChatClientProvider.get()
            .from("chat_messages")
            .select {
                filter {
                    eq("room_id", roomId)
                    eq("sender_uid", senderUid)
                    eq("client_message_id", clientMessageId)
                }
                limit(1)
            }
            .decodeList<UsChatMessage>()
            .firstOrNull()

        return saved ?: error("The message was queued but its server confirmation is not available yet.")
    }

    suspend fun loadReadCursor(roomId: String, readerUid: String): UsChatRoomRead? {
        return SupabaseChatClientProvider.get()
            .from("chat_room_reads")
            .select {
                filter {
                    eq("room_id", roomId)
                    eq("reader_uid", readerUid)
                }
                limit(1)
            }
            .decodeList<UsChatRoomRead>()
            .firstOrNull()
    }

    suspend fun loadPartnerReadCursor(roomId: String, currentUid: String): UsChatRoomRead? {
        return SupabaseChatClientProvider.get()
            .from("chat_room_reads")
            .select {
                filter {
                    eq("room_id", roomId)
                    neq("reader_uid", currentUid)
                }
                limit(1)
            }
            .decodeList<UsChatRoomRead>()
            .firstOrNull()
    }

    /**
     * Advances this member's read cursor. The UPDATE body contains only
     * last_read_message_id, matching the narrow column grant in the schema.
     */
    suspend fun markRead(roomId: String, readerUid: String, messageId: String) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        check(!currentUid.isNullOrBlank() && currentUid == readerUid) {
            "Your Firebase account changed. Reopen Us Chat and try again."
        }

        val client = SupabaseChatClientProvider.get()
        val existing = loadReadCursor(roomId, readerUid)
        if (existing?.lastReadMessageId == messageId) return

        if (existing == null) {
            try {
                client.from("chat_room_reads").insert(
                    UsChatRoomReadInsert(roomId, readerUid, messageId)
                )
            } catch (insertFailure: Exception) {
                // Another active device may have created this member's cursor
                // between our SELECT and INSERT. Only treat it as a race if the
                // row now exists; otherwise preserve the original error.
                val raced = loadReadCursor(roomId, readerUid)
                if (raced == null) throw insertFailure
                client.from("chat_room_reads").update(
                    UsChatRoomReadUpdate(messageId)
                ) {
                    filter {
                        eq("room_id", roomId)
                        eq("reader_uid", readerUid)
                    }
                }
            }
        } else {
            client.from("chat_room_reads").update(
                UsChatRoomReadUpdate(messageId)
            ) {
                filter {
                    eq("room_id", roomId)
                    eq("reader_uid", readerUid)
                }
            }
        }
    }
}
