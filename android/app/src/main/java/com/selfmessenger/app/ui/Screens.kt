package com.selfmessenger.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmessenger.app.Config
import com.selfmessenger.app.Contact
import com.selfmessenger.app.Contacts
import com.selfmessenger.app.Identity
import com.selfmessenger.app.Me
import com.selfmessenger.app.Qr
import com.selfmessenger.app.net.Connection
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.wireguard.android.backend.Tunnel
import com.selfmessenger.app.vpn.VpnManager
import com.selfmessenger.app.vpn.VpnStore
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.widget.Toast
import com.selfmessenger.app.media.VoicePlayer
import com.selfmessenger.app.media.VoiceRecorder
import com.selfmessenger.app.net.MailboxClient

@Composable
fun AppRoot(onUnlock: (() -> Unit) -> Unit, onPrepareVpn: ((Boolean) -> Unit) -> Unit) {
    var unlocked by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (!unlocked) LockScreen { onUnlock { unlocked = true } } else MainNav(onPrepareVpn)
        }
    }
}

@Composable
private fun LockScreen(onUnlockClick: () -> Unit) {
    LaunchedEffect(Unit) { onUnlockClick() }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SelfMessenger", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Gesperrt", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlockClick) { Text("Entsperren (PIN / Finger / Gesicht)") }
    }
}

@Composable
private fun MainNav(onPrepareVpn: ((Boolean) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val me = remember { Identity.load(ctx) }
    var chatWith by remember { mutableStateOf<Contact?>(null) }
    var showVpn by remember { mutableStateOf(false) }
    // Briefkasten app-weit: empfängt offline hinterlegte Nachrichten
    DisposableEffect(Unit) {
        val mbx = MailboxClient(ctx.applicationContext, me, Config.SIGNALING_URL) { from, text ->
            Toast.makeText(ctx, "📨 $from (offline): $text", Toast.LENGTH_LONG).show()
        }
        mbx.start()
        onDispose { mbx.stop() }
    }
    val target = chatWith
    when {
        showVpn -> VpnScreen(onPrepareVpn) { showVpn = false }
        target != null -> ChatScreen(me, target) { chatWith = null }
        else -> MainScreen(me, onOpenChat = { chatWith = it }, onOpenVpn = { showVpn = true })
    }
}

@Composable
private fun MainScreen(me: Me, onOpenChat: (Contact) -> Unit, onOpenVpn: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(me.name) }
    var addCode by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf(Contacts.all(ctx)) }
    val payload = remember(name) { Identity.payload(Identity.load(ctx)) }
    val qr = remember(payload) { Qr.bitmap(payload, 480).asImageBitmap() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        // Kopf
        Text("SelfMessenger", style = MaterialTheme.typography.titleLarge)
        Text("${me.name} · 🔑 ${Identity.fingerprint(me.pubB64)}",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(18.dp))

        // Dein Code — QR + Teilen/Kopieren
        Text("Dein Pairing-Code", style = MaterialTheme.typography.titleMedium)
        Text("Freund scannt den QR – oder schick ihm den Code.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
            Image(bitmap = qr, contentDescription = "QR-Code", modifier = Modifier.padding(10.dp).size(200.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { shareCode(ctx, payload) }, modifier = Modifier.weight(1f)) { Text("📤 Teilen") }
            OutlinedButton(onClick = { copyCode(ctx, payload) }, modifier = Modifier.weight(1f)) { Text("⧉ Kopieren") }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = name, onValueChange = { name = it },
            label = { Text("Dein Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { Identity.setName(ctx, name) }) { Text("Name speichern") }
            TextButton(onClick = onOpenVpn) { Text("🔒 VPN") }
        }

        Spacer(Modifier.height(10.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))

        // Freund hinzufügen
        Text("Freund hinzufügen", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = addCode, onValueChange = { addCode = it },
            label = { Text("Code des Freundes einfügen") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (Contacts.addFromPayload(ctx, addCode)) { addCode = ""; contacts = Contacts.all(ctx) }
        }, modifier = Modifier.fillMaxWidth()) { Text("Als Freund speichern") }
        Spacer(Modifier.height(6.dp))
        Text("📲 Oder haltet die Handys aneinander (NFC).",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kontakte", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { contacts = Contacts.all(ctx) }) { Text("↻") }
        }
        if (contacts.isEmpty()) {
            Text("Noch keine Freunde. Tippe „Teilen“ und tauscht eure Codes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        contacts.forEach { c ->
            Row(Modifier.fillMaxWidth().clickable { onOpenChat(c) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name)
                    Text("🔑 ${Identity.fingerprint(c.pubB64)}",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
            }
        }
    }
}

/** Pairing-Code über die Android-Teilen-Funktion verschicken (WhatsApp, Signal …). */
private fun shareCode(ctx: android.content.Context, code: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, "Verbinde dich mit mir auf SelfMessenger. Mein Code:\n\n$code")
    }
    ctx.startActivity(android.content.Intent.createChooser(send, "Code teilen"))
}

/** Pairing-Code in die Zwischenablage. */
private fun copyCode(ctx: android.content.Context, code: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("SelfMessenger", code))
    android.widget.Toast.makeText(ctx, "Code kopiert", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun ChatScreen(me: Me, contact: Contact, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("…") }
    var input by remember { mutableStateOf("") }
    var inCall by remember { mutableStateOf(false) }
    var localVideo by remember { mutableStateOf<VideoTrack?>(null) }
    var remoteVideo by remember { mutableStateOf<VideoTrack?>(null) }
    val messages = remember { mutableStateListOf<ChatItem>() }
    val conn = remember {
        Connection(ctx.applicationContext, me, contact, Config.SIGNALING_URL,
            onStatus = { status = it },
            onMessage = { fromMe, text -> messages.add(ChatItem(fromMe, text = text)) })
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) readPicked(ctx, uri)?.let { (n, mime, b) -> conn.sendMedia("image", n, mime, b) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) readPicked(ctx, uri)?.let { (n, mime, b) -> conn.sendMedia("file", n, mime, b) }
    }
    val recorder = remember { VoiceRecorder() }
    var recording by remember { mutableStateOf(false) }
    fun startRec() { try { recorder.start(ctx); recording = true } catch (_: Exception) {} }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRec()
    }
    DisposableEffect(conn) {
        conn.onLocalVideo = { localVideo = it }
        conn.onRemoteVideo = { remoteVideo = it }
        conn.onMedia = { fromMe, kind, name, mime, bytes ->
            messages.add(ChatItem(fromMe, media = MediaData(kind, name, mime, bytes)))
        }
        conn.start()
        onDispose { conn.close() }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Kontakte") }
            Spacer(Modifier.width(8.dp))
            Text(contact.name, style = MaterialTheme.typography.titleMedium)
        }
        Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (inCall) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoRenderer(remoteVideo, conn.egl.eglBaseContext, Modifier.weight(1f).height(180.dp))
                VideoRenderer(localVideo, conn.egl.eglBaseContext, Modifier.weight(1f).height(180.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(messages) { item ->
                if (item.text != null) {
                    Text((if (item.fromMe) "→ " else "← ") + item.text, Modifier.padding(vertical = 4.dp))
                } else if (item.media != null) {
                    MediaBubble(item)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { imagePicker.launch("image/*") }) { Text("🖼") }
            TextButton(onClick = { filePicker.launch("*/*") }) { Text("📎") }
            TextButton(onClick = {
                if (recording) {
                    val bytes = recorder.stop(); recording = false
                    if (bytes != null) conn.sendMedia("voice", "sprachnachricht.m4a", "audio/mp4", bytes)
                } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startRec()
                } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (recording) "⏺" else "🎤") }
            if (!inCall) {
                TextButton(onClick = { conn.startCall(false); inCall = true }) { Text("📞") }
                TextButton(onClick = { conn.startCall(true); inCall = true }) { Text("📹") }
            } else {
                TextButton(onClick = { conn.hangupCall(); inCall = false; localVideo = null }) { Text("⏹") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), placeholder = { Text("Nachricht…") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (input.isNotBlank()) { conn.sendText(input.trim()); input = "" } }) { Text("➤") }
        }
    }
}

