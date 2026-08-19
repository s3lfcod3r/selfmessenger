package com.selfmessenger.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection

/**
 * Holt die ICE-Server (STUN + evtl. Cloudflare-TURN) vom Kuppler (`/turn`).
 * TURN ist nötig, wenn die direkte P2P-Verbindung hinter NAT scheitert. Blockierend → aus Hintergrund-Thread aufrufen.
 */
object TurnConfig {
    private val http = OkHttpClient()

    private fun turnUrl(baseUrl: String): String =
        baseUrl.replaceFirst(Regex("^ws"), "http").replace(Regex("/ws(\\?.*)?$"), "/turn")

    private val stunOnly: List<PeerConnection.IceServer>
        get() = listOf(
            PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"))
                .createIceServer()
        )

    fun fetch(baseUrl: String): List<PeerConnection.IceServer> = try {
        val req = Request.Builder().url(turnUrl(baseUrl)).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return stunOnly
            val arr = JSONObject(body).getJSONArray("iceServers")
            val out = ArrayList<PeerConnection.IceServer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val urls = ArrayList<String>()
                when (val u = o.get("urls")) {
                    is JSONArray -> for (k in 0 until u.length()) urls.add(u.getString(k))
                    else -> urls.add(u.toString())
                }
                val b = PeerConnection.IceServer.builder(urls)
                if (o.has("username")) b.setUsername(o.getString("username"))
                if (o.has("credential")) b.setPassword(o.getString("credential"))
                out.add(b.createIceServer())
            }
            if (out.isEmpty()) stunOnly else out
        }
    } catch (e: Exception) { stunOnly }
}
