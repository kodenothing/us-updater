package com.maenalyn.couples

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DailyUsRepo {
    private val db get() = FirebaseFirestore.getInstance()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    val prompts = listOf(
        "What made you feel loved today?",
        "What is one little thing you appreciate about us?",
        "What moment with me would you happily relive?",
        "What is something I did recently that made you smile?",
        "What is one thing you want us to try together?",
        "When do you feel closest to me?",
        "What is one small way I can make tomorrow better for you?",
        "What are you looking forward to doing together?",
        "What is a memory of us that always makes you smile?",
        "What do you think we do really well as a team?",
        "What song reminds you of us, and why?",
        "What is one thing you would like more of in our relationship?",
        "What ordinary moment with me feels special to you?",
        "What is something new you learned about me?",
        "What is one dream you want us to work toward?",
        "What makes you feel safe and understood?",
        "What is your favorite way for us to spend a quiet day?",
        "What is one thing you are proud of us for?",
        "What little tradition should we start together?",
        "What is one thing you want to thank me for?",
        "What adventure would you choose for our next date?",
        "What do you love about the way we communicate?",
        "What is one thing you hope never changes about us?",
        "What helps you feel supported when life is stressful?",
        "What is one detail about me you find lovely?",
        "What would your perfect evening together look like?",
        "What is one goal we could accomplish as a team?",
        "When did you last feel especially proud of me?",
        "What is something you want us to celebrate soon?",
        "What is one thing we should make time for this week?",
        "What is your favorite photo of us, and what happened that day?",
        "What is one way we have grown together?",
        "What is something you would love to teach me?",
        "What makes a difficult day easier when we face it together?",
        "What is one thing you admire about how I handle life?",
        "What is a place you want to visit with me?",
        "What is one thing you want to remember about this season of life?",
        "What does feeling cared for look like to you?",
        "What is one simple date we can plan soon?",
        "What is one promise we can make to each other this week?"
    )

    val moods = listOf("Happy", "Loved", "Calm", "Tired", "Stressed", "Low")

    fun dateKey(date: LocalDate): String = date.format(dateFormatter)

    private fun promptIndex(dateKey: String): Int {
        val epochDay = LocalDate.parse(dateKey, dateFormatter).toEpochDay()
        return Math.floorMod(epochDay, prompts.size.toLong()).toInt()
    }

    private fun dayRef(code: String, dateKey: String) =
        db.collection("couples").document(code).collection("daily").document(dateKey)

    suspend fun ensureDay(code: String, dateKey: String) {
        val ref = dayRef(code, dateKey)
        db.runTransaction { transaction ->
            val current = transaction.get(ref)
            if (!current.exists()) {
                val index = promptIndex(dateKey)
                transaction.set(ref, mapOf(
                    "dateKey" to dateKey,
                    "question" to prompts[index],
                    "questionIndex" to index,
                    "createdAt" to System.currentTimeMillis()
                ))
            }
        }.await()
    }

    fun observeDay(
        code: String,
        dateKey: String,
        currentUid: String,
        partnerUid: String?
    ): Flow<DailyUsState> = callbackFlow {
        val root = dayRef(code, dateKey)
        val ownAnswerRef = root.collection("answers").document(currentUid)
        val partnerAnswerRef = partnerUid?.let { root.collection("answers").document(it) }
        val ownCheckinRef = root.collection("checkins").document(currentUid)
        val partnerCheckinRef = partnerUid?.let { root.collection("checkins").document(it) }
        val lock = Any()

        var question = prompts[promptIndex(dateKey)]
        var myAnswer: String? = null
        var myAnswerCreatedAt: Long? = null
        var partnerAnswer: String? = null
        var myMood: String? = null
        var myCheckinNote = ""
        var partnerMood: String? = null
        var partnerCheckinNote = ""
        var partnerAnswerRegistration: ListenerRegistration? = null
        val registrations = mutableListOf<ListenerRegistration>()

        fun publish() {
            val state = synchronized(lock) {
                DailyUsState(
                    dateKey = dateKey,
                    question = question,
                    myAnswer = myAnswer.orEmpty(),
                    myAnswerCreatedAt = myAnswerCreatedAt,
                    partnerAnswer = partnerAnswer,
                    myMood = myMood,
                    myCheckinNote = myCheckinNote,
                    partnerMood = partnerMood,
                    partnerCheckinNote = partnerCheckinNote
                )
            }
            trySend(state)
        }

        registrations += root.addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                synchronized(lock) { question = snapshot.getString("question")?.takeIf { it.isNotBlank() } ?: question }
                publish()
            }
        }

        registrations += ownAnswerRef.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, _ ->
            val ownValue = snapshot?.getString("answer")?.takeIf { it.isNotBlank() }
            synchronized(lock) {
                myAnswer = ownValue
                myAnswerCreatedAt = snapshot?.getLong("createdAt")
            }
            if (ownValue != null && snapshot?.metadata?.hasPendingWrites() != true && partnerAnswerRef != null && partnerAnswerRegistration == null) {
                partnerAnswerRegistration = partnerAnswerRef.addSnapshotListener { partnerSnapshot, _ ->
                    val answer = partnerSnapshot?.getString("answer")?.takeIf { it.isNotBlank() }
                    synchronized(lock) { partnerAnswer = answer }
                    publish()
                }
            }
            if (ownValue == null) {
                partnerAnswerRegistration?.remove()
                partnerAnswerRegistration = null
                synchronized(lock) { partnerAnswer = null }
            }
            publish()
        }

        registrations += ownCheckinRef.addSnapshotListener { snapshot, _ ->
            synchronized(lock) {
                myMood = snapshot?.getString("mood")
                myCheckinNote = snapshot?.getString("note") ?: ""
            }
            publish()
        }

        if (partnerCheckinRef != null) {
            registrations += partnerCheckinRef.addSnapshotListener { snapshot, _ ->
                synchronized(lock) {
                    partnerMood = snapshot?.getString("mood")
                    partnerCheckinNote = snapshot?.getString("note") ?: ""
                }
                publish()
            }
        }

        publish()
        awaitClose {
            registrations.forEach { it.remove() }
            partnerAnswerRegistration?.remove()
        }
    }

    fun saveAnswer(
        code: String,
        uid: String,
        dateKey: String,
        answer: String,
        originalCreatedAt: Long?
    ): Task<Void> {
        val now = System.currentTimeMillis()
        val batch = db.batch()
        val root = dayRef(code, dateKey)
        batch.set(root.collection("answers").document(uid), mapOf(
            "uid" to uid,
            "answer" to answer.trim(),
            "createdAt" to (originalCreatedAt ?: now),
            "updatedAt" to now
        ))
        val activity = db.collection("couples").document(code).collection("activity").document()
        batch.set(activity, mapOf(
            "actorUid" to uid,
            "type" to "daily_answered",
            "title" to "Answered today's prompt",
            "summary" to "Submit your own answer to reveal both responses.",
            "targetCollection" to "daily",
            "targetId" to dateKey,
            "createdAt" to now
        ))
        return batch.commit()
    }

    fun saveCheckin(code: String, uid: String, dateKey: String, mood: String, note: String): Task<Void> {
        val now = System.currentTimeMillis()
        val batch = db.batch()
        val root = dayRef(code, dateKey)
        batch.set(root.collection("checkins").document(uid), mapOf(
            "uid" to uid,
            "mood" to mood,
            "note" to note.trim(),
            "updatedAt" to now
        ))
        val activity = db.collection("couples").document(code).collection("activity").document()
        batch.set(activity, mapOf(
            "actorUid" to uid,
            "type" to "daily_checkin",
            "title" to "Shared a daily check-in",
            "summary" to "A mood check-in was added.",
            "targetCollection" to "daily",
            "targetId" to dateKey,
            "createdAt" to now
        ))
        return batch.commit()
    }
}

data class DailyUsState(
    val dateKey: String = "",
    val question: String = "",
    val myAnswer: String = "",
    val myAnswerCreatedAt: Long? = null,
    val partnerAnswer: String? = null,
    val myMood: String? = null,
    val myCheckinNote: String = "",
    val partnerMood: String? = null,
    val partnerCheckinNote: String = ""
) {
    val hasAnswered: Boolean get() = myAnswer.isNotBlank()
    val bothAnswered: Boolean get() = hasAnswered && !partnerAnswer.isNullOrBlank()
    val bothCheckedIn: Boolean get() = myMood != null && partnerMood != null
}