/** Rendert eine WebRTC-Video-Spur (Kamera lokal / Partner remote). */
@Composable
private fun VideoRenderer(track: VideoTrack?, egl: EglBase.Context, modifier: Modifier) {
    val ctx = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(ctx) }
    DisposableEffect(Unit) {
        renderer.init(egl, null)
        onDispose { renderer.release() }
    }
    DisposableEffect(track) {
        track?.addSink(renderer)
        onDispose { track?.removeSink(renderer) }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

@Composable
private fun VpnScreen(onPrepareVpn: ((Boolean) -> Unit) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(VpnStore.load(ctx)) }
    val state by VpnManager.state.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Spacer(Modifier.width(8.dp))
            Text("VPN (WireGuard / Mullvad)", style = MaterialTheme.typography.titleMedium)
        }
        Text("Versteckt deine echte IP — beim P2P-Chat sieht der Partner sonst deine Adresse.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("Status: $state", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = if (state == Tunnel.State.UP) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = config, onValueChange = { config = it },
            label = { Text("WireGuard-Konfiguration einfügen (z.B. Mullvad)") },
            modifier = Modifier.fillMaxWidth().height(200.dp))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                VpnStore.save(ctx, config)
                onPrepareVpn { ok -> if (ok) scope.launch { VpnManager.up(ctx, config) } }
            }) { Text("VPN an") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { scope.launch { VpnManager.down(ctx) } }) { Text("VPN aus") }
        }
    }
}

private data class MediaData(val kind: String, val name: String, val mime: String, val bytes: ByteArray)
private data class ChatItem(val fromMe: Boolean, val text: String? = null, val media: MediaData? = null)

@Composable
private fun MediaBubble(item: ChatItem) {
    val m = item.media ?: return
    val prefix = if (item.fromMe) "→ " else "← "
    if (m.kind == "image") {
        val bmp = remember(m) { BitmapFactory.decodeByteArray(m.bytes, 0, m.bytes.size) }
        Column(Modifier.padding(vertical = 4.dp)) {
            Text("$prefix🖼 ${m.name}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = m.name, modifier = Modifier.height(160.dp))
        }
    } else if (m.kind == "voice") {
        val ctx = LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("$prefix🎤 Sprachnachricht")
            TextButton(onClick = { VoicePlayer.play(ctx, m.bytes) }) { Text("▶ abspielen") }
        }
    } else {
        Text("$prefix📎 ${m.name} (${m.bytes.size} B)", Modifier.padding(vertical = 4.dp))
    }
}

/** Liest die gewählte Datei (Name, MIME, Bytes) über den ContentResolver. */
private fun readPicked(ctx: android.content.Context, uri: Uri): Triple<String, String, ByteArray>? = try {
    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
    var name = "datei"
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) {
            val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) name = cur.getString(idx)
        }
    }
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    Triple(name, mime, bytes)
} catch (e: Exception) { null }
