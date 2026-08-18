<p align="center"><img src="web/brand/logo.png" alt="SelfMessenger" width="220" /></p>

# SelfMessenger

**DE** — Serverloser, Ende-zu-Ende-verschlüsselter Peer-to-Peer-Messenger. Nachrichten, Bilder,
Sprachnachrichten und Anrufe gehen **direkt von Gerät zu Gerät** — kein Server sieht je einen Inhalt.
Freundschaften entstehen nur „von Angesicht zu Angesicht" per NFC. Höchste Verschlüsselung
(Signal-Protokoll). App durch PIN, Fingerabdruck und Gesichtserkennung geschützt; alle Daten auf dem
Gerät verschlüsselt gespeichert.

**EN** — Serverless, end-to-end-encrypted peer-to-peer messenger. Messages, images, voice notes and
calls travel **directly device-to-device** — no server ever sees content. Friending happens only
face-to-face via NFC. Strongest available encryption (Signal protocol). App gated by PIN, fingerprint
and face; all on-device data encrypted at rest.

## Status

Planung abgeschlossen — Bau beginnt mit Phase 1. Siehe [PLAN.md](PLAN.md).

## Architektur (Kurzform)

- **Transport:** WebRTC (DataChannel + Media), direkt P2P
- **Krypto:** Signal Double Ratchet + X3DH (Nachrichten), DTLS-SRTP (Anrufe)
- **Signaling:** Cloudflare Worker (nur Verbindungsaufbau, blind) + Cloudflare TURN als Notfall
- **Web:** TypeScript + Vite (PWA) · **Android:** Kotlin + Compose (nativ)

## Lizenz

TBD.
