# SelfMessenger TURN-Relay (kostenlos, selbstgehostet)

Ohne TURN scheitert die direkte P2P-Verbindung oft, wenn beide Seiten hinter NAT sitzen
(z. B. PC im WLAN ↔ Handy im Mobilfunk). Ein TURN-Relay leitet dann den **bereits
Ende-zu-Ende verschlüsselten** Strom weiter — der Relay sieht nur Datensalat.

Dieses Paket startet `coturn` als Container auf deinem eigenen Server (z. B. Unraid) und
bindet ihn an den bestehenden Cloudflare-Worker an. **Kein Cent, kein App-Update** — die
Clients holen die TURN-Zugangsdaten dynamisch über `/turn`.

---

## 1. Geheimnis erzeugen

```bash
openssl rand -hex 32
```

Das Ergebnis ist dein `TURN_SECRET`. Es kennt nur der Server (coturn) und der Worker —
niemals ein Client.

## 2. Konfiguration füllen

In [`turnserver.conf`](turnserver.conf) zwei Platzhalter ersetzen:

| Platzhalter | Wert |
|-------------|------|
| `__REPLACE_SECRET__` | das Geheimnis aus Schritt 1 |
| `__PUBLIC_IP__` | deine öffentliche IP (z. B. `dig +short myip.opendns.com @resolver1.opendns.com`) |

## 3. Container starten (Unraid)

Datei nach `/mnt/user/appdata/selfmessenger-turn/turnserver.conf` legen, dann per
Docker-Compose-Plugin die [`docker-compose.yml`](docker-compose.yml) starten — oder direkt:

```bash
docker run -d --name selfmessenger-turn --network host --restart unless-stopped \
  -v /mnt/user/appdata/selfmessenger-turn/turnserver.conf:/etc/coturn/turnserver.conf:ro \
  coturn/coturn:latest -c /etc/coturn/turnserver.conf
```

## 4. Router: UDP-Ports auf den Unraid-Host weiterleiten

| Port(s) | Protokoll | Zweck |
|---------|-----------|-------|
| `3478` | UDP + TCP | TURN-Steuerkanal |
| `49160`–`49200` | UDP | Relay-Medienports (siehe `min-port`/`max-port`) |

## 5. Worker anbinden (zwei Secrets)

Im Ordner `signaling/`:

```bash
npx wrangler secret put TURN_URL
# Eingabe:  turn:DEINE_IP:3478?transport=udp,turn:DEINE_IP:3478?transport=tcp

npx wrangler secret put TURN_SECRET
# Eingabe:  dasselbe Geheimnis wie static-auth-secret
```

Der Worker deployt sich danach automatisch neu; ohne diese Secrets bleibt es bei STUN
(kein Regress).

## 6. Prüfen

```bash
curl -s https://selfmessenger-signaling.s3lfcod3r.workers.dev/turn
```

Jetzt muss neben STUN ein `turn:`-Eintrag mit `username` (Ablauf-Zeitstempel) und
`credential` erscheinen. Ein echter Relay-Test geht am schnellsten über
<https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/> — die TURN-URL
+ username/credential von oben eintragen; ein `relay`-Kandidat bestätigt, dass es läuft.

---

**Sicherheit:** Die Konfiguration verweigert Relays in private/Loopback/Metadaten-Bereiche,
damit dein TURN nicht als Innen-Proxy in dein LAN missbraucht werden kann. Zugangsdaten
sind kurzlebig (24 h) und werden serverseitig aus dem Geheimnis gemintet.

---

## Alternative: gehostetes TURN (kein eigener Server)

Wer keinen eigenen Relay betreiben will, kann ein kostenloses gehostetes TURN nutzen
(z. B. ExpressTURN-Free: 1000 GB/Monat, Port 3478 UDP/TCP + 80/443). Der Relay sieht
weiterhin nur verschlüsselten Verkehr und speichert nichts. Man legt ein Konto an und
bekommt **feste Zugangsdaten** (Benutzer + Passwort). Diese als drei Worker-Secrets setzen:

```bash
npx wrangler secret put TURN_URL    # z. B. turn:relay.expressturn.com:3478?transport=udp,turn:relay.expressturn.com:3478?transport=tcp
npx wrangler secret put TURN_USER   # Benutzername vom Anbieter
npx wrangler secret put TURN_PASS   # Passwort vom Anbieter
```

Der Worker liefert diese festen Zugangsdaten dann über `/turn` aus (Modus 1b). Ohne die
Secrets bleibt es bei STUN. Genau wie beim eigenen coturn ist **kein App-/Web-Update** nötig.
