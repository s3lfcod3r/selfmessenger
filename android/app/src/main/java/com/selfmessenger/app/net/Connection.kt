package com.selfmessenger.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.selfmessenger.app.Contact
import com.selfmessenger.app.Me
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * Bindet Signaling + WebRTC + Krypto zusammen. Ablauf wie im Web-Client:
 * Rendezvous aus Identitäten -> Kuppler -> WebRTC-Direktverbindung -> Sitzung aus Identitäts-ECDH.
 * (Double Ratchet folgt als eigene Slice.)
 */
class Connection(
    private val ctx: Context,
    private val me: Me,
    private val contact: Contact,
    private val signalingBaseUrl: String,
    private val onStatus: (String) -> Unit,
    private val onMessage: (fromMe: Boolean, text: String) -> Unit
) {
    private var signaling: Signaling? = null
    private var rtc: WebRtcClient? = null
    private var crypto: CryptoSession? = null
    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) = main.post(block)

    val egl: EglBase = EglBase.create()
    var onLocalVideo: ((VideoTrack) -> Unit)? = null
    var onRemoteVideo: ((VideoTrack) -> Unit)? = null
    // Medien: (fromMe, kind, name, mime, bytes)
    var onMedia: ((Boolean, String, String, String, ByteArray) -> Unit)? = null
    // Anruf-Ereignisse (WhatsApp-Stil)
    var onIncomingCall: ((video: Boolean) -> Unit)? = null   // eingehender Anruf -> Annehmen-Ansicht
    var onCallConnected: (() -> Unit)? = null                 // Gegenseite hat Medien -> verbunden
    var onCallEnded: (() -> Unit)? = null                     // Gegenseite hat aufgelegt -> Anruf schließen
    private var callActive = false
    private var callVideo = false

    fun startCall(video: Boolean) { callActive = true; callVideo = video; rtc?.startCall(video) }
    fun acceptCall() { callActive = true; rtc?.startCall(callVideo) }   // Mikro/Kamera passend zum eingehenden Anruf
    fun hangupCall() {
        try { rtc?.sendText(JSONObject().put("t", "bye").toString()) } catch (_: Exception) {}  // Gegenseite mit-beenden
        callActive = false; callVideo = false; rtc?.hangupCall()
    }
    fun setMicEnabled(on: Boolean) = rtc?.setMicEnabled(on)
    fun setCamEnabled(on: Boolean) = rtc?.setCamEnabled(on)
    private fun onRemoteTrack(kind: String) {
        if (kind == "video") callVideo = true
        if (!callActive) ui { onIncomingCall?.invoke(callVideo) } else ui { onCallConnected?.invoke() }
    }

    fun start() {
        val room = CryptoSession.rendezvous(me.pubB64, contact.pubB64)
        signaling = Signaling(signalingBaseUrl,
            onMessage = { handleSignal(it) },
            onOpen = { ui { onStatus("warte auf ${contact.name}…") } },
            onClosed = { }
        )
        ui { onStatus("verbinde mit Kuppler…") }
        signaling?.connect(room)
    }

    private fun handleSignal(m: JSONObject) {
        when (m.optString("type")) {
            "ready" -> {
                val initiator = m.getBoolean("initiator")
                val client = WebRtcClient(ctx, egl, polite = !initiator,
                    onLocalDesc = { desc -> signaling?.send(JSONObject().put("type", "desc").put("desc", desc)) },
                    onIce = { ice -> signaling?.send(JSONObject().put("type", "ice").put("cand", ice)) },
                    onOpen = { onOpen() },
                    onText = { txt -> onWireText(txt) }
                )
                client.onLocalVideo = { t -> ui { onLocalVideo?.invoke(t) } }
                client.onRemoteVideo = { t -> ui { onRemoteVideo?.invoke(t) } }
                client.onRemoteTrackKind = { kind -> onRemoteTrack(kind) }
                rtc = client
                client.start(initiator)
            }
            "desc" -> rtc?.onRemoteDesc(m.getJSONObject("desc"))
            "ice" -> rtc?.onRemoteIce(m.getJSONObject("cand"))
            "full" -> ui { onStatus("Raum voll") }
            "peer-left" -> ui { onStatus("${contact.name} hat getrennt") }
        }
    }

    private fun onOpen() {
        crypto = CryptoSession.derive(me.privateKey, contact.pubB64)
        val sn = CryptoSession.safetyNumber(me.pubB64, contact.pubB64)
        ui { onStatus("✓ Ende-zu-Ende · $sn") }
    }

    fun sendText(text: String) {
        val c = crypto
        if (c != null) {                                  // live: über die Direktverbindung
            val (iv, ct) = c.encrypt(text.toByteArray(Charsets.UTF_8))
            rtc?.sendText(JSONObject().put("t", "msg").put("iv", iv).put("ct", ct).toString())
            ui { onMessage(true, text) }
        } else if (signaling != null) {                   // Partner offline: verschlüsselt in den Briefkasten
            if (!com.selfmessenger.app.Settings.offlineMailboxFor(ctx, contact.pubB64)) {
                ui { onMessage(true, "$text  ✗ (nicht zugestellt – Person offline, Zwischenlagern aus)") }
                return
            }
            val s = CryptoSession.derive(me.privateKey, contact.pubB64)
            val (iv, ct) = s.encrypt(text.toByteArray(Charsets.UTF_8))
            val blob = JSONObject().put("iv", iv).put("ct", ct)
            signaling?.send(JSONObject().put("type", "store")
                .put("mailbox", CryptoSession.mailboxId(contact.pubB64)).put("blob", blob))
            ui { onMessage(true, "$text  ✉ (offline hinterlegt)") }
        }
    }

    /** Bild/Datei/Sprachnachricht senden: verschlüsselt, in Häppchen (wie Web-Client). */
    fun sendMedia(kind: String, name: String, mime: String, bytes: ByteArray) {
        val c = crypto ?: return
        val id = java.util.UUID.randomUUID().toString()
        val total = maxOf(1, (bytes.size + CHUNK - 1) / CHUNK)
        rtc?.sendText(JSONObject().put("t", "meta").put("id", id).put("kind", kind)
            .put("name", name).put("mime", mime).put("size", bytes.size).put("total", total).toString())
        var i = 0
        while (i < total) {
            val slice = bytes.copyOfRange(i * CHUNK, minOf((i + 1) * CHUNK, bytes.size))
            val (iv, ct) = c.encrypt(slice)
            rtc?.sendText(JSONObject().put("t", "chunk").put("id", id).put("i", i).put("iv", iv).put("ct", ct).toString())
            i++
        }
        ui { onMedia?.invoke(true, kind, name, mime, bytes) }
    }

    private val incoming = HashMap<String, MediaAcc>()

    private fun onWireText(json: String) {
        val m = JSONObject(json)
        when (m.optString("t")) {
            "bye" -> { callActive = false; callVideo = false; rtc?.hangupCall(); ui { onCallEnded?.invoke() } }
            "msg" -> {
                val c = crypto ?: return
                val plain = String(c.decrypt(m.getString("iv"), m.getString("ct")), Charsets.UTF_8)
                ui { onMessage(false, plain) }
            }
            "meta" -> incoming[m.getString("id")] =
                MediaAcc(m.getString("kind"), m.getString("name"), m.getString("mime"), m.getInt("total"))
            "chunk" -> {
                val acc = incoming[m.getString("id")] ?: return
                val c = crypto ?: return
                acc.parts[m.getInt("i")] = c.decrypt(m.getString("iv"), m.getString("ct"))
                acc.got++
                if (acc.got >= acc.total) {
                    incoming.remove(m.getString("id"))
                    val bytes = acc.assemble()
                    ui { onMedia?.invoke(false, acc.kind, acc.name, acc.mime, bytes) }
                }
            }
        }
    }

    fun close() { rtc?.close(); signaling?.close(); egl.release() }

    private companion object { const val CHUNK = 16 * 1024 }
}

/** Sammelt eintreffende Medien-Häppchen bis vollständig. */
private class MediaAcc(val kind: String, val name: String, val mime: String, val total: Int) {
    val parts = arrayOfNulls<ByteArray>(total)
    var got = 0
    fun assemble(): ByteArray {
        val size = parts.sumOf { it?.size ?: 0 }
        val out = ByteArray(size); var o = 0
        for (p in parts) { if (p != null) { System.arraycopy(p, 0, out, o, p.size); o += p.size } }
        return out
    }
}
