package com.selfmessenger.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.selfmessenger.app.Contact
import com.selfmessenger.app.Me
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.PeerConnection
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
    private val onMessage: (fromMe: Boolean, text: String, id: String) -> Unit
) {
    // Status-Haken für MEINE gesendeten Nachrichten (id -> "sent"/"delivered"/"read"/"fail")
    var onSentStatus: ((id: String, status: String) -> Unit)? = null
    private var signaling: Signaling? = null
    private var rtc: WebRtcClient? = null
    private var crypto: CryptoSession? = null
    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) = main.post(block)

    val egl: EglBase = EglBase.create()
    var onLocalVideo: ((VideoTrack) -> Unit)? = null
    var onRemoteVideo: ((VideoTrack) -> Unit)? = null
    // Medien: (fromMe, kind, name, mime, bytes, id)
    var onMedia: ((Boolean, String, String, String, ByteArray, String) -> Unit)? = null
    // Anruf-Ereignisse (WhatsApp-Stil)
    var onIncomingCall: ((video: Boolean) -> Unit)? = null   // eingehender Anruf -> Annehmen-Ansicht
    var onCallConnected: (() -> Unit)? = null                 // Gegenseite hat Medien -> verbunden
    var onCallEnded: (() -> Unit)? = null                     // Gegenseite hat aufgelegt -> Anruf schließen
    private var callActive = false
    private var callVideo = false

    fun startCall(video: Boolean) {
        callActive = true; callVideo = video; rtc?.startCall(video)
        MailboxPost.sendCallInviteAsync(me, signalingBaseUrl, contact.pubB64, video)   // weckt eine geschlossene App per Push
    }
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

    // ICE-Server (STUN + evtl. TURN) vorab im Hintergrund holen — blockiert NICHT den Signaling-Thread
    @Volatile private var iceServers: List<PeerConnection.IceServer> = TurnConfig.stunOnly

    fun start() {
        Thread { iceServers = TurnConfig.fetch(signalingBaseUrl) }.start()
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
                val client = WebRtcClient(ctx, egl, polite = !initiator, iceServers = iceServers,
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

    /** Sendet Text, liefert die Nachrichten-ID zurück (für die Status-Haken). */
    fun sendText(text: String): String {
        val id = java.util.UUID.randomUUID().toString()
        val c = crypto
        if (c != null) {                                  // live: über die Direktverbindung
            val (iv, ct) = c.encrypt(text.toByteArray(Charsets.UTF_8))
            rtc?.sendText(JSONObject().put("t", "msg").put("id", id).put("iv", iv).put("ct", ct).toString())
            ui { onMessage(true, text, id); onSentStatus?.invoke(id, "sent") }
        } else if (com.selfmessenger.app.Settings.offlineMailboxFor(ctx, contact.pubB64)) {   // offline: direkt in den Briefkasten
            ui { onMessage(true, text, id) }
            Thread {
                val ok = MailboxPost.postEnvelope(me, signalingBaseUrl, contact.pubB64,
                    JSONObject().put("t", "m").put("id", id).put("body", text))
                ui { onSentStatus?.invoke(id, if (ok) "sent" else "fail") }
            }.start()
        } else {                                          // Zwischenlagern aus -> nicht zustellbar
            ui { onMessage(true, text, id); onSentStatus?.invoke(id, "fail") }
        }
        return id
    }

    /** Bild/Datei/Sprachnachricht senden: verschlüsselt, in Häppchen. Live über WebRTC, sonst offline in den Briefkasten. */
    fun sendMedia(kind: String, name: String, mime: String, bytes: ByteArray) {
        val id = java.util.UUID.randomUUID().toString()
        val c = crypto
        if (c != null) {                                   // live über die Direktverbindung
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
            ui { onMedia?.invoke(true, kind, name, mime, bytes, id); onSentStatus?.invoke(id, "sent") }
        } else if (com.selfmessenger.app.Settings.offlineMailboxFor(ctx, contact.pubB64)) {   // offline: zerstückelt in den Briefkasten
            ui { onMedia?.invoke(true, kind, name, mime, bytes, id) }
            Thread {
                val total = maxOf(1, (bytes.size + OFF_CHUNK - 1) / OFF_CHUNK)
                var ok = MailboxPost.postEnvelope(me, signalingBaseUrl, contact.pubB64,   // Meta weckt per Push
                    JSONObject().put("t", "mm").put("id", id).put("kind", kind).put("name", name).put("mime", mime).put("total", total))
                var i = 0
                while (i < total) {
                    val slice = bytes.copyOfRange(i * OFF_CHUNK, minOf((i + 1) * OFF_CHUNK, bytes.size))
                    val b64 = android.util.Base64.encodeToString(slice, android.util.Base64.NO_WRAP)
                    if (!MailboxPost.postEnvelope(me, signalingBaseUrl, contact.pubB64,
                            JSONObject().put("t", "mc").put("id", id).put("i", i).put("body", b64), push = false)) ok = false
                    i++
                }
                ui { onSentStatus?.invoke(id, if (ok) "sent" else "fail") }   // nur „gesendet", wenn Meta + alle Häppchen ankamen
            }.start()
        } else {                                           // Zwischenlagern aus
            ui { onMedia?.invoke(true, kind, name, mime, bytes, id); onSentStatus?.invoke(id, "fail") }
        }
    }

    private val incoming = HashMap<String, MediaAcc>()

    private fun onWireText(json: String) {
        val m = JSONObject(json)
        when (m.optString("t")) {
            "bye" -> { callActive = false; callVideo = false; rtc?.hangupCall(); ui { onCallEnded?.invoke() } }
            "ack" -> {
                val id = m.optString("id"); val s = m.optString("s")
                if (id.isNotEmpty()) ui { onSentStatus?.invoke(id, if (s == "r") "read" else "delivered") }
            }
            "msg" -> {
                val c = crypto ?: return
                val plain = String(c.decrypt(m.getString("iv"), m.getString("ct")), Charsets.UTF_8)
                val id = m.optString("id", "")
                ui { onMessage(false, plain, id) }
                if (id.isNotEmpty()) {                    // live empfangen -> zugestellt + gelesen (Chat ist offen)
                    rtc?.sendText(JSONObject().put("t", "ack").put("id", id).put("s", "d").toString())
                    rtc?.sendText(JSONObject().put("t", "ack").put("id", id).put("s", "r").toString())
                }
            }
            "meta" -> incoming[m.getString("id")] =
                MediaAcc(m.getString("kind"), m.getString("name"), m.getString("mime"), m.getInt("total"))
            "chunk" -> {
                val acc = incoming[m.getString("id")] ?: return
                val c = crypto ?: return
                acc.parts[m.getInt("i")] = c.decrypt(m.getString("iv"), m.getString("ct"))
                acc.got++
                if (acc.got >= acc.total) {
                    val mid = m.getString("id")
                    incoming.remove(mid)
                    val bytes = acc.assemble()
                    ui { onMedia?.invoke(false, acc.kind, acc.name, acc.mime, bytes, mid) }
                    rtc?.sendText(JSONObject().put("t", "ack").put("id", mid).put("s", "d").toString())   // Bild vollständig -> zugestellt + gelesen
                    rtc?.sendText(JSONObject().put("t", "ack").put("id", mid).put("s", "r").toString())
                }
            }
        }
    }

    fun close() { rtc?.close(); signaling?.close(); egl.release() }

    private companion object { const val CHUNK = 16 * 1024; const val OFF_CHUNK = 60 * 1024 }
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
