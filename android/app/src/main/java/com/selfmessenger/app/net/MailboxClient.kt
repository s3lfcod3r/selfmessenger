package com.selfmessenger.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.selfmessenger.app.Contacts
import com.selfmessenger.app.Me
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
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        })
    }

    private fun handle(text: String) {
        val m = JSONObject(text)
        if (m.optString("type") != "mail") return
        val items = m.getJSONArray("items")
        for (i in 0 until items.length()) {
            val blob = items.getJSONObject(i)
            for (c in Contacts.all(ctx)) {                 // Probe-Entschlüsselung
                try {
                    val session = CryptoSession.derive(me.privateKey, c.pubB64)
                    val plain = String(session.decrypt(blob.getString("iv"), blob.getString("ct")), Charsets.UTF_8)
                    main.post { onOfflineMessage(c.name, plain) }
                    break
                } catch (_: Exception) { /* falscher Kontakt -> weiter */ }
            }
        }
    }

    fun stop() { ws?.close(1000, null); ws = null }
}
