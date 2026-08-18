# SelfMessenger — Plan (Arbeitstitel)

> Serverloser, Ende-zu-Ende-verschlüsselter P2P-Messenger.
> Nachrichten gehen **direkt von Gerät zu Gerät** — kein Server sieht je einen Inhalt.
> Stand: Planung abgeschlossen, Bau beginnt mit Phase 1. Datum: 2026-08-17.

## Kernanforderungen (vom Nutzer, verbindlich)

1. **Kein Nachrichten-Server** — Text/Medien/Anrufe laufen P2P (Gerät ↔ Gerät). Ein Server hilft nur beim
   Verbindungsaufbau (Signaling) und sieht niemals Inhalte.
2. **Höchste Verschlüsselung, die es gibt** — Signal-Protokoll (Double Ratchet + X3DH) für Nachrichten,
   DTLS-SRTP (AES) für Anrufe. **Krypto wird NIE selbst gebaut** — nur auditierte Bibliotheken.
3. **App-Zugang mit 3 Sicherheitschecks** — PIN, Fingerabdruck, Gesichtserkennung.
4. **Gerät-Verschlüsselung „falls verloren"** — alle App-Daten verschlüsselt at-rest; bei Geräteverlust unlesbar.
5. **Funktionen** — Text, Bilder/Dateien, Sprachnachrichten, Telefonie, Videotelefonie.
6. **Verbinden nur „von Angesicht zu Angesicht"** — Freundschaft NUR per NFC (physische Nähe = echter Mensch).
   QR nur als Rückfall (Web kann kein NFC).
7. **VPN-Option in der App** — WireGuard/Mullvad, um die echte IP zu verstecken (bei P2P sieht der Partner sonst
   die echte IP). Wiederverwendung von [[project-selfwg]] (WireGuard-Client). Optional zuschaltbar.

## Klarstellung Server-Rolle (verbindlich)

Der Server (Cloudflare Worker) ist **ausschließlich Kuppler/Signaling**: er tauscht beim Verbindungsaufbau
Adress-/Schlüssel-Zettel aus und ist danach raus. Er transportiert **niemals** Nachrichten, Bilder oder Anrufe
und speichert keine Inhalte. Einzige Grauzone: **TURN** (Notfall-Relay bei strengen Firewalls) — dabei fließen
Daten zwar über einen Relay, aber **E2E-verschlüsselt** (Relay sieht nur Datensalat). TURN ist **abschaltbar**
(streng-P2P-Modus).

### WICHTIG: VPN ≠ Verbindungs-Retter (Korrektur einer Annahme)
- **Mullvad & kommerzielle Privatsphäre-VPNs**: verstecken die IP (gut!), aber geteilter Exit + **keine
  Port-Weiterleitung mehr (Mullvad seit 2023 abgeschafft)** → Direkt-P2P wird **schwerer**. Zwei Peers beide
  hinter Mullvad verbinden sich meist NICHT direkt → Relay wird häufiger nötig, nicht seltener.
- **Eigener WireGuard-Mesh** (SelfWG-Server): feste private Adressen → Direktverbindung klappt zuverlässig,
  könnte den Kuppler teils ersetzen. Aber: eigener WG-Server ist „ein Server" (leitet nur verschlüsselte
  VPN-Pakete, liest nichts).
