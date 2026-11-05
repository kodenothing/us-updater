package com.maenalyn.couples.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal object UsChatOutboxStatus {
    const val PENDING = "PENDING"
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
}

internal data class UsChatOutboxEntry(
    val clientMessageId: String,
    val roomId: String,
    val senderUid: String,
    val body: String,
    val localCreatedAtMs: Long,
    val status: String,
    val attemptCount: Int,
    val serverMessageId: String?,
    val serverCreatedAt: String?,
    val lastError: String?
)

/**
 * App-private durable storage for outgoing messages.
 * WorkManager receives only the opaque client message UUID, never message
 * text, Firebase tokens, or Supabase credentials.
 */
internal object UsChatOutboxStore {
    private const val TABLE = "us_chat_outbox"
    private val helperLock = Any()

    @Volatile
    private var helper: UsChatOutboxDatabase? = null

    private fun database(context: Context): SQLiteDatabase {
        val current = helper ?: synchronized(helperLock) {
            helper ?: UsChatOutboxDatabase(context.applicationContext).also { helper = it }
        }
        return current.writableDatabase
    }

    fun insert(
        context: Context,
        roomId: String,
        senderUid: String,
        body: String,
        clientMessageId: String
    ): UsChatOutboxEntry {
        require(roomId.isNotBlank())
        require(senderUid.isNotBlank())
        require(body.isNotBlank() && body.length <= 4000)
        require(clientMessageId.isNotBlank())

        val values = ContentValues().apply {
            put("client_message_id", clientMessageId)
            put("room_id", roomId)
            put("sender_uid", senderUid)
            put("body", body)
            put("local_created_at_ms", System.currentTimeMillis())
            put("status", UsChatOutboxStatus.PENDING)
            put("attempt_count", 0)
        }

        database(context).insertWithOnConflict(
            TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )

        return find(context, clientMessageId)
            ?: error("The message could not be saved to the local outbox.")
    }

    fun find(context: Context, clientMessageId: String): UsChatOutboxEntry? =
        database(context).query(
            TABLE,
            null,
            "client_message_id = ?",
            arrayOf(clientMessageId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toOutboxEntry() else null
        }

    fun listForRoom(
        context: Context,
        roomId: String,
        senderUid: String
    ): List<UsChatOutboxEntry> =
        database(context).query(
            TABLE,
            null,
            "room_id = ? AND sender_uid = ?",
            arrayOf(roomId, senderUid),
            null,
            null,
            "local_created_at_ms ASC, client_message_id ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toOutboxEntry())
            }
        }

    fun listPending(
        context: Context,
        roomId: String,
        senderUid: String
    ): List<UsChatOutboxEntry> =
        database(context).query(
            TABLE,
            null,
            "room_id = ? AND sender_uid = ? AND status = ?",
            arrayOf(roomId, senderUid, UsChatOutboxStatus.PENDING),
            null,
            null,
            "local_created_at_ms ASC, client_message_id ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toOutboxEntry())
            }
        }

    fun markSending(context: Context, clientMessageId: String) {
        database(context).execSQL(
            "UPDATE $TABLE SET status = ?, attempt_count = attempt_count + 1 " +
                "WHERE client_message_id = ? AND status NOT IN (?, ?)",
            arrayOf(
                UsChatOutboxStatus.SENDING,
                clientMessageId,
                UsChatOutboxStatus.SENT,
                UsChatOutboxStatus.FAILED
            )
        )
    }

    fun markPending(context: Context, clientMessageId: String, reason: String) {
        val values = ContentValues().apply {
            put("status", UsChatOutboxStatus.PENDING)
            put("last_error", reason.take(500))
        }
        database(context).update(
            TABLE,
            values,
            "client_message_id = ? AND status != ?",
            arrayOf(clientMessageId, UsChatOutboxStatus.SENT)
        )
    }

    fun markSent(
        context: Context,
        clientMessageId: String,
        serverMessageId: String,
        serverCreatedAt: String
    ) {
        val values = ContentValues().apply {
            put("status", UsChatOutboxStatus.SENT)
            put("server_message_id", serverMessageId)
            put("server_created_at", serverCreatedAt)
            putNull("last_error")
        }
        database(context).update(
            TABLE,
            values,
            "client_message_id = ?",
            arrayOf(clientMessageId)
        )
    }

    fun markFailed(context: Context, clientMessageId: String, reason: String) {
        val values = ContentValues().apply {
            put("status", UsChatOutboxStatus.FAILED)
            put("last_error", reason.take(500))
        }
        database(context).update(
            TABLE,
            values,
            "client_message_id = ? AND status != ?",
            arrayOf(clientMessageId, UsChatOutboxStatus.SENT)
        )
    }

    fun retry(context: Context, clientMessageId: String): UsChatOutboxEntry? {
        val values = ContentValues().apply {
            put("status", UsChatOutboxStatus.PENDING)
            put("attempt_count", 0)
            putNull("last_error")
        }
        database(context).update(
            TABLE,
            values,
            "client_message_id = ? AND status = ?",
            arrayOf(clientMessageId, UsChatOutboxStatus.FAILED)
        )
        return find(context, clientMessageId)
    }

    /**
     * Remove local rows only after a PostgREST page contains the exact
     * client_message_id. A worker success alone does not delete the row.
     */
    fun deleteConfirmed(
        context: Context,
        roomId: String,
        confirmedClientMessageIds: Set<String>
    ) {
        if (confirmedClientMessageIds.isEmpty()) return

        val placeholders = confirmedClientMessageIds.joinToString(",") { "?" }
        val args = arrayOf<Any>(roomId) + confirmedClientMessageIds
        database(context).execSQL(
            "DELETE FROM $TABLE WHERE room_id = ? AND client_message_id IN ($placeholders)",
            args
        )
    }

    private fun Cursor.toOutboxEntry(): UsChatOutboxEntry =
        UsChatOutboxEntry(
            clientMessageId = getString(getColumnIndexOrThrow("client_message_id")),
            roomId = getString(getColumnIndexOrThrow("room_id")),
            senderUid = getString(getColumnIndexOrThrow("sender_uid")),
            body = getString(getColumnIndexOrThrow("body")),
            localCreatedAtMs = getLong(getColumnIndexOrThrow("local_created_at_ms")),
            status = getString(getColumnIndexOrThrow("status")),
            attemptCount = getInt(getColumnIndexOrThrow("attempt_count")),
            serverMessageId = getString(getColumnIndexOrThrow("server_message_id")),
            serverCreatedAt = getString(getColumnIndexOrThrow("server_created_at")),
            lastError = getString(getColumnIndexOrThrow("last_error"))
        )
}

