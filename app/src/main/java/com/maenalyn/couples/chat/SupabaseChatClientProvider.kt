package com.maenalyn.couples.chat

import com.maenalyn.couples.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Supplies the existing Firebase ID token to Supabase.
 *
 * The first request for each Firebase UID force-refreshes its ID token so the
 * Admin-provisioned "role=authenticated" custom claim is present. Later requests
 * use Firebase's normal token cache/refresh behavior.
 */
internal object FirebaseSupabaseAccessTokenProvider {
    private val mutex = Mutex()
    private var preparedUid: String? = null

    suspend fun getToken(): String? = mutex.withLock {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            preparedUid = null
            return@withLock null
        }

        val forceRefresh = preparedUid != user.uid
        val tokenResult = user.getIdToken(forceRefresh).await()

        // Never hand Supabase a token from an account that changed mid-refresh.
        if (FirebaseAuth.getInstance().currentUser?.uid != user.uid) {
            preparedUid = null
            return@withLock null
        }

        val role = tokenResult.claims["role"] as? String
        if (role != "authenticated") {
            if (!forceRefresh) {
                val refreshed = user.getIdToken(true).await()
                if (FirebaseAuth.getInstance().currentUser?.uid != user.uid) {
                    preparedUid = null
                    return@withLock null
                }
                check(refreshed.claims["role"] == "authenticated") {
                    "This Firebase account is missing the required Supabase role claim. Verify the Admin SDK claim setup."
                }
                preparedUid = user.uid
                return@withLock refreshed.token
                    ?: error("Firebase did not return an ID token.")
            }

            preparedUid = null
            error(
                "This Firebase account is missing the required Supabase role claim. " +
                    "Verify the Admin SDK claim setup and refresh the Firebase ID token."
            )
        }

        preparedUid = user.uid
        tokenResult.token ?: error("Firebase did not return an ID token.")
    }
}

/**
 * One app-wide Supabase client. Supabase Auth is intentionally not installed:
 * Firebase remains the only identity provider and supplies the access token.
 */
internal object SupabaseChatClientProvider {
    private val lock = Any()

    @Volatile
    private var cachedClient: SupabaseClient? = null

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.startsWith("https://") &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank() &&
            !BuildConfig.SUPABASE_URL.contains("YOUR_PROJECT_REF") &&
            !BuildConfig.SUPABASE_PUBLISHABLE_KEY.contains("REPLACE_ME")

    fun get(): SupabaseClient {
        check(isConfigured) {
            "Supabase is not configured. Create the local supabase.properties file."
        }

        return cachedClient ?: synchronized(lock) {
            cachedClient ?: createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
                supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
            ) {
                accessToken = { FirebaseSupabaseAccessTokenProvider.getToken() }
                install(Postgrest)
                install(Realtime)
            }.also { cachedClient = it }
        }
    }
}
