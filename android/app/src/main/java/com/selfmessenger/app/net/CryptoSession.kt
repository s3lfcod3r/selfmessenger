package com.selfmessenger.app.net

import com.selfmessenger.app.Identity
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Identitäts-abgeleitete Sitzung: ECDH(meinPriv, KontaktPub) -> 32-Byte-Shared-Secret als
 * AES-256-GCM-Schlüssel. Byte-kompatibel zum Web-Client (Web Crypto ECDH deriveKey nutzt das
 * Shared Secret direkt als AES-Key; AES-GCM hängt den Tag an den Ciphertext an).
 */
class CryptoSession(private val aesKey: SecretKeySpec) {

    /** @return Paar (ivB64, ctB64) */
    fun encrypt(plain: ByteArray): Pair<String, String> {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        return Identity.b64(iv) to Identity.b64(c.doFinal(plain))
    }

    fun decrypt(ivB64: String, ctB64: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, Identity.b64d(ivB64)))
        return c.doFinal(Identity.b64d(ctB64))
    }

    companion object {
        fun derive(myPriv: PrivateKey, contactPubB64: String): CryptoSession {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(myPriv)
            ka.doPhase(rawToPublicKey(Identity.b64d(contactPubB64)), true)
            return CryptoSession(SecretKeySpec(ka.generateSecret(), "AES"))
        }

        /** Treffpunkt aus beiden Identitäten — nur wer die Schlüssel persönlich getauscht hat, kennt ihn. */
        fun rendezvous(aB64: String, bB64: String): String {
            val s = listOf(aB64, bB64).sorted().joinToString("|")
            val d = MessageDigest.getInstance("SHA-256").digest(("selfmsg-rv|$s").toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }.substring(0, 32)
        }

        /** Briefkasten-ID eines Empfängers (aus seinem Pubkey) — für Offline-Zustellung. Wie Web. */
        fun mailboxId(pubB64: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(("selfmsg-mbx|$pubB64").toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }.substring(0, 32)
        }

        fun safetyNumber(p1B64: String, p2B64: String): String {
            val s = listOf(p1B64, p2B64).sorted().joinToString("")
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }.substring(0, 20).chunked(4).joinToString(" ")
        }

        /** Rohen unkomprimierten P-256-Punkt (0x04||X||Y) in einen PublicKey wandeln. */
        private fun rawToPublicKey(raw: ByteArray): PublicKey {
            val x = BigInteger(1, raw.copyOfRange(1, 33))
            val y = BigInteger(1, raw.copyOfRange(33, 65))
            val params = AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
            val spec = ECPublicKeySpec(ECPoint(x, y), params)
            return KeyFactory.getInstance("EC").generatePublic(spec)
        }
    }
}
