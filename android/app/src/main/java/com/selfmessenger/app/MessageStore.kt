package com.selfmessenger.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Eine gespeicherte Chat-Zeile. `label` trägt Medien-Kurztext (Bytes werden NICHT persistiert).
 *  `id`/`status` nur für eigene Nachrichten (Status-Haken: sent/delivered/read/fail). */
data class StoredMsg(
    val fromMe: Boolean, val text: String?, val label: String?, val time: String,
    val id: String? = null, val status: String? = null
)

/**
 * Lokaler Chatverlauf, at-rest verschlüsselt (EncryptedSharedPreferences, Master-Key im Keystore).
 * Pro Kontakt eine JSON-Liste. Wird nur beschrieben, wenn [Settings.saveHistoryFor] true ist.
 * Medien (Bild/Datei/Sprache) werden als Kurztext gespeichert, nicht die Rohdaten (P2P, nicht nachladbar).
 */
object MessageStore {
    private const val PREFS = "sm_messages"
    private const val MAX_PER_CHAT = 2000

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, PREFS,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(ctx: Context, pub: String): List<StoredMsg> {
        val raw = prefs(ctx).getString(pub, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StoredMsg(
                    o.getBoolean("m"),
                    if (o.isNull("t")) null else o.getString("t"),
                    if (o.isNull("l")) null else o.getString("l"),
                    o.optString("ts", ""),
                    if (o.has("id") && !o.isNull("id")) o.getString("id") else null,
                    if (o.has("st") && !o.isNull("st")) o.getString("st") else null
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun append(ctx: Context, pub: String, msg: StoredMsg) {
        val list = load(ctx, pub).toMutableList()
        list.add(msg)
        while (list.size > MAX_PER_CHAT) list.removeAt(0)
        save(ctx, pub, list)
    }

    fun clear(ctx: Context, pub: String) = prefs(ctx).edit().remove(pub).apply()

    /** Status-Haken einer gesendeten Nachricht aktualisieren (nie herabstufen). */
    fun updateStatus(ctx: Context, pub: String, id: String, status: String) {
        val list = load(ctx, pub)
        var changed = false
        val out = list.map {
            if (it.id == id && rank(status) > rank(it.status)) { changed = true; it.copy(status = status) } else it
        }
        if (changed) save(ctx, pub, out)
    }

    private fun rank(s: String?): Int = when (s) { "sent" -> 1; "delivered" -> 2; "read" -> 3; else -> 0 }

    private fun save(ctx: Context, pub: String, list: List<StoredMsg>) {
        val arr = JSONArray()
        for (m in list) arr.put(JSONObject().apply {
            put("m", m.fromMe)
            put("t", m.text ?: JSONObject.NULL)
            put("l", m.label ?: JSONObject.NULL)
            put("ts", m.time)
            put("id", m.id ?: JSONObject.NULL)
            put("st", m.status ?: JSONObject.NULL)
        })
        prefs(ctx).edit().putString(pub, arr.toString()).apply()
    }
}
