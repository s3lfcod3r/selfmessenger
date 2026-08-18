package com.selfmessenger.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Freunde, nur durch persönlichen Code-Tausch (QR/NFC) hinzugefügt. At-rest verschlüsselt. */
data class Contact(val name: String, val pubB64: String)

object Contacts {
    private const val PREFS = "sm_contacts"
    private const val K = "list"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx,
        PREFS,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun all(ctx: Context): List<Contact> {
        val arr = JSONArray(prefs(ctx).getString(K, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it); Contact(o.getString("n"), o.getString("k"))
        }
    }

    /** Fügt einen Freund aus seinem Pairing-Code hinzu. Gibt false bei ungültigem Code zurück. */
    fun addFromPayload(ctx: Context, code: String): Boolean {
        val o = try { JSONObject(String(Identity.b64d(code.trim()), Charsets.UTF_8)) }
                catch (e: Exception) { return false }
        if (!o.has("k") || !o.has("n")) return false
        val list = all(ctx).toMutableList()
        if (list.none { it.pubB64 == o.getString("k") }) list.add(Contact(o.getString("n"), o.getString("k")))
        save(ctx, list)
        return true
    }

    private fun save(ctx: Context, list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("k", it.pubB64)) }
        prefs(ctx).edit().putString(K, arr.toString()).apply()
    }
}
