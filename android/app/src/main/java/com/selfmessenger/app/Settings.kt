package com.selfmessenger.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Nutzer-Einstellungen (verschlüsselt at-rest). Zwei getrennte Schalter, jeweils mit
 * globalem Standard UND optionaler Überschreibung pro Kontakt (Tri-State: null = folgt global):
 *  - saveHistory:    Chatverlauf lokal (verschlüsselt) speichern?
 *  - offlineMailbox: Nachrichten für einen offline-Partner auf dem Kuppler zwischenlagern?
 */
object Settings {
    private const val PREFS = "sm_settings"
    private const val K_HIST = "hist_global"
    private const val K_MBX = "mbx_global"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, PREFS,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // --- globale Standardwerte (Default: beides an) ---
    fun saveHistoryGlobal(ctx: Context): Boolean = prefs(ctx).getBoolean(K_HIST, true)
    fun offlineMailboxGlobal(ctx: Context): Boolean = prefs(ctx).getBoolean(K_MBX, true)
    fun setSaveHistoryGlobal(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_HIST, on).apply()
    fun setOfflineMailboxGlobal(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_MBX, on).apply()

    // --- pro Kontakt: "on" / "off" / (nicht gesetzt = global) ---
    fun saveHistoryFor(ctx: Context, pub: String): Boolean = resolve(ctx, "hist:$pub", saveHistoryGlobal(ctx))
    fun offlineMailboxFor(ctx: Context, pub: String): Boolean = resolve(ctx, "mbx:$pub", offlineMailboxGlobal(ctx))

    /** null = folgt global; true/false = Kontakt-Überschreibung. */
    fun historyOverride(ctx: Context, pub: String): Boolean? = override(ctx, "hist:$pub")
    fun mailboxOverride(ctx: Context, pub: String): Boolean? = override(ctx, "mbx:$pub")
    fun setHistoryOverride(ctx: Context, pub: String, value: Boolean?) = setOverride(ctx, "hist:$pub", value)
    fun setMailboxOverride(ctx: Context, pub: String, value: Boolean?) = setOverride(ctx, "mbx:$pub", value)

    private fun resolve(ctx: Context, key: String, global: Boolean): Boolean =
        when (prefs(ctx).getString(key, null)) { "on" -> true; "off" -> false; else -> global }

    private fun override(ctx: Context, key: String): Boolean? =
        when (prefs(ctx).getString(key, null)) { "on" -> true; "off" -> false; else -> null }

    private fun setOverride(ctx: Context, key: String, value: Boolean?) {
        val e = prefs(ctx).edit()
        if (value == null) e.remove(key) else e.putString(key, if (value) "on" else "off")
        e.apply()
    }
}
