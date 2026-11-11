package com.maenalyn.couples

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.security.SecureRandom
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** Default together date for this app: March 14, 2024. */
val DEFAULT_ANNIVERSARY: Long = java.time.LocalDate.of(2024, 3, 14).toEpochDay()

data class Item(val id: String, val type: String, val text: String, val done: Boolean)

sealed interface CoupleState {
    /** The server confirmed that this couple document no longer exists. */
    data object Gone : CoupleState
    /** The couple could not be verified; this must never clear local pairing data. */
    data class Unavailable(val message: String) : CoupleState
    data class Active(
        val memberCount: Int,
        val anniversaryEpochDay: Long?,
        val coupleName: String = "Us 💜",
        val memberNames: Map<String, String> = emptyMap(),
        val memberIds: List<String> = emptyList(),
        val deleteApprovals: Map<String, Boolean> = emptyMap(),
        val memberGenders: Map<String, String> = emptyMap()
    ) : CoupleState
}

class PairingException(message: String) : Exception(message)

object Repo {
    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val NEW_CODE_LENGTH = 12
    private const val NET_TIMEOUT = 15_000L
    private const val OFFLINE = "You need internet for this."
    private val secureRandom = SecureRandom()

    private fun couple(code: String) = db.collection("couples").document(code)

    fun currentEmail(): String? = auth.currentUser?.email
    fun currentUid(): String? = auth.currentUser?.uid

    /** Silent anonymous sign-in: no login screen, just a private ID per phone. */
    suspend fun ensureSignedIn(): String {
        auth.currentUser?.let { return it.uid }
        val res = withTimeoutOrNull(NET_TIMEOUT.milliseconds) { auth.signInAnonymously().await() }
            ?: throw PairingException("You need internet for first-time setup.")
        return res.user?.uid ?: throw PairingException("Couldn't create your account. Please try again.")
    }

    private fun newCode(): String = buildString(NEW_CODE_LENGTH) {
        repeat(NEW_CODE_LENGTH) {
            append(ALPHABET[secureRandom.nextInt(ALPHABET.length)])
        }
    }

    /** Creates a new couple without ever overwriting an existing document. */
    suspend fun createCouple(uid: String): String {
        val data = mapOf(
            "members" to listOf(uid),
            "createdAt" to System.currentTimeMillis(),
            "anniversary" to DEFAULT_ANNIVERSARY,
            "coupleName" to "Us 💜",
            "memberNames" to mapOf(uid to "Me"),
            "deleteApprovals" to emptyMap<String, Boolean>(),
            "inviteOnly" to true
        )

        for (attempt in 0 until 8) {
            val code = newCode()
            try {
                val created = withTimeoutOrNull(NET_TIMEOUT.milliseconds) {
                    db.runTransaction { transaction ->
                        val ref = couple(code)
                        if (transaction.get(ref).exists()) {
                            false
                        } else {
                            transaction.set(ref, data)
                            true
                        }
                    }.await()
                } ?: throw PairingException(OFFLINE)

                if (created) return code
            } catch (e: FirebaseFirestoreException) {
                // A permission denial can mean the candidate already belongs to a full couple.
                // Do not expose that document; simply generate another candidate.
                if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
            }
        }

        throw PairingException("Couldn't create an invite. Check that the updated Firestore rules are published, then try again.")
    }

    /** Accepts legacy 8-character codes and new 12-character codes. */
    suspend fun joinCouple(uid: String, raw: String): String {
        val code = raw.uppercase().filter { it in ALPHABET }
        if (code.length != 8 && code.length != NEW_CODE_LENGTH) {
            throw PairingException("Enter a valid 8- or 12-character code.")
        }

        val result = try {
            withTimeoutOrNull(NET_TIMEOUT.milliseconds) {
                db.runTransaction { transaction ->
                    val ref = couple(code)
                    val snap = transaction.get(ref)
                    if (!snap.exists()) {
                        "missing"
                    } else {
                        val members = snap.get("members") as? List<*>
                        when {
                            members == null -> "invalid"
                            members.any { it !is String } -> "invalid"
                            uid in members -> "self"
                            members.size >= 2 -> "full"
                            members.size != 1 -> "invalid"
                            else -> {
                                val memberNames = (snap.get("memberNames") as? Map<*, *>)
                                    ?.entries
                                    ?.mapNotNull { (key, value) ->
                                        if (key is String && value is String) key to value else null
                                    }
                                    ?.toMap()
                                    .orEmpty()
                                    .toMutableMap()
                                memberNames[uid] = "Partner"
                                transaction.update(
                                    ref,
                                    mapOf(
                                        "members" to (members.filterIsInstance<String>() + uid),
                                        "memberNames" to memberNames,
                                        "deleteApprovals" to emptyMap<String, Boolean>(),
                                        "inviteOnly" to false
                                    )
                                )
                                "joined"
                            }
                        }
                    }
                }.await()
            } ?: throw PairingException(OFFLINE)
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                throw PairingException("That code isn't valid, or that couple is already paired.")
            }
            throw e
        }

