package com.maenalyn.couples.chat

import com.google.firebase.auth.FirebaseAuth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
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
    val clientMessageId: String? = null,
    @SerialName("deleted_at")
    val deletedAt: String? = null,
    @SerialName("deleted_by_uid")
    val deletedByUid: String? = null
)

internal data class UsChatMessagePage(
    val messages: List<UsChatMessage>,
    val hasMore: Boolean
)


@Serializable
internal data class UsChatReadRecord(
    @SerialName("room_id")
    val roomId: String,
    @SerialName("reader_uid")
    val readerUid: String,
    @SerialName("last_read_message_id")
    val lastReadMessageId: String? = null,
    @SerialName("last_read_at")
    val lastReadAt: String
)

internal data class UsChatReadState(
    val readerUid: String,
    val lastReadMessageId: String?,
    val lastReadMessageCreatedAt: String?,
    val lastReadAt: String
)


@Serializable
private data class UsChatMessageActionParams(
    @SerialName("p_room_id")
    val roomId: String,
    @SerialName("p_message_id")
    val messageId: String
)

@Serializable
internal data class UsChatMessageHideRecord(
    @SerialName("room_id")
    val roomId: String,
    @SerialName("message_id")
    val messageId: String,
    @SerialName("hidden_by_uid")
    val hiddenByUid: String
)

@Serializable
private data class UsChatReadInsert(
    @SerialName("room_id") val roomId: String,
    @SerialName("reader_uid") val readerUid: String,
    @SerialName("last_read_message_id") val lastReadMessageId: String
)

@Serializable
private data class UsChatReadUpdate(
    @SerialName("last_read_message_id") val lastReadMessageId: String
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

    suspend fun loadMessagesByIds(
        roomId: String,
        messageIds: Collection<String>
    ): List<UsChatMessage> {
        if (messageIds.isEmpty()) return emptyList()

        val client = SupabaseChatClientProvider.get()
        return messageIds.distinct().chunked(100).flatMap { batch ->
            client.from("chat_messages")
                .select {
                    filter {
                        eq("room_id", roomId)
                        isIn("id", batch)
                    }
                }
                .decodeList<UsChatMessage>()
        }
    }

    suspend fun loadHiddenMessageIds(
        roomId: String,
        readerUid: String
    ): Set<String> {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        check(!currentUid.isNullOrBlank() && currentUid == readerUid) {
            "Your Firebase account changed. Reopen Us Chat to refresh this conversation."
        }

        return SupabaseChatClientProvider.get()
            .from("chat_message_user_hides")
            .select {
                filter {
                    eq("room_id", roomId)
                    eq("hidden_by_uid", readerUid)
                }
            }
            .decodeList<UsChatMessageHideRecord>()
            .mapTo(mutableSetOf()) { it.messageId }
    }

    suspend fun removeMessageForMe(
        roomId: String,
        messageId: String,
        readerUid: String
    ) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        check(!currentUid.isNullOrBlank() && currentUid == readerUid) {
            "Your Firebase account changed. Reopen Us Chat before removing this message."
        }

        SupabaseChatClientProvider.get().postgrest.rpc(
            "chat_hide_message_for_me",
            UsChatMessageActionParams(roomId, messageId)
        )
    }

    suspend fun removeMessageForEveryone(
        roomId: String,
        messageId: String,
        senderUid: String
    ) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        check(!currentUid.isNullOrBlank() && currentUid == senderUid) {
            "Only the account that sent this message can remove it for everyone."
        }

        SupabaseChatClientProvider.get().postgrest.rpc(
            "chat_remove_message_for_everyone",
            UsChatMessageActionParams(roomId, messageId)
        )
    }

    /**
     * Read state is room-scoped and RLS limits each write to the current Firebase UID.
     * The database trigger in the read-receipt migration prevents cursor regression.
     */
    suspend fun loadReadStates(roomId: String): List<UsChatReadState> {
        val client = SupabaseChatClientProvider.get()
        val records = client.from("chat_room_reads")
            .select {
                filter { eq("room_id", roomId) }
            }
            .decodeList<UsChatReadRecord>()

        return records.map { record ->
            val cursorMessage = record.lastReadMessageId?.let { messageId ->
                client.from("chat_messages")
                    .select {
                        filter {
                            eq("room_id", roomId)
                            eq("id", messageId)
                        }
                        limit(1)
                    }
                    .decodeList<UsChatMessage>()
                    .firstOrNull()
            }
            UsChatReadState(
                readerUid = record.readerUid,
                lastReadMessageId = record.lastReadMessageId,
                lastReadMessageCreatedAt = cursorMessage?.createdAt,
                lastReadAt = record.lastReadAt
            )
        }
    }

    suspend fun advanceReadCursor(
        roomId: String,
        readerUid: String,
        messageId: String
    ) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        check(!currentUid.isNullOrBlank() && currentUid == readerUid) {
            "Your Firebase account changed. Reopen Us Chat before updating read status."
        }

        val client = SupabaseChatClientProvider.get()
        val existing = client.from("chat_room_reads")
            .select {
                filter {
                    eq("room_id", roomId)
                    eq("reader_uid", readerUid)
                }
                limit(1)
            }
            .decodeList<UsChatReadRecord>()
            .firstOrNull()

        if (existing == null) {
            client.from("chat_room_reads").insert(
                UsChatReadInsert(
                    roomId = roomId,
                    readerUid = readerUid,
                    lastReadMessageId = messageId
                )
            )
        } else if (existing.lastReadMessageId != messageId) {
            client.from("chat_room_reads").update(
                UsChatReadUpdate(lastReadMessageId = messageId)
            ) {
                filter {
                    eq("room_id", roomId)
                    eq("reader_uid", readerUid)
                }
            }
        }
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
