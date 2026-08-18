package com.selfmessenger.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Signaling-Client: WebSocket zum Cloudflare-Kuppler. Reicht nur Verbindungs-Zettel
 * (ready/desc/ice) durch — sieht nie Inhalte. Gleiche Protokoll-Nachrichten wie der Web-Client.
 */
class Signaling(
    private val baseUrl: String,               // z.B. wss://selfmessenger-signaling.<sub>.workers.dev/ws
    private val onMessage: (JSONObject) -> Unit,
    private val onOpen: () -> Unit,
    private val onClosed: () -> Unit
) {
    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    fun connect(room: String) {
        val req = Request.Builder().url("$baseUrl?room=$room").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = onMessage(JSONObject(text))
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onClosed()
        })
    }

    fun send(o: JSONObject) { ws?.send(o.toString()) }
    fun close() { ws?.close(1000, null); ws = null }
}
