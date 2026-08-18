package com.selfmessenger.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessaging
import com.selfmessenger.app.AckBus
import com.selfmessenger.app.AppState
import com.selfmessenger.app.Contact
import com.selfmessenger.app.Contacts
import com.selfmessenger.app.Me
import com.selfmessenger.app.PendingRead
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Briefkasten-Client: meldet den eigenen Briefkasten beim Kuppler an und empfängt
 * offline hinterlegte, verschlüsselte Blobs. Der richtige Absender wird per
 * Probe-Entschlüsselung gegen alle Kontakte gefunden (kein Absender im Blob = privat).
 */
class MailboxClient(
    private val ctx: Context,
    private val me: Me,
    private val baseUrl: String,
    private val onOfflineMessage: (fromName: String, text: String) -> Unit
) {
    private val client = OkHttpClient()
    private var ws: WebSocket? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        val mbx = CryptoSession.mailboxId(me.pubB64)
        val req = Request.Builder().url("$baseUrl?mbx=$mbx").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = registerPush(webSocket)
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        })
    }

    /** FCM-Token holen und beim eigenen Briefkasten anmelden → Push, wenn App zu ist. */
    private fun registerPush(webSocket: WebSocket) {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (token.isNullOrBlank()) return@addOnSuccessListener
                PushToken.set(ctx, token)
                try { webSocket.send(JSONObject().put("type", "pushtoken").put("token", token).toString()) } catch (_: Exception) {}
            }
        } catch (_: Exception) { /* Firebase nicht verfügbar -> ohne Push weiter */ }
    }

    private fun handle(text: String) {
        val m = JSONObject(text)
        if (m.optString("type") != "mail") return
        val items = m.getJSONArray("items")
        for (i in 0 until items.length()) {
            val blob = items.getJSONObject(i)
            for (c in Contacts.all(ctx)) {                 // Probe-Entschlüsselung: nur der echte Absender passt
                val raw = try {
                    val session = CryptoSession.derive(me.privateKey, c.pubB64)
                    String(session.decrypt(blob.getString("iv"), blob.getString("ct")), Charsets.UTF_8)
                } catch (_: Exception) { continue }
                handleEnvelope(c, raw)
                break
            }
        }
    }

    /** Umschlag: entweder echte Nachricht ({t:"m",id,body}) oder Status-Bestätigung ({t:"a",id,s}). */
    private fun handleEnvelope(c: Contact, raw: String) {
        val env = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("t", "m").put("body", raw) }
        if (env.optString("t") == "a") {                   // Bestätigung für eine von MIR gesendete Nachricht
            val id = env.optString("id"); val s = env.optString("s")
            if (id.isNotEmpty()) AckBus.emit(c.pubB64, id, if (s == "r") "read" else "delivered")
            return
        }
        // echte Nachricht
        val id = env.optString("id", "")
        val body = if (env.has("body")) env.getString("body") else raw
        main.post { onOfflineMessage(c.name, body) }
        if (id.isNotEmpty()) {                             // zugestellt bestätigen; gelesen, wenn dieser Chat offen ist
            MailboxPost.sendAckAsync(me, baseUrl, c.pubB64, id, "d")
            if (AppState.openChatPub == c.pubB64) MailboxPost.sendAckAsync(me, baseUrl, c.pubB64, id, "r")
            else PendingRead.add(c.pubB64, id)
        }
    }

    fun stop() { ws?.close(1000, null); ws = null }
}
