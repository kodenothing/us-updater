package com.maenalyn.couples.chat

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class ChatRoomDiagnosticRow(
    val id: String,
    @SerialName("firebase_couple_code")
    val firebaseCoupleCode: String
)

/**
 * Debug-only, read-only verification of Firebase third-party auth plus chat-room RLS.
 * It deliberately does not log the Firebase UID, couple code, JWT, or publishable key.
 */
internal object SupabaseChatDiagnostics {
    private const val TAG = "UsChatBackend"

    suspend fun verifyPrivateRoomAccess(coupleCode: String) {
        if (!SupabaseChatClientProvider.isConfigured) {
            Log.i(TAG, "SKIP: local Supabase configuration is not present.")
            return
        }

        val initialUid = FirebaseAuth.getInstance().currentUser?.uid
        if (initialUid.isNullOrBlank()) {
            Log.w(TAG, "FAIL: no signed-in Firebase account is available.")
            return
        }

        try {
            // Validate/refresh the current Firebase token before making the protected read.
            if (FirebaseSupabaseAccessTokenProvider.getToken().isNullOrBlank()) {
                Log.w(TAG, "FAIL: Firebase did not provide an ID token.")
                return
            }

            val rooms = SupabaseChatClientProvider.get()
                .from("chat_rooms")
                .select {
                    filter {
                        eq("firebase_couple_code", coupleCode)
                    }
                }
                .decodeList<ChatRoomDiagnosticRow>()

            if (FirebaseAuth.getInstance().currentUser?.uid != initialUid) {
                Log.i(TAG, "SKIP: Firebase account changed during the diagnostic.")
                return
            }

            when {
                rooms.size == 1 && rooms.single().firebaseCoupleCode == coupleCode ->
                    Log.i(TAG, "PASS: current Firebase account can read its private Us Chat room through RLS.")

                rooms.isEmpty() ->
                    Log.w(TAG, "FAIL: Supabase returned no matching room. Check the registered roster and RLS policies.")

                else ->
                    Log.w(TAG, "FAIL: expected one visible room but received ${rooms.size}.")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(
                TAG,
                "FAIL: protected Supabase room read failed (${error.javaClass.simpleName}): ${error.message.orEmpty()}"
            )
        }
    }
}
