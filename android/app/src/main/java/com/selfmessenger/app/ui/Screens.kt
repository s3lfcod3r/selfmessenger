package com.selfmessenger.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.selfmessenger.app.Config
import com.selfmessenger.app.Contact
import com.selfmessenger.app.Contacts
import com.selfmessenger.app.Identity
import com.selfmessenger.app.Me
import com.selfmessenger.app.Qr
import com.selfmessenger.app.media.VoicePlayer
import com.selfmessenger.app.media.VoiceRecorder
import com.selfmessenger.app.net.Connection
import com.selfmessenger.app.net.MailboxClient
import com.selfmessenger.app.vpn.VpnManager
import com.selfmessenger.app.vpn.VpnStore
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- Self-Farben ----
private val SelfTeal = Color(0xFF33A78C)
private val SelfTealBright = Color(0xFF43D3AD)
private val SelfTealDeep = Color(0xFF1F7763)
private val SelfIce = Color(0xFF9DBDD0)
private val SelfBg = Color(0xFF080C11)
private val SelfPanel = Color(0xFF161B22)
private val SelfText = Color(0xFFEEF4F7)
private val SelfMuted = Color(0xFF8A9CAA)
private val BubbleSelf = Color(0xFF0C3B30)
private val BubbleThem = Color(0xFF161B22)
private val OnTeal = Color(0xFF04120D)

private val SelfColors = darkColorScheme(
    primary = SelfTeal, onPrimary = OnTeal, secondary = SelfTealBright,
    background = SelfBg, onBackground = SelfText,
    surface = SelfPanel, onSurface = SelfText,
    surfaceVariant = SelfPanel, onSurfaceVariant = SelfMuted
)

private fun initialOf(n: String?): String = ((n ?: "?").trim().firstOrNull() ?: '?').uppercaseChar().toString()
private fun nowTime(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

@Composable
fun AppRoot(onUnlock: (() -> Unit) -> Unit, onPrepareVpn: ((Boolean) -> Unit) -> Unit) {
    var unlocked by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = SelfColors) {
        Surface(Modifier.fillMaxSize(), color = SelfBg) {
            if (!unlocked) LockScreen { onUnlock { unlocked = true } } else MainNav(onPrepareVpn)
        }
    }
}

@Composable
private fun LockScreen(onUnlockClick: () -> Unit) {
    LaunchedEffect(Unit) { onUnlockClick() }
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Wordmark(22)
        Spacer(Modifier.height(10.dp))
        Text("Gesperrt", color = SelfMuted)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlockClick) { Text("Entsperren (PIN / Finger / Gesicht)") }
    }
}

@Composable
private fun MainNav(onPrepareVpn: ((Boolean) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val me = remember { Identity.load(ctx) }
    var screen by remember { mutableStateOf("chats") }   // chats | mycode | add | vpn
    var chatWith by remember { mutableStateOf<Contact?>(null) }

    DisposableEffect(Unit) {
        val mbx = MailboxClient(ctx.applicationContext, me, Config.SIGNALING_URL) { from, text ->
            Toast.makeText(ctx, "📨 $from: $text", Toast.LENGTH_LONG).show()
        }
        mbx.start(); onDispose { mbx.stop() }
    }

    val target = chatWith
    when {
        target != null -> ChatScreen(me, target) { chatWith = null }
        screen == "mycode" -> MyCodeScreen(me) { screen = "chats" }
        screen == "add" -> AddFriendScreen { screen = "chats" }
        screen == "vpn" -> VpnScreen(onPrepareVpn) { screen = "chats" }
        else -> ChatsScreen(me,
            onOpenChat = { chatWith = it },
            onMyCode = { screen = "mycode" },
            onAddFriend = { screen = "add" },
            onVpn = { screen = "vpn" })
    }
}

// ================= Bausteine =================

@Composable
private fun Wordmark(size: Int) {
    Row {
        Text("Self", fontSize = size.sp, fontWeight = FontWeight.Bold, color = SelfIce)
        Text("Messenger", fontSize = size.sp, fontWeight = FontWeight.Bold, color = SelfTeal)
    }
}

@Composable
private fun Avatar(name: String, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(SelfTealDeep, SelfTealBright))),
        contentAlignment = Alignment.Center
    ) { Text(initialOf(name), color = OnTeal, fontWeight = FontWeight.Bold, fontSize = (size * 0.42).sp) }
}

