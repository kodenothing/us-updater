package com.maenalyn.couples

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await

class LocationShareExpirationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("us", Context.MODE_PRIVATE)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt <= 0L || System.currentTimeMillis() < expiresAt) return Result.success()
        if (!prefs.getBoolean(KEY_SHARE_ENABLED, false)) return Result.success()

        val code = prefs.getString("code", null)
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        prefs.edit()
            .putBoolean(KEY_SHARE_ENABLED, false)
            .putBoolean(KEY_BACKGROUND_ENABLED, false)
            .putLong(KEY_EXPIRES_AT, 0L)
            .apply()

        LocationSharingService.stop(applicationContext, disableSharing = true)
        if (!code.isNullOrBlank() && !uid.isNullOrBlank()) {
            runCatching { LocationRepo.remove(code, uid).await() }
        }
        applicationContext.sendBroadcast(
            Intent(LocationSharingService.ACTION_STATUS_CHANGED).setPackage(applicationContext.packageName)
        )
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "us-location-share-expiration"
        private const val KEY_EXPIRES_AT = "shareLocationExpiresAtMillis"
        private const val KEY_SHARE_ENABLED = "shareLiveLocation"
        private const val KEY_BACKGROUND_ENABLED = "keepLocationSharingInBackground"

        fun schedule(context: Context, expiresAtMillis: Long) {
            val delay = (expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<LocationShareExpirationWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
