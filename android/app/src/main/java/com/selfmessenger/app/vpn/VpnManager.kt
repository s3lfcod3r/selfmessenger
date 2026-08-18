package com.selfmessenger.app.vpn

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Tunnel-Adapter; meldet Zustandswechsel zurück. Name muss 1-15 Zeichen [a-zA-Z0-9_=+.-] sein. */
class SelfTunnel(private val onState: (Tunnel.State) -> Unit) : Tunnel {
    override fun getName(): String = "SelfMessenger"
    override fun onStateChange(newState: Tunnel.State) = onState(newState)
}

/**
 * Schlanke WireGuard-Steuerung (an/aus) über GoBackend aus com.wireguard.android:tunnel.
 * Versteckt die echte IP hinter dem VPN — der Gesprächspartner sieht bei P2P sonst deine IP.
 * Reuse-Basis: SelfWG (dort mit Auto-Reconnect-Wächter; hier bewusst minimal).
 */
object VpnManager {
    private var backend: Backend? = null
    private val tunnel = SelfTunnel { st -> _state.value = st }

    private val _state = MutableStateFlow(Tunnel.State.DOWN)
    val state: StateFlow<Tunnel.State> = _state.asStateFlow()

    private fun init(ctx: Context) {
        if (backend == null) {
            try { backend = GoBackend(ctx.applicationContext) } catch (_: Throwable) { /* libwg-go.so fehlt */ }
        }
    }

    /** WireGuard-Konfig (z.B. Mullvad) hochfahren. VpnService.prepare-Consent muss vorher erteilt sein. */
    suspend fun up(ctx: Context, configText: String): Boolean = withContext(Dispatchers.IO) {
        init(ctx)
        val b = backend ?: return@withContext false
        try {
            val cfg = Config.parse(configText.byteInputStream().bufferedReader())
            b.setState(tunnel, Tunnel.State.UP, cfg)
            true
        } catch (_: Exception) { false }
    }

    suspend fun down(ctx: Context) = withContext(Dispatchers.IO) {
        init(ctx)
        try { backend?.setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) { }
        Unit
    }
}

/** WireGuard-Konfig verschlüsselt speichern (at-rest, Android Keystore). */
object VpnStore {
    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, "sm_vpn",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun load(ctx: Context): String = prefs(ctx).getString("config", "") ?: ""
    fun save(ctx: Context, config: String) { prefs(ctx).edit().putString("config", config).apply() }
}
