package com.selfmessenger.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessaging
import com.selfmessenger.app.AckBus
import com.selfmessenger.app.AppState
import com.selfmessenger.app.Contact
import com.selfmessenger.app.Contacts
import com.selfmessenger.app.IncomingCall
import com.selfmessenger.app.IncomingCallBus
import com.selfmessenger.app.MediaFiles
import com.selfmessenger.app.Me
import com.selfmessenger.app.MessageStore
import com.selfmessenger.app.OfflineMedia
import com.selfmessenger.app.OfflineMediaBus
import com.selfmessenger.app.PendingRead
import com.selfmessenger.app.Settings
import com.selfmessenger.app.StoredMsg
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

    private val mediaAcc = HashMap<String, MediaOff>()

    /** Umschlag: Nachricht ({t:m}), Bestätigung ({t:a}) oder Medium ({t:mm} Meta / {t:mc} Häppchen). */
    private fun handleEnvelope(c: Contact, raw: String) {
        val env = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("t", "m").put("body", raw) }
        when (env.optString("t")) {
            "a" -> {                                       // Bestätigung für eine von MIR gesendete Nachricht
                val id = env.optString("id"); val s = env.optString("s")
                if (id.isNotEmpty()) AckBus.emit(c.pubB64, id, if (s == "r") "read" else "delivered")
            }
            "call" -> {                                    // Anruf-Einladung: nur wenn frisch (< 60 s) → App öffnet den Chat
                val ts = env.optLong("ts", 0L)
                if (System.currentTimeMillis() - ts in 0..60_000)
                    IncomingCallBus.emit(IncomingCall(c.pubB64, c.name, env.optBoolean("video", false)))
            }
            "mm" -> mediaAcc[env.getString("id")] =
                MediaOff(env.getString("kind"), env.getString("name"), env.getString("mime"), env.getInt("total"))
            "mc" -> {
                val id = env.getString("id"); val acc = mediaAcc[id] ?: return
                acc.parts[env.getInt("i")] = android.util.Base64.decode(env.getString("body"), android.util.Base64.NO_WRAP)
                acc.got++
                if (acc.got >= acc.total) { mediaAcc.remove(id); onOfflineMedia(c, id, acc) }
            }
            else -> {                                      // echte Textnachricht
                val id = env.optString("id", "")
                val body = if (env.has("body")) env.getString("body") else raw
                main.post { onOfflineMessage(c.name, body) }
                ackReceived(c.pubB64, id)
            }
        }
    }

    private fun onOfflineMedia(c: Contact, id: String, acc: MediaOff) {
        val bytes = acc.assemble()
        if (Settings.saveHistoryFor(ctx, c.pubB64)) {      // speichern, damit es beim Öffnen da ist
            val fid = MediaFiles.save(ctx, bytes)
            MessageStore.append(ctx, c.pubB64, StoredMsg(false, null, mediaLabel(acc.kind, acc.name), nowT(), null, null, fid, acc.kind, acc.name, acc.mime))
        }
        OfflineMediaBus.emit(OfflineMedia(c.pubB64, acc.kind, acc.name, acc.mime, bytes))   // offener Chat: live
        ackReceived(c.pubB64, id)
    }

    private fun ackReceived(pub: String, id: String) {
        if (id.isEmpty()) return
        MailboxPost.sendAckAsync(me, baseUrl, pub, id, "d")           // zugestellt
        if (AppState.openChatPub == pub) MailboxPost.sendAckAsync(me, baseUrl, pub, id, "r")  // gelesen (Chat offen)
        else PendingRead.add(pub, id)
    }

    private fun mediaLabel(kind: String, name: String) = when (kind) { "image" -> "🖼 Bild"; "voice" -> "🎤 Sprachnachricht"; else -> "📎 $name" }
    private fun nowT() = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

    fun stop() { ws?.close(1000, null); ws = null }

    /** Sammelt offline empfangene Medien-Häppchen bis vollständig. */
    private class MediaOff(val kind: String, val name: String, val mime: String, val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var got = 0
        fun assemble(): ByteArray {
            val size = parts.sumOf { it?.size ?: 0 }
            val out = ByteArray(size); var o = 0
            for (p in parts) { if (p != null) { System.arraycopy(p, 0, out, o, p.size); o += p.size } }
            return out
        }
    }
}