        when (result) {
            "joined" -> return code
            "self" -> throw PairingException("That's your own code. Let your partner scan or enter it.")
            "full" -> throw PairingException("That couple is already paired.")
            else -> throw PairingException("That code isn't valid.")
        }
    }

    /** Links the current anonymous identity to an email/password sign-in method. */
    suspend fun linkRecoveryEmail(emailInput: String, password: String): String {
        val email = emailInput.trim()
        if (email.isBlank()) throw PairingException("Enter an email address.")
        if (password.length < 6) throw PairingException("Use a password with at least 6 characters.")

        val user = auth.currentUser ?: throw PairingException("Your account isn't ready. Please try again.")
        if (!user.isAnonymous && !user.email.isNullOrBlank()) return user.email!!

        withTimeoutOrNull(NET_TIMEOUT.milliseconds) {
            user.linkWithCredential(EmailAuthProvider.getCredential(email, password)).await()
        } ?: throw PairingException(OFFLINE)

        return auth.currentUser?.email ?: email
    }

    /** Signs into a previously linked account and restores its couple document. */
    suspend fun restoreCouple(emailInput: String, password: String): Pair<String, String> {
        val email = emailInput.trim()
        if (email.isBlank()) throw PairingException("Enter the email address linked to your account.")
        if (password.isBlank()) throw PairingException("Enter your password.")

        val user = withTimeoutOrNull(NET_TIMEOUT.milliseconds) {
            auth.signInWithEmailAndPassword(email, password).await().user
        } ?: throw PairingException(OFFLINE)
        val uid = user?.uid ?: throw PairingException("Couldn't sign in. Please try again.")

        val snapshot = withTimeoutOrNull(NET_TIMEOUT.milliseconds) {
            db.collection("couples")
                .whereArrayContains("members", uid)
                .limit(2)
                .get(Source.SERVER)
                .await()
        } ?: throw PairingException(OFFLINE)

        if (snapshot.isEmpty) {
            throw PairingException("No couple was found for this account. Check the email or contact your partner.")
        }
        if (snapshot.size() > 1) {
            throw PairingException("This account is linked to more than one couple. Please contact support.")
        }

        return snapshot.documents.single().id to (user.email ?: email)
    }

    /** Recovers the couple code using the Firebase identity already saved on this phone. */
    suspend fun recoverCurrentCouple(): String {
        val uid = auth.currentUser?.uid
            ?: throw PairingException("This phone's saved sign-in is unavailable. Use account recovery if you linked an email and password.")

        val snapshot = withTimeoutOrNull(NET_TIMEOUT.milliseconds) {
            db.collection("couples")
                .whereArrayContains("members", uid)
                .limit(2)
                .get(Source.SERVER)
                .await()
        } ?: throw PairingException(OFFLINE)

        if (snapshot.isEmpty) {
            throw PairingException("No shared space was found for this phone's saved account. Try account recovery if you previously linked an email and password.")
        }
        if (snapshot.size() > 1) {
            throw PairingException("More than one shared space was found for this account. Please contact support.")
        }

        return snapshot.documents.single().id
    }

    /** Cancels only this member's still-pending, one-person invitation. */
    suspend fun cancelPendingInvite(code: String): Boolean {
        val uid = auth.currentUser?.uid
            ?: throw PairingException("Sign in again before cancelling this invite.")
        return withTimeoutOrNull(NET_TIMEOUT.milliseconds) {
            db.runTransaction { transaction ->
                val ref = couple(code)
                val snap = transaction.get(ref)
                if (!snap.exists()) {
                    true
                } else {
                    val members = (snap.get("members") as? List<*>)
                        ?.filterIsInstance<String>().orEmpty()
                    val isPendingInvite = snap.getBoolean("inviteOnly") ?: true
                    when {
                        members.size == 1 && members.single() == uid && isPendingInvite -> {
                            transaction.delete(ref)
                            true
                        }
                        else -> false
                    }
                }
            }.await()
        } ?: throw PairingException("Connect to the internet to cancel this invite.")
    }

    /** Records this member's consent. A paired space is deleted only after both members approve. */
    suspend fun approveDataDeletion(code: String): Boolean {
        val uid = auth.currentUser?.uid ?: throw PairingException("Sign in again before requesting deletion.")
        return withTimeoutOrNull(NET_TIMEOUT.milliseconds) {
            db.runTransaction { transaction ->
                val ref = couple(code)
                val snap = transaction.get(ref)
                if (!snap.exists()) throw PairingException("This shared space no longer exists.")
                val members = (snap.get("members") as? List<*>)?.filterIsInstance<String>().orEmpty()
                if (uid !in members) throw PairingException("This account is no longer part of this shared space.")
                val approvals = (snap.get("deleteApprovals") as? Map<*, *>)
                    ?.entries?.mapNotNull { (key, value) ->
                        if (key is String && value is Boolean) key to value else null
                    }?.toMap()?.toMutableMap() ?: mutableMapOf()
                approvals[uid] = true
                transaction.update(ref, "deleteApprovals", approvals)
                members.isNotEmpty() && members.all { approvals[it] == true }
            }.await()
        } ?: throw PairingException("Connect to the internet to request deletion.")
    }

    /** Deletes all shared data after the approval map contains every member. */
    suspend fun unpair(code: String) {
        val done = withTimeoutOrNull((NET_TIMEOUT * 6).milliseconds) {
            val parent = couple(code).get(Source.SERVER).await()
            val memberIds = (parent.get("members") as? List<*>)?.filterIsInstance<String>().orEmpty()
            val approvals = (parent.get("deleteApprovals") as? Map<*, *>)
                ?.entries?.mapNotNull { (key, value) ->
                    if (key is String && value is Boolean) key to value else null
                }?.toMap().orEmpty()
            val currentUid = auth.currentUser?.uid
                ?: throw PairingException("Sign in again before deleting this shared space.")
            if (memberIds.isEmpty() || currentUid !in memberIds) {
                throw PairingException("This account is no longer part of that shared space.")
            }
            if (!memberIds.all { approvals[it] == true }) {
                throw PairingException("Both partners must approve permanent deletion first.")
            }

            val collections = listOf("items", "events", "notes", "memories", "memoryAlbumCovers", "activity", "daily", "livePhotoWidget", "livePhotoWidgetPhotos")
            for (name in collections) {
                val docs = couple(code).collection(name).get(Source.SERVER).await().documents
                docs.forEach { doc ->
                    if (name == "memories" || name == "livePhotoWidgetPhotos") {
                        doc.reference.collection("content").document("image").delete().await()
                        doc.reference.collection("content").document("thumbnail").delete().await()
                    }
                    if (name == "daily") {
                        for (sub in listOf("answers", "checkins")) {
                            memberIds.forEach { memberUid ->
                                doc.reference.collection(sub).document(memberUid).delete().await()
                            }
                        }
                    }
                }
                docs.chunked(400).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
            }

            memberIds.forEach { uid ->
                couple(code).collection("locations").document(uid).delete().await()
                couple(code).collection("devices").document(uid).delete().await()
            }
            couple(code).delete().await()
            true
        }
        if (done == null) throw PairingException("Go online to delete the shared space, then try again.")
    }

    fun observeCouple(code: String): Flow<CoupleState> = callbackFlow {
        val reg = couple(code).addSnapshotListener { s, e ->
            if (e != null) {
                val message = when (e.code) {
                    FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        "Firebase couldn't verify your shared space. Your pairing is still saved. Check your account and Firebase access, then retry."
                    FirebaseFirestoreException.Code.UNAVAILABLE,
                    FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                        "Couldn't reach your shared space. Check your internet connection, then retry."
                    else ->
                        "Your shared space couldn't be checked (${e.code}). Your pairing is still saved. Please retry."
                }
                trySend(CoupleState.Unavailable(message))
                close()
                return@addSnapshotListener
            }
            if (s == null) return@addSnapshotListener
            if (s.exists()) {
                val n = (s.get("members") as? List<*>)?.size ?: 1
                trySend(CoupleState.Active(
                    n,
                    s.getLong("anniversary") ?: DEFAULT_ANNIVERSARY,
                    s.getString("coupleName")?.takeIf { it.isNotBlank() } ?: "Us 💜",
                    (s.get("memberNames") as? Map<*, *>)
                        ?.entries
                        ?.mapNotNull { (key, value) ->
                            if (key is String && value is String) key to value else null
                        }
                        ?.toMap()
                        .orEmpty(),
                    (s.get("members") as? List<*>)?.filterIsInstance<String>().orEmpty(),
                    (s.get("deleteApprovals") as? Map<*, *>)
                        ?.entries
                        ?.mapNotNull { (key, value) -> if (key is String && value is Boolean) key to value else null }
                        ?.toMap()
                        .orEmpty(),
                    (s.get("memberGenders") as? Map<*, *>)
                        ?.entries
                        ?.mapNotNull { (key, value) ->
                            if (key is String && value is String && value in setOf("male", "female", "unspecified")) {
                                key to value
                            } else null
                        }
                        ?.toMap()
                        .orEmpty()
                ))
            } else if (!s.metadata.isFromCache) {
                trySend(CoupleState.Gone)
            }
        }
        awaitClose { reg.remove() }
    }

    fun observeItems(code: String): Flow<List<Item>> = callbackFlow {
        val reg = couple(code).collection("items").orderBy("createdAt")
            .addSnapshotListener { s, _ ->
                if (s != null) trySend(s.documents.map {
                    Item(
                        id = it.id,
                        type = it.getString("type") ?: "todo",
                        text = it.getString("text") ?: "",
                        done = it.getBoolean("done") ?: false
                    )
                })
            }
        awaitClose { reg.remove() }
    }

    // Firestore applies this writes locally immediately and queues them while offline.
    fun addItem(code: String, uid: String, type: String, text: String): com.google.android.gms.tasks.Task<Void> {
        val now = System.currentTimeMillis()
        val itemRef = couple(code).collection("items").document()
        val activityRef = couple(code).collection("activity").document()
        val batch = db.batch()
        batch.set(itemRef, mapOf(
            "type" to type, "text" to text, "done" to false,
            "createdAt" to now, "createdBy" to uid
        ))
        batch.set(activityRef, mapOf(
            "actorUid" to uid,
            "type" to if (type == "bucket") "bucket_created" else "todo_created",
            "title" to if (type == "bucket") "Added a dream" else "Added a to-do",
            "summary" to text.take(180),
            "targetCollection" to "items",
            "targetId" to itemRef.id,
            "createdAt" to now
        ))
        return batch.commit()
    }

    fun setDone(code: String, id: String, uid: String, done: Boolean): com.google.android.gms.tasks.Task<Void> {
        val batch = db.batch()
        batch.update(couple(code).collection("items").document(id), "done", done)
        if (done) {
            val activityRef = couple(code).collection("activity").document()
            batch.set(activityRef, mapOf(
                "actorUid" to uid,
                "type" to "todo_completed",
                "title" to "Completed a little task",
                "summary" to "A shared list item was checked off.",
                "targetCollection" to "items",
                "targetId" to id,
                "createdAt" to System.currentTimeMillis()
            ))
        }
        return batch.commit()
    }

    fun deleteItem(code: String, id: String) =
        couple(code).collection("items").document(id).delete()

    fun setAnniversary(code: String, epochDay: Long) =
        couple(code).update("anniversary", epochDay)

    fun setCoupleName(code: String, name: String) =
        couple(code).update("coupleName", name.trim())

    fun setMemberNickname(code: String, uid: String, nickname: String) =
        couple(code).update("memberNames.$uid", nickname.trim())

    fun setMemberGender(code: String, uid: String, gender: String): com.google.android.gms.tasks.Task<Void> {
        require(gender in setOf("male", "female", "unspecified")) { "Choose a valid gender setting." }
        return couple(code).update("memberGenders.$uid", gender)
    }
}
