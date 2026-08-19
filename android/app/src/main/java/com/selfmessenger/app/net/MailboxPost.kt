package com.selfmessenger.app.net

import com.selfmessenger.app.Me
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Direkter Briefkasten-Einwurf per HTTP (POST /send) — Offline-Versand ganz ohne P2P/Room.
 * Alle Aufrufe blockieren (Netz) → immer aus einem Hintergrund-Thread aufrufen.
 */
object MailboxPost {
    private val http = OkHttpClient()
    private val JSON = "application/json".toMediaType()

    private fun sendUrl(baseUrl: String): String =
        baseUrl.replaceFirst(Regex("^ws"), "http").replace(Regex("/ws(\\?.*)?$"), "/send")

    // push: true (Standard) | false (Chunks/Acks) | "call" (Anruf-Benachrichtigung)
    fun post(baseUrl: String, mailbox: String, iv: String, ct: String, push: Any = true) {
        val body = JSONObject().put("mailbox", mailbox)
            .put("blob", JSONObject().put("iv", iv).put("ct", ct)).put("push", push).toString()
        val req = Request.Builder().url(sendUrl(baseUrl)).post(body.toRequestBody(JSON)).build()
        try { http.newCall(req).execute().use {} } catch (_: Exception) {}
    }

    /** Verschlüsselten Umschlag (Nachricht/Medium/Ack/Anruf) in den Briefkasten von [pub] legen. */
    fun postEnvelope(me: Me, baseUrl: String, pub: String, obj: JSONObject, push: Any = true) {
        val s = CryptoSession.derive(me.privateKey, pub)
        val (iv, ct) = s.encrypt(obj.toString().toByteArray(Charsets.UTF_8))
        post(baseUrl, CryptoSession.mailboxId(pub), iv, ct, push)
    }

    // Ack weckt den Empfänger nicht (push=false)
    fun sendAck(me: Me, baseUrl: String, pub: String, id: String, s: String) =
        postEnvelope(me, baseUrl, pub, JSONObject().put("t", "a").put("id", id).put("s", s), push = false)

    /** Anruf-Einladung (weckt den Empfänger mit Anruf-Benachrichtigung). */
    fun sendCallInviteAsync(me: Me, baseUrl: String, pub: String, video: Boolean) {
        Thread {
            postEnvelope(me, baseUrl, pub,
                JSONObject().put("t", "call").put("video", video).put("ts", System.currentTimeMillis()), push = "call")
        }.start()
    }

    /** Bequemer Wrapper: Ack im Hintergrund senden. */
    fun sendAckAsync(me: Me, baseUrl: String, pub: String, id: String, s: String) {
        Thread { sendAck(me, baseUrl, pub, id, s) }.start()
    }
}
