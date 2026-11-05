package com.maenalyn.couples.chat

import android.content.ContentValues
import android.content.Context
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal data class UsChatOutboxMessage(
    val clientMessageId: String,
    val roomId: String,
    val senderUid: String,
    val body: String,
    val createdAt: String,
    val status: String,
    val lastError: String?
) {
    fun asPendingMessage() = UsChatMessage(
        id = clientMessageId,
        roomId = roomId,
        senderUid = senderUid,
        body = body,
        createdAt = createdAt,
        clientMessageId = clientMessageId,
        isPending = status != STATUS_FAILED,
        isAwaitingSync = status == STATUS_SENT,
        sendError = lastError
    )

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_FAILED = "failed"
        const val STATUS_SENT = "sent"
    }
}

private class UsChatOutboxOpenHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                client_message_id TEXT PRIMARY KEY NOT NULL,
                room_id TEXT NOT NULL,
                sender_uid TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at TEXT NOT NULL,
                status TEXT NOT NULL,
                last_error TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX us_chat_outbox_room_created_idx ON $TABLE_NAME(room_id, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the initial durable outbox schema. Future migrations
        // must preserve queued messages rather than dropping this table.
    }

    companion object {
        private const val DATABASE_NAME = "us_chat_outbox.db"
        private const val DATABASE_VERSION = 1
        const val TABLE_NAME = "us_chat_outbox"
    }
}

/**
 * Durable app-private outbox. WorkManager stores only the opaque message ID;
 * message text stays in the app's SQLite database and is never put in Work Data.
 */
internal object UsChatOutboxStore {
    private const val TABLE = UsChatOutboxOpenHelper.TABLE_NAME

    @Volatile
    private var helper: UsChatOutboxOpenHelper? = null

    private fun database(context: Context): SQLiteDatabase {
        val current = helper ?: synchronized(this) {
            helper ?: UsChatOutboxOpenHelper(context.applicationContext).also { helper = it }
        }
        return current.writableDatabase
    }

    suspend fun enqueue(context: Context, message: UsChatOutboxMessage) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("client_message_id", message.clientMessageId)
            put("room_id", message.roomId)
            put("sender_uid", message.senderUid)
            put("body", message.body)
            put("created_at", message.createdAt)
            put("status", UsChatOutboxMessage.STATUS_PENDING)
            putNull("last_error")
        }
        check(
            database(context).insertWithOnConflict(
                TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            ) != -1L
        ) { "Couldn't save this message to the local outbox." }
    }

    suspend fun get(context: Context, clientMessageId: String): UsChatOutboxMessage? =
        withContext(Dispatchers.IO) {
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
                if (cursor.moveToFirst()) cursor.toOutboxMessage() else null
            }
        }

    suspend fun forRoom(context: Context, roomId: String): List<UsChatOutboxMessage> =
        withContext(Dispatchers.IO) {
            database(context).query(
                TABLE,
                null,
                "room_id = ?",
                arrayOf(roomId),
                null,
                null,
                "created_at ASC, client_message_id ASC"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toOutboxMessage())
                }
            }
        }

    suspend fun markSent(context: Context, clientMessageId: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("status", UsChatOutboxMessage.STATUS_SENT)
            putNull("last_error")
        }
        database(context).update(
            TABLE,
            values,
            "client_message_id = ?",
            arrayOf(clientMessageId)
        )
    }

    suspend fun markPending(context: Context, clientMessageId: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("status", UsChatOutboxMessage.STATUS_PENDING)
            putNull("last_error")
        }
        database(context).update(
            TABLE,
            values,
            "client_message_id = ?",
            arrayOf(clientMessageId)
        )
    }

    suspend fun markFailed(context: Context, clientMessageId: String, reason: String) =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("status", UsChatOutboxMessage.STATUS_FAILED)
                put("last_error", reason.take(240))
            }
            database(context).update(
                TABLE,
                values,
                "client_message_id = ?",
                arrayOf(clientMessageId)
            )
        }

    suspend fun delete(context: Context, clientMessageId: String) = withContext(Dispatchers.IO) {
        database(context).delete(
            TABLE,
            "client_message_id = ?",
            arrayOf(clientMessageId)
        )
    }

    private fun android.database.Cursor.toOutboxMessage() = UsChatOutboxMessage(
        clientMessageId = getString(getColumnIndexOrThrow("client_message_id")),
        roomId = getString(getColumnIndexOrThrow("room_id")),
        senderUid = getString(getColumnIndexOrThrow("sender_uid")),
        body = getString(getColumnIndexOrThrow("body")),
        createdAt = getString(getColumnIndexOrThrow("created_at")),
        status = getString(getColumnIndexOrThrow("status")),
        lastError = getString(getColumnIndexOrThrow("last_error"))
    )
}

internal object UsChatOutboxScheduler {
    fun enqueue(context: Context, roomId: String, clientMessageId: String) {
        val request = OneTimeWorkRequestBuilder<UsChatSendWorker>()
            .setInputData(workDataOf(UsChatSendWorker.KEY_CLIENT_MESSAGE_ID to clientMessageId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .build()

        // A single serial chain per room preserves the order in which
        // messages were placed in the local outbox, including while offline.
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "us-chat-outbox-$roomId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}

/**
 * Retries transient send failures after connectivity returns. The server-side
 * unique client_message_id index makes retries idempotent.
 */
class UsChatSendWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val clientMessageId = inputData.getString(KEY_CLIENT_MESSAGE_ID)
            ?: return Result.failure()

        val queued = UsChatOutboxStore.get(applicationContext, clientMessageId)
            ?: return Result.success()

        return try {
            UsChatRepository.sendMessage(
                roomId = queued.roomId,
                senderUid = queued.senderUid,
                body = queued.body,
                clientMessageId = queued.clientMessageId
            )
            UsChatOutboxStore.markSent(applicationContext, clientMessageId)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                UsChatOutboxStore.markFailed(
                    applicationContext,
                    clientMessageId,
                    failure.message ?: "Message couldn't be sent. Tap to retry."
                )
                // Mark this item failed but let later messages in the same
                // room queue continue processing.
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_CLIENT_MESSAGE_ID = "client_message_id"
        private const val MAX_ATTEMPTS = 5
    }
}
