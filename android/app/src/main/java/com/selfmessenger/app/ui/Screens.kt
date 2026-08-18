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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.selfmessenger.app.Config
import com.selfmessenger.app.Contact
import com.selfmessenger.app.Contacts
import com.selfmessenger.app.Identity
import com.selfmessenger.app.AckBus
import com.selfmessenger.app.AppState
import com.selfmessenger.app.MediaFiles
import com.selfmessenger.app.Me
import com.selfmessenger.app.MessageStore
import com.selfmessenger.app.OfflineBus
import com.selfmessenger.app.OfflineMediaBus
import com.selfmessenger.app.PendingRead
import com.selfmessenger.app.Qr
import com.selfmessenger.app.Settings
import com.selfmessenger.app.StoredMsg
import com.selfmessenger.app.net.MailboxPost
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
    var screen by remember { mutableStateOf("chats") }   // chats | mycode | add | settings | vpn
    var chatWith by remember { mutableStateOf<Contact?>(null) }

    // Benachrichtigungs-Erlaubnis (Android 13+) für Push einholen
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    DisposableEffect(Unit) {
        val mbx = MailboxClient(ctx.applicationContext, me, Config.SIGNALING_URL) { from, text ->
            // Offline-Nachricht: in den Verlauf des passenden Kontakts legen (falls Speichern an),
            // an einen ggf. offenen Chat live weiterreichen + Hinweis anzeigen.
            val c = Contacts.all(ctx).firstOrNull { it.name == from }
            if (c != null) {
                if (Settings.saveHistoryFor(ctx, c.pubB64))
                    MessageStore.append(ctx, c.pubB64, StoredMsg(false, text, null, nowTime()))
                OfflineBus.emit(c.pubB64, text)
            }
            Toast.makeText(ctx, "📨 $from: $text", Toast.LENGTH_LONG).show()
        }
        mbx.start(); onDispose { mbx.stop() }
    }

    val target = chatWith
    when {
        target != null -> ChatScreen(me, target) { chatWith = null }
        screen == "mycode" -> MyCodeScreen(me) { screen = "chats" }
        screen == "add" -> AddFriendScreen { screen = "chats" }
        screen == "settings" -> SettingsScreen(onVpn = { screen = "vpn" }) { screen = "chats" }
        screen == "vpn" -> VpnScreen(onPrepareVpn) { screen = "settings" }
        else -> ChatsScreen(me,
            onOpenChat = { chatWith = it },
            onMyCode = { screen = "mycode" },
            onAddFriend = { screen = "add" },
            onSettings = { screen = "settings" })
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
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = SelfText) }
        else Spacer(Modifier.width(10.dp))
        content()
    }
}

// ================= Chats (Start) =================

@Composable
private fun ChatsScreen(me: Me, onOpenChat: (Contact) -> Unit, onMyCode: () -> Unit, onAddFriend: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val contacts = remember { Contacts.all(ctx) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar {
                Box(Modifier.weight(1f)) { Wordmark(19) }
                IconButton(onClick = onMyCode) { Icon(Icons.Filled.Link, "Mein Code", tint = SelfText) }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Einstellungen", tint = SelfText) }
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
        ) { Icon(Icons.Filled.Add, "Freund hinzufügen") }
    }
}

// ================= Einstellungen =================

