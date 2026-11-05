package com.maenalyn.couples.chat

import com.google.firebase.auth.FirebaseAuth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val clientMessageId: String? = null
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
     * user is browsing history. UUID is the tie-breaker for equal timestamps.
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
     * Idempotent send: retry the same UUID if the HTTP result is ambiguous.
     * The database unique index prevents a duplicate server row.
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

        val client = SupabaseChatClientProvider.get()
        client.from("chat_messages").upsert(
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

        return client.from("chat_messages")
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
            ?: error("The server has not confirmed this message yet. Retry safely.")
    }
}
