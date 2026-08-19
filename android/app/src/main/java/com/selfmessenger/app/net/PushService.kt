package com.selfmessenger.app.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
        notifyMessage(this, title, body, msg.data["type"] == "call")
    }

    companion object {
        const val CHANNEL = "selfmessenger_messages"
        const val CHANNEL_CALL = "selfmessenger_calls"
        fun notifyMessage(ctx: Context, title: String, body: String, isCall: Boolean = false) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = if (isCall) CHANNEL_CALL else CHANNEL
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nachrichten", NotificationManager.IMPORTANCE_HIGH))
                nm.createNotificationChannel(NotificationChannel(CHANNEL_CALL, "Anrufe", NotificationManager.IMPORTANCE_HIGH))
            }
            // Tippen öffnet die App (und schließt die Benachrichtigung dank setAutoCancel)
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            } ?: Intent()
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(ctx, if (isCall) 1 else 0, launch, piFlags)
            val n = NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(if (isCall) android.R.drawable.sym_action_call else android.R.drawable.stat_notify_chat)
                .setContentTitle(if (isCall) "Eingehender Anruf" else title)
                .setContentText(if (isCall) "$body — tippen zum Annehmen" else body)
                .setContentIntent(pi)
                .setCategory(if (isCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
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
