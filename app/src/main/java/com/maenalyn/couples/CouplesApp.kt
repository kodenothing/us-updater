package com.maenalyn.couples

import android.app.Application
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings

class CouplesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCheckSetup.install()
        // Keep a bounded offline cache; photo originals and thumbnails are also
        // cached separately in app-private files when the user opens them.
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(
                PersistentCacheSettings.newBuilder()
                    .setSizeBytes(100L * 1024L * 1024L)
                    .build()
            )
            .build()
        FirebaseFirestore.getInstance().firestoreSettings = settings
    }
}
