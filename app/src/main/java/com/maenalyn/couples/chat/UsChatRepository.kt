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
    val createdAt: String
)

@Serializable
private data class UsChatMessageInsert(
    @SerialName("room_id")
    val roomId: String,
    @SerialName("sender_uid")
    val senderUid: String,
    val body: String
)

/**
 * Initial text-chat data access. All operations use the existing Firebase ID
 * token through SupabaseChatClientProvider and remain protected by database RLS.
 */
internal object UsChatRepository {
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

    suspend fun loadMessages(roomId: String): List<UsChatMessage> {
        return SupabaseChatClientProvider.get()
            .from("chat_messages")
            .select {
                filter {
                    eq("room_id", roomId)
                }
                order(column = "created_at", order = Order.DESCENDING)
                order(column = "id", order = Order.DESCENDING)
                limit(count = 100L)
            }
            .decodeList<UsChatMessage>()
            .reversed()
    }

    suspend fun sendMessage(roomId: String, senderUid: String, text: String) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        check(!currentUid.isNullOrBlank() && currentUid == senderUid) {
            "Your Firebase account changed. Reopen Us Chat and try again."
        }

        val body = text.trim()
        require(body.isNotEmpty()) { "Write a message first." }
        require(body.length <= 4000) { "Messages must be 4,000 characters or fewer." }

        SupabaseChatClientProvider.get()
            .from("chat_messages")
            .insert(UsChatMessageInsert(roomId, senderUid, body))
    }
}
