package com.selfmessenger.app.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Nimmt eine Sprachnachricht auf (AAC/MPEG-4) und liefert die Bytes. */
class VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    fun start(ctx: Context) {
        val f = File.createTempFile("voice", ".m4a", ctx.cacheDir); file = f
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start(); recorder = r
    }

    fun stop(): ByteArray? = try {
        recorder?.stop(); recorder?.release(); recorder = null
        file?.readBytes()
    } catch (e: Exception) { null } finally { file?.delete(); file = null }
}

/** Spielt eine empfangene Sprachnachricht (aus Bytes) ab. */
object VoicePlayer {
    private var mp: MediaPlayer? = null
    fun play(ctx: Context, bytes: ByteArray) {
        stop()
        val f = File.createTempFile("play", ".m4a", ctx.cacheDir); f.writeBytes(bytes)
        mp = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { f.delete() }
            prepare(); start()
        }
    }
    fun stop() { try { mp?.release() } catch (_: Exception) {}; mp = null }
}
