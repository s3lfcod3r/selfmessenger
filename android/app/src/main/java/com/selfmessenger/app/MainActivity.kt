package com.selfmessenger.app

import android.net.VpnService
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.selfmessenger.app.nfc.PairingHceService
import com.selfmessenger.app.ui.AppRoot

/** Einstiegspunkt. Biometrische Entsperrung + NFC-Lesemodus fürs persönliche Pairing. */
class MainActivity : FragmentActivity() {

    private var nfcAdapter: NfcAdapter? = null

    private var pendingVpn: ((Boolean) -> Unit)? = null
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingVpn?.invoke(result.resultCode == RESULT_OK); pendingVpn = null
    }

    /** VPN-Einwilligung des Systems einholen, dann Callback mit Ergebnis. */
    fun prepareVpn(onResult: (Boolean) -> Unit) {
        val intent = VpnService.prepare(this)
        if (intent == null) onResult(true) else { pendingVpn = onResult; vpnConsent.launch(intent) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent { AppRoot(onUnlock = { onOk -> promptUnlock(onOk) }, onPrepareVpn = { cb -> prepareVpn(cb) }) }
    }

    override fun onResume() {
        super.onResume()
        // Lese-Modus: hält man ein anderes SelfMessenger-Handy dran, wird dessen Code gelesen.
        nfcAdapter?.enableReaderMode(
            this, { tag -> onNfcTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    private fun onNfcTag(tag: Tag) {
        val iso = IsoDep.get(tag) ?: return
        try {
            iso.connect()
            val select = PairingHceService.hex("00A40400") +
                    byteArrayOf(PairingHceService.AID.size.toByte()) + PairingHceService.AID + byteArrayOf(0x00.toByte())
            val resp = iso.transceive(select)
            if (resp.size >= 2 && resp[resp.size - 2] == 0x90.toByte() && resp[resp.size - 1] == 0x00.toByte()) {
                val payload = String(resp.copyOfRange(0, resp.size - 2), Charsets.UTF_8)
                if (Contacts.addFromPayload(this, payload)) {
                    runOnUiThread { Toast.makeText(this, "Freund per NFC hinzugefügt", Toast.LENGTH_SHORT).show() }
                }
            }
            iso.close()
        } catch (_: Exception) { /* Verbindung abgebrochen — ignorieren */ }
    }

    private fun promptUnlock(onOk: () -> Unit) {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("SelfMessenger entsperren")
            .setSubtitle("PIN, Fingerabdruck oder Gesicht")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL).build()
        } else {
            builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Abbrechen").build()
        }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            }).authenticate(info)
    }
}
