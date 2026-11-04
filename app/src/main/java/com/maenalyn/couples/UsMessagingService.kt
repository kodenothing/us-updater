package com.maenalyn.couples

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class UsMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val code = getSharedPreferences("us", MODE_PRIVATE).getString("code", null) ?: return
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("couples").document(code).collection("devices").document(uid)
            .set(mapOf(
                "uid" to uid,
                "token" to token,
                "updatedAt" to System.currentTimeMillis(),
                "notificationsEnabled" to getSharedPreferences("us", MODE_PRIVATE).getBoolean("partnerNotifications", true)
            ))
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.data["title"] ?: "A little update from your person"
        val body = message.data["body"] ?: "Open Us to see what's new."
        val key = message.data["notificationId"]
            ?: (message.messageId ?: System.currentTimeMillis().toString())
        val category = message.data["category"] ?: "other"
        UsNotifications.showPartnerUpdate(this, title, body, key, category)
    }
}
