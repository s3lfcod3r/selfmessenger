package com.selfmessenger.app

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/** Eigene Geräte-Identität. Schlüssel liegen at-rest verschlüsselt (EncryptedSharedPreferences,
 *  Master-Key im Android Keystore). Passt zum Web-Format: P-256, öffentlicher Schlüssel als
 *  rohe unkomprimierte Punktdarstellung (0x04||X||Y), Base64. */
data class Me(val name: String, val privateKey: PrivateKey, val pubRaw: ByteArray, val pubB64: String)

object Identity {
    private const val PREFS = "sm_secure"
    private const val K_NAME = "name"
    private const val K_PRIV = "priv"   // PKCS8, Base64
    private const val K_PUB = "pub"     // roh unkomprimiert, Base64

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx,
        PREFS,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(ctx: Context): Me {
        val p = prefs(ctx)
        if (!p.contains(K_PRIV)) create(ctx)
        val name = p.getString(K_NAME, "Ich") ?: "Ich"
        val priv = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(b64d(p.getString(K_PRIV, null)!!)))
        val pubB64 = p.getString(K_PUB, null)!!
        return Me(name, priv, b64d(pubB64), pubB64)
    }

    private fun create(ctx: Context) {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val kp = gen.generateKeyPair()
        val pubRaw = rawPublic(kp.public as ECPublicKey)
        prefs(ctx).edit()
            .putString(K_NAME, "Ich")
            .putString(K_PRIV, b64(kp.private.encoded))
            .putString(K_PUB, b64(pubRaw))
            .apply()
    }

    fun setName(ctx: Context, name: String) {
        prefs(ctx).edit().putString(K_NAME, name.ifBlank { "Ich" }).apply()
    }

    fun payload(me: Me): String =
        b64(JSONObject().put("n", me.name).put("k", me.pubB64).toString().toByteArray(Charsets.UTF_8))

    fun fingerprint(pubB64: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(b64d(pubB64))
        return d.joinToString("") { "%02x".format(it) }.substring(0, 12).chunked(4).joinToString(" ")
    }

    private fun rawPublic(pub: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + to32(pub.w.affineX.toByteArray()) + to32(pub.w.affineY.toByteArray())

    private fun to32(b: ByteArray): ByteArray {
        val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
