# SelfMessenger — Signaling (Kuppler)

Nur Verbindungsvermittlung. **Sieht nie Inhalte, speichert nichts.** WebSocket-Endpoint `/ws?room=<code>`.
Reicht `offer`/`answer`/`ice` zwischen genau 2 Peers eines Raums durch.

## Produktion (Cloudflare)

```bash
npm i -g wrangler
wrangler login                 # Cloudflare-Konto (Info@selfcoder.de)
wrangler deploy                # -> https://selfmessenger-signaling.<subdomain>.workers.dev
```

Der Web-Client verbindet dann auf `wss://selfmessenger-signaling.<subdomain>.workers.dev/ws?room=<code>`.

## Lokal testen (ohne Cloudflare)

Der Ordner [`../dev`](../dev) enthält denselben Kuppler als Node-Server + den Client zum Ausprobieren:

```bash
cd ../dev && npm install && node server.mjs
# öffnet http://127.0.0.1:8778  — in 2 Tabs mit gleichem Code verbinden
```

## Protokoll

| Nachricht | Richtung | Zweck |
|-----------|----------|-------|
| `{type:'ready',initiator}` | Server→Client | beide da, Rolle vergeben (initiator baut Offer) |
| `{type:'offer'/'answer',sdp}` | Peer↔Peer (durchgereicht) | WebRTC-Verbindungsaufbau |
| `{type:'ice',cand}` | Peer↔Peer (durchgereicht) | Netzwerkpfade (STUN/TURN) |
| `{type:'full'}` / `{type:'peer-left'}` | Server→Client | Raum voll / Partner weg |

**TURN:** In Produktion `iceServers` im Client um Cloudflare-Realtime-TURN ergänzen (Notfall-Relay bei strengen
Firewalls; bleibt E2E-verschlüsselt). Standard streng-P2P bleibt abschaltbar.
