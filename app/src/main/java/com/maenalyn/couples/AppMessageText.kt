package com.maenalyn.couples

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/**
 * Keeps low-level transport and platform exceptions out of user-facing banners.
 * Known product messages are preserved; technical network failures get one
 * calm, actionable sentence instead of host names or exception internals.
 */
internal object AppMessageText {
    private val connectivityPhrases = listOf(
        "unable to resolve host",
        "unknownhostexception",
        "no address associated with hostname",
        "failed to connect",
        "connection refused",
        "connection reset",
        "network is unreachable",
        "sockettimeoutexception",
        "timed out",
        "timeout while",
        "http request error",
        "unavailable:"
    )

    private val technicalPhrases = listOf(
        "java.net.",
        "io.ktor.",
        "io.github.jan.supabase",
        "retrofit2.",
        "android.database.",
        "exception:",
        "stacktrace"
    )

    fun isConnectivityFailure(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth++ < 8) {
            if (
                current is UnknownHostException ||
                current is ConnectException ||
                current is SocketTimeoutException ||
                current is NoRouteToHostException
            ) {
                return true
            }

            val message = current.message?.lowercase(Locale.ROOT).orEmpty()
            if (connectivityPhrases.any(message::contains)) return true
            current = current.cause
        }
        return false
    }

    fun forDisplay(rawMessage: String?, fallback: String = "Something went wrong. Please try again."): String {
        val message = rawMessage?.trim().orEmpty()
        if (message.isBlank()) return fallback

        val normalized = message.lowercase(Locale.ROOT)
        if (connectivityPhrases.any(normalized::contains)) {
            return "You're offline or the connection is interrupted. Us will reconnect when your internet is available."
        }
        if (technicalPhrases.any(normalized::contains)) return fallback

        // Exception messages sometimes append host names or diagnostics after
        // the first line. Only display a short, readable first line.
        return message.lineSequence().firstOrNull()?.take(220)?.ifBlank { fallback } ?: fallback
    }

    fun forDisplay(error: Throwable?, fallback: String): String =
        if (isConnectivityFailure(error)) {
            "You're offline or the connection is interrupted. Us will reconnect when your internet is available."
        } else {
            forDisplay(error?.message, fallback)
        }
}