private class UsChatOutboxDatabase(context: Context) : SQLiteOpenHelper(
    context,
    "us_chat_outbox.db",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE us_chat_outbox (
                client_message_id TEXT PRIMARY KEY NOT NULL,
                room_id TEXT NOT NULL,
                sender_uid TEXT NOT NULL,
                body TEXT NOT NULL CHECK(length(body) BETWEEN 1 AND 4000),
                local_created_at_ms INTEGER NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
                attempt_count INTEGER NOT NULL DEFAULT 0,
                server_message_id TEXT,
                server_created_at TEXT,
                last_error TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX us_chat_outbox_room_sender_order " +
                "ON us_chat_outbox(room_id, sender_uid, local_created_at_ms)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

internal object UsChatOutboxScheduler {
    private const val WORK_NAME_PREFIX = "us-chat-outbox-room-"
    private const val MAX_ATTEMPTS = 5

    fun enqueue(context: Context, entry: UsChatOutboxEntry) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UsChatSendWorker>()
            .setInputData(workDataOf(UsChatSendWorker.KEY_CLIENT_MESSAGE_ID to entry.clientMessageId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("us-chat-outbox")
            .build()

        // APPEND_OR_REPLACE serializes messages in queue order for this room,
        // while allowing later messages after a failed item.
        WorkManager.getInstance(context.applicationContext)
            .beginUniqueWork(
                WORK_NAME_PREFIX + entry.roomId,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            .enqueue()
    }

    suspend fun enqueuePending(context: Context, roomId: String, senderUid: String) {
        val pending = withContext(Dispatchers.IO) {
            UsChatOutboxStore.listPending(context, roomId, senderUid)
        }
        pending.forEach { enqueue(context, it) }
    }

    internal fun maxAttempts(): Int = MAX_ATTEMPTS
}

internal class UsChatSendWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val clientMessageId = inputData.getString(KEY_CLIENT_MESSAGE_ID)
            ?: return Result.failure()

        val entry = withContext(Dispatchers.IO) {
            UsChatOutboxStore.find(applicationContext, clientMessageId)
        } ?: return Result.success()

        if (entry.status == UsChatOutboxStatus.SENT ||
            entry.status == UsChatOutboxStatus.FAILED
        ) {
            return Result.success()
        }

        val activeUid = FirebaseAuth.getInstance().currentUser?.uid
        if (activeUid.isNullOrBlank() || activeUid != entry.senderUid) {
            withContext(Dispatchers.IO) {
                UsChatOutboxStore.markFailed(
                    applicationContext,
                    clientMessageId,
                    "Sign in with the account that created this message to send it."
                )
            }
            // A delivery failure is stored in SQLite. Complete this WorkRequest
            // successfully so later messages in the room chain are not cancelled.
            return Result.success()
        }

        withContext(Dispatchers.IO) {
            UsChatOutboxStore.markSending(applicationContext, clientMessageId)
        }

        return try {
            val confirmed = UsChatRepository.sendMessage(
                roomId = entry.roomId,
                senderUid = entry.senderUid,
                body = entry.body,
                clientMessageId = entry.clientMessageId
            )

            withContext(Dispatchers.IO) {
                UsChatOutboxStore.markSent(
                    applicationContext,
                    clientMessageId,
                    confirmed.id,
                    confirmed.createdAt
                )
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = failure.message ?: "Message delivery failed."
            if (runAttemptCount + 1 < UsChatOutboxScheduler.maxAttempts()) {
                withContext(Dispatchers.IO) {
                    UsChatOutboxStore.markPending(applicationContext, clientMessageId, reason)
                }
                Result.retry()
            } else {
                withContext(Dispatchers.IO) {
                    UsChatOutboxStore.markFailed(applicationContext, clientMessageId, reason)
                }
                // Permanent item failure must not cancel the next room-queue item.
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_CLIENT_MESSAGE_ID = "client_message_id"
    }
}
