<p align="center"><img src="web/brand/logo.png" alt="SelfMessenger" width="220" /></p>

<h1 align="center">SelfMessenger</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Status-Live-33a78c?style=flat-square" alt="Status" />
  <img src="https://img.shields.io/badge/Serverless-33a78c?style=flat-square" alt="Serverless" />
  <img src="https://img.shields.io/badge/End--to--End%20Encrypted-33a78c?style=flat-square" alt="E2E" />
  <img src="https://img.shields.io/badge/Web%20%2B%20Android-33a78c?style=flat-square" alt="Web + Android" />
</p>

**DE** — Serverloser, Ende-zu-Ende-verschlüsselter Peer-to-Peer-Messenger. Nachrichten, Bilder,
Sprachnachrichten und Anrufe gehen **direkt von Gerät zu Gerät** — kein Server sieht je einen Inhalt.
Freundschaften entstehen nur „von Angesicht zu Angesicht" per NFC oder QR. Läuft im Browser **und** als
native Android-App.

**EN** — Serverless, end-to-end-encrypted peer-to-peer messenger. Messages, images, voice notes and
calls travel **directly device-to-device** — no server ever sees content. Friending happens only
face-to-face via NFC or QR. Runs in the browser **and** as a native Android app.

---

## ✨ Funktionen / Features

- 💬 **Text, Bilder, Sprachnachrichten** — direkt P2P über WebRTC
- 📞 **Sprach- & Videoanrufe** im WhatsApp-Stil (Vollbild, Annehmen/Stumm/Kamera/Auflegen)
- 📨 **Offline-Zustellung** — Text **und Bilder** erreichen auch offline Kontakte (verschlüsselt im „Briefkasten" zwischengelagert, dann zugestellt)
- ✓✓ **Status-Haken** — gesendet · zugestellt · gelesen (blau), auch für Bilder
- 🔔 **Push & Anruf-Klingelschirm** — Benachrichtigung bei geschlossener App; eingehender Anruf poppt als Vollbild über dem Sperrbildschirm auf (Android)
- 🤝 **Pairing nur persönlich** — per NFC (Handy an Handy) oder QR-Code
- 🔒 **App-Sperre** (PIN / Fingerabdruck / Gesicht) und verschlüsselte Speicherung auf dem Gerät (Android)
- 🛡️ **VPN-Option** (WireGuard) zum Verstecken der eigenen IP
- ⚙️ **Einstellungen** — Chatverlauf speichern & Offline-Zwischenlagern, global und pro Kontakt

## 🚀 Nutzen / Get it

- **Web:** <https://selfmessenger-signaling.s3lfcod3r.workers.dev> — einfach öffnen (Desktop im Breitformat, Handy im Hochformat)
- **Android-App:** über [Releases](https://github.com/s3lfcod3r/selfmessenger/releases/latest) oder den [SelfStore](https://github.com/s3lfcod3r/selfstore)

## 🔐 Sicherheitsmodell / Security model

- **Identitäts-authentifizierter Kanal:** aus dem persönlichen Pairing wird per **ECDH (P-256) → AES-256-GCM** eine gemeinsame Sitzung abgeleitet. Weil die Schlüssel „von Angesicht zu Angesicht" getauscht werden, ist ein Man-in-the-Middle ausgeschlossen (Sicherheitsnummer zum Abgleich).
- **Forward Secrecy (Web):** darüber läuft der **Signal Double Ratchet** (auditierte `libolm`); bricht er auf einem Gerät nicht, bleibt der AES-Kanal aktiv.
- **Anrufe:** WebRTC-Pflichtverschlüsselung (**DTLS-SRTP**).
- **Server sieht nichts:** Der Cloudflare-Worker ist reiner **Kuppler + anonymer Briefkasten** — er transportiert nur verschlüsselte Blobs und weiß nie, wer mit wem was schreibt. Push-Benachrichtigungen sind inhaltslos („neue Nachricht" / „eingehender Anruf").
- **At-rest (Android):** Identität, Kontakte, Einstellungen und Chatverlauf liegen verschlüsselt (Android Keystore / EncryptedSharedPreferences).

## 🏗️ Architektur / Architecture

- **Transport:** WebRTC (DataChannel + Media), direkt P2P; STUN für den Verbindungsaufbau
- **Signaling & Briefkasten:** Cloudflare Worker + Durable Objects (blind, SQLite-basiert)
- **Push:** Firebase Cloud Messaging (nur Weck-Signal, kein Inhalt)
- **Web:** HTML/JS (PWA), vom Worker mit ausgeliefert
- **Android:** Kotlin + Jetpack Compose (nativ, kein WebView)

```
web/        Web-App (wird vom Worker ausgeliefert)
signaling/  Cloudflare Worker (Kuppler + Briefkasten + Push)
android/    Native Android-App (Kotlin/Compose)
```

## 🛠️ Selbst hosten / Self-host the relay

Der Kuppler ist ein einzelner Cloudflare Worker:

```bash
cd signaling
npx wrangler deploy
```

Danach die Live-URL in der Android-App unter `Config.SIGNALING_URL` eintragen (die Web-App wird vom
Worker selbst ausgeliefert). Für Push optional ein Firebase-Dienstkonto als Worker-Secret `FCM_SA`
hinterlegen.

## 📄 Lizenz / License

Privates Projekt. Alle Rechte vorbehalten. / Private project, all rights reserved.
