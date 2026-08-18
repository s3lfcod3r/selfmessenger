package com.selfmessenger.app.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Empfängt FCM-Push, wenn die App geschlossen/im Hintergrund ist, und zeigt eine
 * Benachrichtigung. Der Push trägt KEINEN Inhalt (nur „neue Nachricht") — der eigentliche,
 * verschlüsselte Text wird erst geladen, wenn die App den Briefkasten abruft.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { PushToken.set(this, token) }

    override fun onMessageReceived(msg: RemoteMessage) {
        val title = msg.data["title"] ?: "SelfMessenger"
        val body = msg.data["body"] ?: "Neue Nachricht"
        notifyMessage(this, title, body)
    }

    companion object {
        const val CHANNEL = "selfmessenger_messages"
        fun notifyMessage(ctx: Context, title: String, body: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nachrichten", NotificationManager.IMPORTANCE_HIGH))
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title).setContentText(body)
                .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
            nm.notify(System.currentTimeMillis().toInt(), n)
        }
    }
}

/** Merkt sich das aktuelle FCM-Token, damit der Briefkasten-Client es beim Kuppler anmelden kann. */
object PushToken {
    private fun p(ctx: Context) = ctx.getSharedPreferences("sm_push", Context.MODE_PRIVATE)
    fun set(ctx: Context, t: String) = p(ctx).edit().putString("token", t).apply()
    fun get(ctx: Context): String? = p(ctx).getString("token", null)
}