- **Entscheidung (Nutzer, 2026-08-17):** VPN eingebaut (aus SelfWG). Da Mullvad P2P nicht rettet →
  verschlüsseltes Relay als Sicherheitsnetz behalten (liest nie mit, erfüllt „kein lesbarer Server").
  Mullvad = reines Privatsphäre-Feature. Optional später: eigener WG-Mesh = echtes Immer-Direkt.

## Plattformen

- **Web** (PWA, installierbar) — schnellstes Feedback, WebRTC eingebaut.
- **Android** (nativ Kotlin + Compose, KEIN WebView) — NFC, Biometrie, Hintergrund-Anrufe nur nativ möglich.
- **iOS**: bewusst außen vor (sperrt NFC + Hintergrund-P2P stark).

## Architektur

```
   Gerät A  ◄──────── direkt, E2E-verschlüsselt ─────────►  Gerät B
      │        (Text, Bilder, Sprache, Telefonie, Video)        │
      │                                                          │
      └───────►  Cloudflare Worker  ◄───────────────────────────┘
                 (nur Verbindungsaufbau + TURN-Notfall,
                  sieht NIE einen Inhalt, speichert keine Nachrichten)
```

### Technische Entscheidungen

| Baustein            | Wahl                                             | Begründung |
|---------------------|--------------------------------------------------|------------|
| Nachrichten-Krypto  | **Signal Double Ratchet + X3DH**                 | Höchster Standard, auditiert (Signal, WhatsApp). |
| Krypto-Umsetzung    | **Ein Rust-Kern → WASM (Web) + native lib (Android)** | Beide Seiten byte-kompatibel; kein Eigenbau. Kandidaten: libsignal / vodozemac. |
| Anruf-Krypto        | **WebRTC DTLS-SRTP (AES)**                        | Bei WebRTC Pflicht; in P2P echtes E2E (nur die 2 Geräte haben Schlüssel). |
| Transport           | **WebRTC** (DataChannel + Media)                 | Direktverbindung; Anrufe/Video sind Kern-Feature. |
| Signaling           | **Cloudflare Worker + Durable Object** (WebSocket)| Immer online, unabhängig von Unraid, kostenloses Kontingent reicht. |
| TURN (Notfall)      | **Cloudflare Realtime TURN**                     | Für ~15 % strenge Firewalls; kein eigener Relay nötig. |
| Identität           | Schlüsselpaar pro Gerät, **kein Konto/Handynr.** | Verlässt nie das Gerät. |
| Web-Stack           | TypeScript + Vite, PWA                            | Ein Codestamm, schnelles Feedback. |
| Android-Stack       | Kotlin + Compose, WebRTC-Android-SDK             | Passt zu bestehendem Setup. |

## Sicherheitsmodell

### Zugang zur App (3 Checks)
- **Android**: `BiometricPrompt` (Fingerabdruck + Gesicht) + Geräte-PIN als Fallback.
  Entsperrt einen im Keystore hinterlegten Schlüssel, der die lokale DB aufschließt.
- **Web**: WebAuthn (Plattform-Authenticator = Fingerabdruck/Gesicht wo verfügbar) + Passphrase-Fallback.

### At-rest (Geräteverlust)
- **Android Keystore / StrongBox** hält den Master-Key hardware-gebunden (nicht exportierbar).
- Lokale Nachrichten-DB **verschlüsselt** (SQLCipher o. ä.).
- Identitäts-/Sitzungsschlüssel nicht exportierbar.
- Ergebnis: Handy weg → Daten unlesbar, Biometrie-Sperre blockiert Zugriff.

### Freundschaft (nur physisch)
1. **NFC-Antippen** tauscht die öffentlichen Identitätsschlüssel direkt aus (Android: Host Card Emulation).
   QR-Scan als Rückfall/Web.
2. Damit ist der Schlüssel **persönlich verifiziert** → Man-in-the-Middle technisch unmöglich.
3. Später online wiederfinden: jedes Gerät meldet beim Worker einen **anonymen Fingerabdruck** seines
   Schlüssels als „Briefkasten". Nur wer den Fingerabdruck kennt (= persönlich getauscht) kann anfunken.
   Der Worker leitet nur verschlüsselte Verbindungs-Zettel blind weiter.

## Bau-Phasen (jede mit Verifikation)

| Phase | Inhalt | Verifikation (fertig wenn…) |
|-------|--------|------------------------------|
| **1 — Herzstück** ✅ | Identitäts-Schlüssel + Pairing + verschlüsselte Sitzung | ✅ verifiziert: Prototyp `prototype/phase1-pairing-spike.html`, Kopplung + AES-GCM im Browser getestet |
| **2 — Verbindung** ✅* | Cloudflare-Worker + WebRTC + Textchat | ✅ Mechanik lokal verifiziert (2 Tabs, Signaling→WebRTC→E2E bidirektional). *Offen: Cloudflare-Deploy + echter Cross-Network-Test |
| **3 — Medien** ✅* | Bilder, Dateien, Sprachnachrichten (verschlüsselt, gestückelt) | ✅ verifiziert: Bild 60KB & Datei bit-identisch übertragen (Hash-Vergleich), Chunk-Reassembly ok. *Mikro-Aufnahme nur auf echtem Gerät testbar |
| **4 — Anrufe** ✅* | Audio, dann Video (WebRTC + DTLS-SRTP) | ✅ verifiziert: synthetischer Video-Anruf, B empfängt audio+video (inbound-rtp: Bytes fließen), Perfect-Negotiation mid-chat, kein Text-Regress. *Live Mikro/Kamera nur auf echtem Gerät |
| **5 — Android** 🔨 | Native App, gleiches Protokoll, **NFC-Pairing** + Biometrie + verschlüsselte DB + Anrufe | Slice 1 ✅ BAUT: Biometrie-Sperre + verschl. Identität + QR-Pairing + Kontakte. Slice 2 ✅ BAUT: Signaling + WebRTC + Krypto (web-kompatibel) + Chat-UI. Slice 3 ✅ BAUT: **NFC-Pairing** (HCE). Slice 4 ✅ BAUT: **Audio/Video-Anrufe**. Slice 6 ✅ BAUT: **Medien** (Bild/Datei/**Sprachnachricht** senden+empfangen, chunked+AES). Slice 7 ✅ BAUT: **Offline-Zustellung** (MailboxClient app-weit + Offline-Senden) (`Android/selfmessenger-app`). **Android = volle Web-Parität (Compile).** Offen: Double Ratchet nativ (olm-Blocker), Android↔Web-Krypto-Interop, Cloudflare-URL + echter 2-Geräte-Test |
| **6 — VPN** ✅ | WireGuard-Integration (Mullvad + eigenes), IP-Verschleierung, zuschaltbar; Basis = [[project-selfwg]] | ✅ BAUT (Android): VpnManager/SelfTunnel (GoBackend an/aus), VpnStore (Konfig verschl.), VpnScreen, Manifest-Dienst + Consent. Native libwg-go.so eingepackt. Runtime = echtes Gerät |
| **7 — Feinschliff** 🔨 | Offline-Zustellung (versiegelte Blobs), Push-Benachrichtigungen | Offline-Zustellung ✅ Web verifiziert: Briefkasten im Kuppler (anonyme verschlüsselte Blobs), sofort- + zwischengelagert-dann-zugestellt getestet; Probe-Entschlüsselung findet Absender. Offen: Android-Port, Push |

### Kern-Härtung ✅ (2026-08-17, Web verifiziert)
Feste **Identität pro Gerät** (persistent, localStorage), **persönliches Pairing** per QR-Code (Payload =
Name + Identitäts-Pubkey; NFC nutzt später denselben Payload), **Kontaktliste** mit Fingerabdruck.
Der Signaling-**Rendezvous-Punkt** wird aus den *persönlich getauschten* Schlüsseln abgeleitet
(`SHA-256(sort(pubA,pubB))`) → **nur wer sich persönlich getroffen hat, kann dich erreichen**.
Sitzungsschlüssel = ECDH der beiden Identitäten → **MITM-unmöglich**. Verifiziert: 2 Profile (anna/ben)
tauschen Codes, gleicher Rendezvous, gleiche Sicherheitsnummer, Chat, Persistenz nach Reload.

### Double Ratchet ✅ (2026-08-17, Web verifiziert)
**Signal Double Ratchet** über auditierte **libolm** (Matrix, v3.2.15, WASM in `web/olm.js`+`web/olm.wasm`) —
kein Eigenbau-Krypto. Ablauf: identitäts-ECDH-Kanal bootstrappt → darüber (AES-verpackt = **authentifiziert**,
MITM-sicher) läuft der olm-Handshake (Identity+One-Time-Key → create_outbound/create_inbound) → danach ALLE
Inhalte (Text + Medien-Häppchen) über den Ratchet. Ergebnis: **Forward Secrecy + Self-Healing + MITM-sicher**.
Verifiziert: 3 Texte + 40KB-Bild bidirektional über Ratchet, Hash identisch, Sicherheitsnummer stabil.
**Offen:** echtes NFC + QR-Scan-Kamera (Android); olm-Session-Persistenz (aktuell frisch pro Verbindung = max. FS);
Media/Calls: Calls weiter DTLS-SRTP (Echtzeit, nicht über Ratchet).

## Bekannte Risiken / offene Entscheidungen

- **Anrufe bei geschlossener App (Android):** „Klingeln" braucht i. d. R. Push-Weckruf (Googles FCM).
  FCM sieht **keinen** Inhalt, ist aber Google-Infra. Alternative: App muss offen sein. → Entscheidung in Phase 5.
- **NFC Handy↔Handy:** Android Beam ist tot; geht heute nur über Host Card Emulation (fummelig). Deshalb QR als Basis.
- **Dev-Umgebung:** `next/vite dev` läuft NICHT vom Netzlaufwerk F: → lokal spiegeln, Repo auf F:/GitHub halten.
- **Git auf F::** SMB-Ownership-Problem bekannt → ggf. `.git/objects`-Rechte auf Unraid richten.

## Nächster Schritt

Phase 1: Web-Prototyp — Pairing + verschlüsselte Sitzung, im Browser verifizierbar.
