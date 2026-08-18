package com.selfmessenger.app.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.selfmessenger.app.Identity

/**
 * Stellt den eigenen Pairing-Code per NFC bereit (Host Card Emulation).
 * Das Gegen-Handy im Lese-Modus schickt SELECT AID und bekommt den Code (Payload) + Status 9000.
 * So werden Schlüssel nur „von Angesicht zu Angesicht" getauscht.
 */
class PairingHceService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || !isSelectAid(commandApdu)) return SW_FAIL
        val payload = Identity.payload(Identity.load(this)).toByteArray(Charsets.UTF_8)
        return payload + SW_OK
    }

    override fun onDeactivated(reason: Int) { /* Verbindung getrennt — nichts zu tun */ }

    companion object {
        val AID: ByteArray = hex("F053454C464D5347")               // F0 + "SELFMSG"
        private val SW_OK = hex("9000")
        private val SW_FAIL = hex("6F00")

        fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** Prüft, ob das APDU ein SELECT auf unsere AID ist (00 A4 04 00 Lc <AID>). */
        private fun isSelectAid(apdu: ByteArray): Boolean {
            if (apdu.size < 6) return false
            if (apdu[0] != 0x00.toByte() || apdu[1] != 0xA4.toByte() ||
                apdu[2] != 0x04.toByte()) return false
            val lc = apdu[4].toInt() and 0xFF
            if (apdu.size < 5 + lc) return false
            return apdu.copyOfRange(5, 5 + lc).contentEquals(AID)
        }
    }
}