@Composable
private fun SettingsScreen(onVpn: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var saveHist by remember { mutableStateOf(Settings.saveHistoryGlobal(ctx)) }
    var offlineMbx by remember { mutableStateOf(Settings.offlineMailboxGlobal(ctx)) }
    Column(Modifier.fillMaxSize()) {
        TopBar(onBack) { Text("Einstellungen", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            SectionLabel("Nachrichten")
            ToggleRow("Chatverlauf lokal speichern", "Gespräche bleiben (verschlüsselt) auf dem Gerät.", saveHist) {
                saveHist = it; Settings.setSaveHistoryGlobal(ctx, it)
            }
            ToggleRow("Offline-Nachrichten zwischenlagern", "Wenn der Partner offline ist, wird die Nachricht verschlüsselt beim Kuppler gepuffert.", offlineMbx) {
                offlineMbx = it; Settings.setOfflineMailboxGlobal(ctx, it)
            }
            Text("Standard für neue Chats. Pro Chat lässt sich das im Chat oben über ⋮ überschreiben.",
                color = SelfMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            SectionLabel("Netzwerk")
            NavRow("VPN (WireGuard)", "Eigene IP verstecken (Mullvad-Konfiguration einfügen).", onVpn)

            SectionLabel("Sicherheit")
            Text("Alle Inhalte sind Ende-zu-Ende verschlüsselt und laufen direkt Gerät-zu-Gerät. Der Server sieht nie deine Nachrichten.",
                color = SelfMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = SelfTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = SelfText)
            Text(subtitle, fontSize = 12.sp, color = SelfMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = OnTeal, checkedTrackColor = SelfTeal))
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = SelfText)
            Text(subtitle, fontSize = 12.sp, color = SelfMuted)
        }
        Text("›", fontSize = 22.sp, color = SelfMuted)
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
private data class ChatItem(val fromMe: Boolean, val text: String? = null, val media: MediaData? = null, val time: String = nowTime(), val id: String? = null, val status: String? = null)
private data class CallUi(val video: Boolean, val incoming: Boolean, val connected: Boolean = false, val muted: Boolean = false, val camOff: Boolean = false)

@Composable
private fun ChatScreen(me: Me, contact: Contact, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("verbinde …") }
    var input by remember { mutableStateOf("") }
    var localVideo by remember { mutableStateOf<VideoTrack?>(null) }
    var remoteVideo by remember { mutableStateOf<VideoTrack?>(null) }
    var call by remember { mutableStateOf<CallUi?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    // Verlauf beim Öffnen laden (falls Speichern für diesen Kontakt an ist)
    val messages = remember {
        mutableStateListOf<ChatItem>().also { list ->
            if (Settings.saveHistoryFor(ctx, contact.pubB64))
                MessageStore.load(ctx, contact.pubB64).forEach { sm ->
                    val media = sm.mediaId?.let { mid ->
                        MediaFiles.load(ctx, mid)?.let { b -> MediaData(sm.mediaKind ?: "file", sm.mediaName ?: "Datei", sm.mediaMime ?: "application/octet-stream", b) }
                    }
                    list.add(ChatItem(sm.fromMe, text = if (media == null) (sm.text ?: sm.label) else null, media = media, time = sm.time, id = sm.id, status = sm.status))
                }
        }
    }
    val listState = rememberLazyListState()
    fun persist(item: ChatItem, label: String?) {
        if (!Settings.saveHistoryFor(ctx, contact.pubB64)) return
        val m = item.media
        if (m == null) {
            MessageStore.append(ctx, contact.pubB64, StoredMsg(item.fromMe, item.text, label, item.time, item.id, item.status))
        } else {
            Thread {   // Medien-Bytes auf Platte (überstehen Neustart), außerhalb des UI-Threads
                val mid = MediaFiles.save(ctx, m.bytes)
                MessageStore.append(ctx, contact.pubB64, StoredMsg(item.fromMe, null, label, item.time, item.id, item.status, mid, m.kind, m.name, m.mime))
            }.start()
        }
    }
    fun updateStatus(id: String, s: String) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0 && rankStatus(s) > rankStatus(messages[i].status)) messages[i] = messages[i].copy(status = s)
        MessageStore.updateStatus(ctx, contact.pubB64, id, s)
    }

    val conn = remember {
        Connection(ctx.applicationContext, me, contact, Config.SIGNALING_URL,
            onStatus = { status = it },
            onMessage = { fromMe, text, id -> val item = ChatItem(fromMe, text = text, id = id, status = if (fromMe) "pending" else null); messages.add(item); persist(item, null) })
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
        conn.onMedia = { fromMe, kind, name, mime, bytes, id -> val item = ChatItem(fromMe, media = MediaData(kind, name, mime, bytes), id = id, status = if (fromMe) "pending" else null); messages.add(item); persist(item, mediaLabel(kind, name)) }
        conn.onIncomingCall = { video -> if (call == null) call = CallUi(video = video, incoming = true) else if (video) call = call!!.copy(video = true) }
        conn.onCallConnected = { call = call?.copy(incoming = false, connected = true) }
        conn.onCallEnded = { call = null; localVideo = null; remoteVideo = null }
        conn.onSentStatus = { id, s -> updateStatus(id, s) }
        conn.start()
        // Diesen Chat als „offen" markieren + offene Lesebestätigungen nachsenden
        AppState.openChatPub = contact.pubB64
        val pending = PendingRead.take(contact.pubB64)
        if (pending.isNotEmpty()) Thread { pending.forEach { MailboxPost.sendAck(me, Config.SIGNALING_URL, contact.pubB64, it, "r") } }.start()
        onDispose { if (AppState.openChatPub == contact.pubB64) AppState.openChatPub = null; conn.close() }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    // Offline zugestellte Nachricht live einblenden, wenn dieser Chat gerade offen ist
    // (persistiert wird sie bereits app-global in MainNav).
    LaunchedEffect(contact.pubB64) {
        OfflineBus.events.collect { (pub, text) -> if (pub == contact.pubB64) messages.add(ChatItem(false, text = text)) }
    }
    // Status-Haken aus Offline-Bestätigungen (zugestellt/gelesen) aktualisieren
    LaunchedEffect(contact.pubB64) {
        AckBus.events.collect { (pub, id, s) -> if (pub == contact.pubB64) updateStatus(id, s) }
    }
    // Offline empfangenes Bild/Medium live einblenden (persistiert wurde es bereits in MainNav/MailboxClient)
    LaunchedEffect(contact.pubB64) {
        OfflineMediaBus.events.collect { om -> if (om.pub == contact.pubB64) messages.add(ChatItem(false, media = MediaData(om.kind, om.name, om.mime, om.bytes))) }
    }

    Column(Modifier.fillMaxSize()) {
        // Kopfzeile
        TopBar(onBack) {
            Avatar(contact.name, 38); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(contact.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SelfText, maxLines = 1)
                Text(status, fontSize = 11.sp, color = SelfMuted, maxLines = 1)
            }
            IconButton(onClick = { withCallPerms(false) { call = CallUi(false, false); conn.startCall(false) } }) { Icon(Icons.Filled.Call, "Anrufen", tint = SelfText) }
            IconButton(onClick = { withCallPerms(true) { call = CallUi(true, false); conn.startCall(true) } }) { Icon(Icons.Filled.Videocam, "Videoanruf", tint = SelfText) }
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, "Menü", tint = SelfText) }
        }
        if (showMenu) ContactSettingsDialog(contact, onClearHistory = { MessageStore.clear(ctx, contact.pubB64); messages.clear() }) { showMenu = false }
        // Nachrichten — neueste immer unten (auch bei wenigen Nachrichten)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.Bottom) {
            items(messages) { item -> MessageBubble(item) }
        }
        // Eingabeleiste
        Row(Modifier.fillMaxWidth().background(SelfPanel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { imagePicker.launch("image/*") }) { Icon(Icons.Filled.AttachFile, "Bild/Datei", tint = SelfMuted) }
            IconButton(onClick = {
                if (recording) { val b = recorder.stop(); recording = false; if (b != null) conn.sendMedia("voice", "sprachnachricht.m4a", "audio/mp4", b) }
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, "Sprachnachricht", tint = if (recording) Color(0xFFE5484D) else SelfMuted) }
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Nachricht") }, singleLine = true, shape = RoundedCornerShape(22.dp))
            Spacer(Modifier.width(6.dp))
            FilledIconButton(onClick = { if (input.isNotBlank()) { conn.sendText(input.trim()); input = "" } }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = SelfTeal, contentColor = OnTeal)) { Icon(Icons.AutoMirrored.Filled.Send, "Senden") }
        }
    }

    // Vollbild-Anruf
    call?.let { c ->
        CallOverlay(c, contact, conn.egl.eglBaseContext, localVideo, remoteVideo,
            onAccept = { withCallPerms(c.video) { conn.acceptCall(); call = c.copy(incoming = false, connected = true) } },
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
                Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.time, fontSize = 10.sp, color = SelfMuted)
                    if (mine && item.status != null) {
                        Spacer(Modifier.width(3.dp))
                        Text(tickGlyph(item.status), fontSize = 10.sp, color = if (item.status == "read") Color(0xFF53BDEB) else SelfMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaContent(m: MediaData) {
    val ctx = LocalContext.current
    when (m.kind) {
        "image" -> {
            val bmp = remember(m) { BitmapFactory.decodeByteArray(m.bytes, 0, m.bytes.size) }
            var showFull by remember { mutableStateOf(false) }
            Column {
                if (bmp != null) Image(bmp.asImageBitmap(), m.name, Modifier.heightIn(max = 240.dp).clip(RoundedCornerShape(8.dp)).clickable { showFull = true })
                else Text("🖼 ${m.name}", color = SelfText)
                if (showFull && bmp != null) Dialog(onDismissRequest = { showFull = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Box(Modifier.fillMaxSize().background(Color(0xF2000000)).clickable { showFull = false }, contentAlignment = Alignment.Center) {
                        Image(bmp.asImageBitmap(), m.name, Modifier.fillMaxWidth())
                    }
                }
                TextButton(
                    onClick = {
                        val ok = MediaFiles.saveImageToGallery(ctx, m.bytes, m.name, m.mime)
                        Toast.makeText(ctx, if (ok) "Bild in Galerie gespeichert" else "Speichern fehlgeschlagen", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                ) { Text("⬇ Speichern", fontSize = 12.sp, color = SelfTeal) }
            }
        }
        "voice" -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎤 Sprachnachricht", color = SelfText)
                TextButton(onClick = { VoicePlayer.play(ctx, m.bytes) }) { Text("▶") }
            }
        }
        else -> Text("📎 ${m.name}", color = SelfText)
    }
}

private fun mediaLabel(kind: String, name: String): String = when (kind) {
    "image" -> "🖼 Bild"
    "voice" -> "🎤 Sprachnachricht"
    else -> "📎 $name"
}

private fun tickGlyph(status: String?): String = when (status) {
    "pending" -> "🕓"; "sent" -> "✓"; "delivered", "read" -> "✓✓"; "fail" -> "⚠"; else -> ""
}
private fun rankStatus(status: String?): Int = when (status) {
    "sent" -> 1; "delivered" -> 2; "read" -> 3; else -> 0
}

@Composable
private fun ContactSettingsDialog(contact: Contact, onClearHistory: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var hist by remember { mutableStateOf(Settings.saveHistoryFor(ctx, contact.pubB64)) }
    var mbx by remember { mutableStateOf(Settings.offlineMailboxFor(ctx, contact.pubB64)) }
    val hasOverride = Settings.historyOverride(ctx, contact.pubB64) != null || Settings.mailboxOverride(ctx, contact.pubB64) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
        dismissButton = {
            TextButton(onClick = {
                Settings.setHistoryOverride(ctx, contact.pubB64, null); Settings.setMailboxOverride(ctx, contact.pubB64, null)
                hist = Settings.saveHistoryFor(ctx, contact.pubB64); mbx = Settings.offlineMailboxFor(ctx, contact.pubB64)
            }, enabled = hasOverride) { Text("Auf Standard") }
        },
        title = { Text(contact.name) },
        text = {
            Column {
                Text("Nur für diesen Chat (überschreibt die globale Einstellung).", color = SelfMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chatverlauf speichern", Modifier.weight(1f), color = SelfText)
                    Switch(checked = hist, onCheckedChange = { hist = it; Settings.setHistoryOverride(ctx, contact.pubB64, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = OnTeal, checkedTrackColor = SelfTeal))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Offline zwischenlagern", Modifier.weight(1f), color = SelfText)
                    Switch(checked = mbx, onCheckedChange = { mbx = it; Settings.setMailboxOverride(ctx, contact.pubB64, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = OnTeal, checkedTrackColor = SelfTeal))
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onClearHistory() }, contentPadding = PaddingValues(0.dp)) {
                    Text("🗑 Verlauf dieses Chats löschen", color = Color(0xFFE5484D))
                }
            }
        }
    )
}

// ================= Anruf (Vollbild) =================

@Composable
private fun CallOverlay(
    call: CallUi, contact: Contact, egl: EglBase.Context, local: VideoTrack?, remote: VideoTrack?,
    onAccept: () -> Unit, onHangup: () -> Unit, onMute: () -> Unit, onCam: () -> Unit
) {
    var secs by remember { mutableStateOf(0) }
    LaunchedEffect(call.connected) { if (call.connected) { secs = 0; while (true) { delay(1000); secs++ } } }
    val stateText = when {
        call.incoming -> "Eingehender Anruf …"
        !call.connected -> "ruft an …"
        else -> "%02d:%02d".format(secs / 60, secs % 60)
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF05070A))) {
        if (call.video && call.connected) {
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
                CallCtl(Icons.Filled.Call, Color(0xFF25D366), onAccept, "Annehmen")
            } else {
                CallCtl(if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic, Color(0x22FFFFFF), onMute, "Stumm")
                if (call.video) CallCtl(if (call.camOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam, Color(0x22FFFFFF), onCam, "Kamera")
            }
            CallCtl(Icons.Filled.CallEnd, Color(0xFFE5484D), onHangup, "Auflegen")
        }
    }
}

@Composable
private fun CallCtl(icon: ImageVector, bg: Color, onClick: () -> Unit, desc: String = "") {
    Box(Modifier.size(62.dp).clip(CircleShape).background(bg).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(26.dp))
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