@Composable
private fun TopBar(onBack: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(SelfPanel).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Text("←", fontSize = 22.sp, color = SelfText) }
        else Spacer(Modifier.width(10.dp))
        content()
    }
}

// ================= Chats (Start) =================

@Composable
private fun ChatsScreen(me: Me, onOpenChat: (Contact) -> Unit, onMyCode: () -> Unit, onAddFriend: () -> Unit, onVpn: () -> Unit) {
    val ctx = LocalContext.current
    val contacts = remember { Contacts.all(ctx) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar {
                Box(Modifier.weight(1f)) { Wordmark(19) }
                IconButton(onClick = onVpn) { Text("🔒", fontSize = 19.sp) }
                IconButton(onClick = onMyCode) { Text("🔗", fontSize = 19.sp) }
            }
            if (contacts.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(28.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("Noch keine Chats.", color = SelfMuted, fontSize = 15.sp)
                    Text("Tippe + und tauscht eure Codes persönlich.", color = SelfMuted, fontSize = 13.sp)
                }
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(contacts) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenChat(c) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(c.name, 48); Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 16.sp, color = SelfText)
                            Text("🔒 Ende-zu-Ende verschlüsselt", fontSize = 13.sp, color = SelfMuted)
                        }
                    }
                    HorizontalDivider(color = Color(0x14FFFFFF))
                }
            }
        }
        FloatingActionButton(
            onClick = onAddFriend, containerColor = SelfTeal, contentColor = OnTeal,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) { Text("+", fontSize = 28.sp) }
    }
}

// ================= Mein Code =================

@Composable
private fun MyCodeScreen(me: Me, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(me.name) }
    val payload = remember(name) { Identity.payload(Identity.load(ctx)) }
    val qr = remember(payload) { Qr.bitmap(payload, 480).asImageBitmap() }
    Column(Modifier.fillMaxSize()) {
        TopBar(onBack) { Text("Mein Code", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Freund scannt den QR – oder schick ihm den Code.", fontSize = 13.sp, color = SelfMuted)
            Spacer(Modifier.height(12.dp))
            Surface(color = Color.White, shape = RoundedCornerShape(14.dp)) {
                Image(qr, "QR-Code", Modifier.padding(12.dp).size(220.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { shareCode(ctx, payload) }, Modifier.weight(1f)) { Text("📤 Teilen") }
                OutlinedButton(onClick = { copyCode(ctx, payload) }, Modifier.weight(1f)) { Text("⧉ Kopieren") }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Dein Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { Identity.setName(ctx, name) }) { Text("Name speichern") }
        }
    }
}

// ================= Freund hinzufügen =================

@Composable
private fun AddFriendScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var addCode by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar(onBack) { Text("Freund hinzufügen", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("Füge den Code deines Freundes ein (den er dir per „Teilen“ geschickt hat) — oder haltet eure Handys aneinander (NFC).", fontSize = 13.sp, color = SelfMuted)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(value = addCode, onValueChange = { addCode = it }, label = { Text("Code des Freundes einfügen") }, modifier = Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (Contacts.addFromPayload(ctx, addCode)) { Toast.makeText(ctx, "Freund gespeichert", Toast.LENGTH_SHORT).show(); onBack() }
                else Toast.makeText(ctx, "Ungültiger Code", Toast.LENGTH_SHORT).show()
            }, Modifier.fillMaxWidth()) { Text("Als Freund speichern") }
        }
    }
}

// ================= Chat =================

private data class MediaData(val kind: String, val name: String, val mime: String, val bytes: ByteArray)
private data class ChatItem(val fromMe: Boolean, val text: String? = null, val media: MediaData? = null, val time: String = nowTime())
private data class CallUi(val video: Boolean, val incoming: Boolean, val muted: Boolean = false, val camOff: Boolean = false)

