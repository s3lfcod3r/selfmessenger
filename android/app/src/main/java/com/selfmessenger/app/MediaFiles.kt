package com.selfmessenger.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/**
 * Speichert empfangene/gesendete Medien-Bytes lokal (App-internes Verzeichnis), damit
 * Bilder/Dateien einen Neustart überleben. Zusätzlich: Bild in die Galerie speichern.
 */
object MediaFiles {
    private const val MAX_BYTES = 25 * 1024 * 1024   // 25 MB Deckel pro Datei

    private fun dir(ctx: Context) = File(ctx.filesDir, "media").apply { mkdirs() }

    /** Bytes ablegen, gibt eine ID zurück (oder null, wenn zu groß / Fehler). */
    fun save(ctx: Context, bytes: ByteArray): String? {
        if (bytes.size > MAX_BYTES) return null
        return try {
            val id = UUID.randomUUID().toString()
            File(dir(ctx), id).writeBytes(bytes)
            id
        } catch (e: Exception) { null }
    }

    fun load(ctx: Context, id: String): ByteArray? = try {
        val f = File(dir(ctx), id); if (f.exists()) f.readBytes() else null
    } catch (e: Exception) { null }

    /** Bild in die Galerie (Pictures/SelfMessenger) speichern. Gibt true bei Erfolg. */
    fun saveImageToGallery(ctx: Context, bytes: ByteArray, name: String, mime: String): Boolean = try {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name.ifBlank { "SelfMessenger.jpg" })
            put(MediaStore.Images.Media.MIME_TYPE, mime.ifBlank { "image/jpeg" })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SelfMessenger")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) { resolver.openOutputStream(uri)?.use { it.write(bytes) }; true } else false
    } catch (e: Exception) { false }
}
