# SelfMessenger — Deploy & erster echter Test

Der Cloudflare-Kuppler liefert **die Web-App gleich mit aus** — nach dem Deploy gibt es **eine URL** für alles
(App + Signaling + Briefkasten). Vorab per `wrangler deploy --dry-run` geprüft: baut sauber, beide Durable
Objects (ROOMS, MAILBOX) erkannt.

## 1. Kuppler deployen (einmalig, ~5 Min)

In der PowerShell:

```bash
cd "F:\09_Cloude\Github SelfCoder\selfmessenger\signaling"
npx wrangler login
```

→ Browser öffnet sich, mit dem Cloudflare-Konto **Info@selfcoder.de** einloggen und Zugriff erlauben.

```bash
npx wrangler deploy
```

Am Ende zeigt Wrangler die URL, ungefähr:

```
https://selfmessenger-signaling.<deinsubdomain>.workers.dev
```

**Diese URL merken** — das ist die App.

## 2. Web testen (sofort, ohne Installation)

Die URL aus Schritt 1 auf **zwei Geräten** im Browser öffnen (z. B. PC + Handy):

1. Auf beiden einen **Namen** setzen → „Speichern".
2. Freunde verbinden: Gerät A zeigt den **QR-Code**, Gerät B scannt ihn (oder Code kopieren/einfügen).
   Danach umgekehrt, damit sich beide gegenseitig haben.
3. Kontakt **antippen** → ihr seid verschlüsselt verbunden. Text, Bild, Datei, Sprachnachricht, Anruf testen.

> Damit sich beide finden, müssen sie **gleichzeitig** denselben Kontakt öffnen (bei „warte auf …" kurz warten).
> Offline-Nachrichten landen im Briefkasten und kommen, sobald der andere online ist.

## 3. Android-App scharf schalten

In [`Android/selfmessenger-app/app/src/main/java/com/selfmessenger/app/Config.kt`] die URL eintragen
(mit `wss://` und `/ws` am Ende):

```kotlin
const val SIGNALING_URL = "wss://selfmessenger-signaling.<deinsubdomain>.workers.dev/ws"
```

Dann neu bauen + installieren:

```bash
$env:JAVA_HOME="F:\09_Cloude\Github SelfCoder\Android\jdk21"
cd "F:\09_Cloude\Github SelfCoder\Android\selfmessenger-app"
& "F:\09_Cloude\Github SelfCoder\Android\gradle-8.10.2\bin\gradle" :app:assembleDebug --no-daemon
```

APK: `app/build/outputs/apk/debug/app-debug.apk` → aufs Handy kopieren + installieren.

## Falls die eine URL zickt (Notfall-Plan B)

Sollte das kombinierte Ausliefern Probleme machen, hosten wir die Web-App getrennt (GitHub Pages) und hängen die
Kuppler-Adresse per Parameter an: `…/index.html?sig=wss://selfmessenger-signaling.<sub>.workers.dev/ws`.
Der Client unterstützt das bereits (Konstante `SIG_BASE`).

## Bekannte Grenzen beim ersten Test

- **Android ↔ Web** teilen sich noch nicht denselben Nachrichten-Schlüssel (Web nutzt Double Ratchet/olm,
  Android die starke AES-Schicht). Erst **Web↔Web** und **Android↔Android** testen; Cross-Platform kommt danach.
- Anrufe/Mikro/Kamera brauchen echte Geräte-Freigaben (Browser fragt nach Erlaubnis).