@Composable
private fun ChatScreen(me: Me, contact: Contact, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("verbinde …") }
    var input by remember { mutableStateOf("") }
    var localVideo by remember { mutableStateOf<VideoTrack?>(null) }
    var remoteVideo by remember { mutableStateOf<VideoTrack?>(null) }
    var call by remember { mutableStateOf<CallUi?>(null) }
    val messages = remember { mutableStateListOf<ChatItem>() }
    val listState = rememberLazyListState()

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
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g -> if (g) startRec() }

    // Anruf-Berechtigungen
    var pendingCall by remember { mutableStateOf<(() -> Unit)?>(null) }
    val callPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) pendingCall?.invoke(); pendingCall = null
    }
    fun withCallPerms(video: Boolean, action: () -> Unit) {
        val perms = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) action()
        else { pendingCall = action; callPerms.launch(perms) }
    }

    DisposableEffect(conn) {
        conn.onLocalVideo = { localVideo = it }
        conn.onRemoteVideo = { remoteVideo = it }
        conn.onMedia = { fromMe, kind, name, mime, bytes -> messages.add(ChatItem(fromMe, media = MediaData(kind, name, mime, bytes))) }
        conn.onIncomingCall = { video -> if (call == null) call = CallUi(video = video, incoming = true) else if (video) call = call!!.copy(video = true) }
        conn.onCallConnected = { call = call?.copy(incoming = false) }
        conn.onCallEnded = { call = null; localVideo = null; remoteVideo = null }
        conn.start()
        onDispose { conn.close() }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize()) {
        // Kopfzeile
        TopBar(onBack) {
            Avatar(contact.name, 38); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(contact.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SelfText, maxLines = 1)
                Text(status, fontSize = 11.sp, color = SelfMuted, maxLines = 1)
            }
            IconButton(onClick = { withCallPerms(false) { call = CallUi(false, false); conn.startCall(false) } }) { Text("📞", fontSize = 18.sp) }
            IconButton(onClick = { withCallPerms(true) { call = CallUi(true, false); conn.startCall(true) } }) { Text("📹", fontSize = 18.sp) }
        }
        // Nachrichten
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
            items(messages) { item -> MessageBubble(item) }
        }
        // Eingabeleiste
        Row(Modifier.fillMaxWidth().background(SelfPanel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { imagePicker.launch("image/*") }) { Text("📎", fontSize = 20.sp) }
            IconButton(onClick = {
                if (recording) { val b = recorder.stop(); recording = false; if (b != null) conn.sendMedia("voice", "sprachnachricht.m4a", "audio/mp4", b) }
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (recording) "⏺" else "🎤", fontSize = 20.sp) }
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Nachricht") }, singleLine = true, shape = RoundedCornerShape(22.dp))
            Spacer(Modifier.width(6.dp))
            FilledIconButton(onClick = { if (input.isNotBlank()) { conn.sendText(input.trim()); input = "" } }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = SelfTeal, contentColor = OnTeal)) { Text("➤") }
        }
    }

    // Vollbild-Anruf
    call?.let { c ->
        CallOverlay(c, contact, conn.egl.eglBaseContext, localVideo, remoteVideo,
            onAccept = { withCallPerms(c.video) { conn.acceptCall(); call = c.copy(incoming = false) } },
            onHangup = { conn.hangupCall(); call = null; localVideo = null; remoteVideo = null },
            onMute = { val m = !c.muted; conn.setMicEnabled(!m); call = c.copy(muted = m) },
            onCam = { val o = !c.camOff; conn.setCamEnabled(!o); call = c.copy(camOff = o) })
    }
}

@Composable
private fun MessageBubble(item: ChatItem) {
    val mine = item.fromMe
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(color = if (mine) BubbleSelf else BubbleThem, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 290.dp)) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                when {
                    item.text != null -> Text(item.text, fontSize = 15.sp, color = SelfText)
                    item.media != null -> MediaContent(item.media)
                }
                Text(item.time, fontSize = 10.sp, color = SelfMuted, modifier = Modifier.align(Alignment.End).padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun MediaContent(m: MediaData) {
    when (m.kind) {
        "image" -> {
            val bmp = remember(m) { BitmapFactory.decodeByteArray(m.bytes, 0, m.bytes.size) }
            if (bmp != null) Image(bmp.asImageBitmap(), m.name, Modifier.heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)))
            else Text("🖼 ${m.name}", color = SelfText)
        }
        "voice" -> {
            val ctx = LocalContext.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎤 Sprachnachricht", color = SelfText)
                TextButton(onClick = { VoicePlayer.play(ctx, m.bytes) }) { Text("▶") }
            }
        }
        else -> Text("📎 ${m.name}", color = SelfText)
    }
}

// ================= Anruf (Vollbild) =================

@Composable
private fun CallOverlay(
    call: CallUi, contact: Contact, egl: EglBase.Context, local: VideoTrack?, remote: VideoTrack?,
    onAccept: () -> Unit, onHangup: () -> Unit, onMute: () -> Unit, onCam: () -> Unit
) {
    var secs by remember { mutableStateOf(0) }
    LaunchedEffect(call.incoming) { if (!call.incoming) { secs = 0; while (true) { delay(1000); secs++ } } }
    val stateText = if (call.incoming) "Eingehender Anruf …" else "%02d:%02d".format(secs / 60, secs % 60)

    Box(Modifier.fillMaxSize().background(Color(0xFF05070A))) {
        if (call.video && !call.incoming) {
            VideoRenderer(remote, egl, Modifier.fillMaxSize())
            VideoRenderer(local, egl, Modifier.align(Alignment.TopEnd).padding(16.dp).size(100.dp, 146.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.align(Alignment.TopCenter).padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(contact.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(stateText, color = Color(0xFFE3EBF0), fontSize = 13.sp)
            }
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(contact.name, 118)
                Spacer(Modifier.height(16.dp))
                Text(contact.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(stateText, color = SelfIce, fontSize = 14.sp)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            if (call.incoming) {
                CallCtl("📞", Color(0xFF25D366), onAccept)
            } else {
                CallCtl(if (call.muted) "🔇" else "🎤", Color(0x22FFFFFF), onMute)
                if (call.video) CallCtl("📷", Color(0x22FFFFFF), onCam)
            }
            CallCtl("📞", Color(0xFFE5484D), onHangup, rotate = 135f)
        }
    }
}

@Composable
private fun CallCtl(glyph: String, bg: Color, onClick: () -> Unit, rotate: Float = 0f) {
    Box(Modifier.size(62.dp).clip(CircleShape).background(bg).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 25.sp, color = Color.White, modifier = if (rotate != 0f) Modifier.rotate(rotate) else Modifier)
    }
}

@Composable
private fun VideoRenderer(track: VideoTrack?, egl: EglBase.Context, modifier: Modifier) {
    val ctx = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(ctx) }
    DisposableEffect(Unit) { renderer.init(egl, null); onDispose { renderer.release() } }
    DisposableEffect(track) { track?.addSink(renderer); onDispose { track?.removeSink(renderer) } }
    AndroidView(factory = { renderer }, modifier = modifier)
}

// ================= VPN =================

@Composable
private fun VpnScreen(onPrepareVpn: ((Boolean) -> Unit) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(VpnStore.load(ctx)) }
    val state by VpnManager.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(onBack) { Text("VPN (WireGuard / Mullvad)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Versteckt deine echte IP — beim P2P-Chat sieht der Partner sonst deine Adresse.", fontSize = 13.sp, color = SelfMuted)
            Spacer(Modifier.height(8.dp))
            Text("Status: $state", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (state == Tunnel.State.UP) SelfTeal else SelfMuted)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = config, onValueChange = { config = it }, label = { Text("WireGuard-Konfiguration einfügen (z.B. Mullvad)") }, modifier = Modifier.fillMaxWidth().height(200.dp))
            Spacer(Modifier.height(10.dp))
            Row {
                Button(onClick = { VpnStore.save(ctx, config); onPrepareVpn { ok -> if (ok) scope.launch { VpnManager.up(ctx, config) } } }) { Text("VPN an") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { scope.launch { VpnManager.down(ctx) } }) { Text("VPN aus") }
            }
        }
    }
}

// ================= Helfer =================

private fun shareCode(ctx: android.content.Context, code: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, "Verbinde dich mit mir auf SelfMessenger. Mein Code:\n\n$code")
    }
    ctx.startActivity(android.content.Intent.createChooser(send, "Code teilen"))
}

private fun copyCode(ctx: android.content.Context, code: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("SelfMessenger", code))
    Toast.makeText(ctx, "Code kopiert", Toast.LENGTH_SHORT).show()
}

private fun readPicked(ctx: android.content.Context, uri: Uri): Triple<String, String, ByteArray>? = try {
    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
    var name = "datei"
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) { val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (idx >= 0) name = cur.getString(idx) }
    }
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    Triple(name, mime, bytes)
} catch (e: Exception) { null }
