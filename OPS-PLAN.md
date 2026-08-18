# SelfMessenger — Betriebs- & Rechts-Plan

> Was wir brauchen, um live zu gehen — und wie, ohne rechtliche Probleme, wenn andere es nutzen.
> **Hinweis: Ich bin kein Anwalt. Der Rechts-Teil ist allgemeine Orientierung, kein Rechtsrat.**
> Für alles über Freundes-/Familienkreis hinaus einen IT-/Medien-Anwalt fragen.

---

## 1. APK-Verteilung: eigenes Repo + SelfStore

**Ziel:** SelfMessenger als Self-App im SelfStore, APK per GitHub-Release (wie deine anderen Apps).

Schritte:
1. **Repo anlegen:** `s3lfcod3r/selfmessenger` (Konto s3lfcod3r, wie alle Self-Repos).
   Inhalt: `web/`, `signaling/`, `dev/`, Android-Projekt, README (2-sprachig, mit Logo).
2. **Release-Keystore erzeugen** (EINMALIG, KRITISCH für alle künftigen Updates — wie bei SelfMailer/SelfStore):
   eigener `selfmessenger-release.jks`. **Sicher aufbewahren** — verlierst du ihn, kannst du keine Updates
   mehr signieren. Debug-Keystore ist nur für Tests.
3. **APK bauen & signieren** (Release), dann **GitHub-Release** mit der APK anhängen.
4. **SelfStore-Katalogeintrag:** neue App in den SelfStore-Katalog (DE+EN-Beschreibung, `applicationId`
   `com.selfmessenger.app`, `source` = das Release, Icon = `brand/`-Logo). SelfStore synct automatisch
   (15-Min-Cron + Instant bei Release über `SELFSTORE_SYNC_TOKEN`).
5. **Profil-README** (s3lfcod3r) um SelfMessenger ergänzen (EN+DE), Original-Logo.

> Play Protect meldet Self-APKs oft als „schädlich" (False-Positive) → „Trotzdem installieren".

---

## 2. Cloudflare (Kuppler): Limits & Kosten

Der Kuppler macht fast nichts — nur Verbindungsaufbau + kurz Blobs zwischenlagern. **Sehr wenig Traffic.**

| Was | Gratis-Tarif | Reicht für uns? |
|-----|--------------|-----------------|
| Worker-Anfragen | **100.000 / Tag** | Ja, mit Riesen-Abstand. Pro Gespräch nur eine Handvoll Anfragen. |
| Durable Objects (Räume/Briefkasten) | **gratis mit SQLite-Speicher** (haben wir umgestellt) | Ja, unser Verbrauch ist winzig. |
| WebSocket-Verbindungen | via DO-„Hibernation" günstig | Ja. |
| TURN-Notfall-Relay (falls später) | Cloudflare-Realtime-TURN hat Gratis-Kontingent, dann bezahlt | Erst relevant bei Anrufen durch strenge Firewalls. |

**Fazit:** Für Freunde/Familie **kostenlos**. Erst bei hunderten aktiven Nutzern über den Workers-Paid-Plan
($5/Monat, 10 Mio Anfragen inkl.) nachdenken. Beim ersten Deploy schauen wir gemeinsam auf das Dashboard, ob
der Free-Plan die Durable Objects freischaltet — falls nicht, kostet der Paid-Plan $5/Monat.

---

## 3. Rechtliches — wenn andere es nutzen (kein Rechtsrat)

### Dein stärkster Schutz ist schon eingebaut
**Verbinden nur per NFC/QR von Angesicht zu Angesicht** → es ist ein **geschlossener Kreis**, kein öffentlicher
Dienst. Fremde können dich technisch gar nicht erreichen. Das unterscheidet dich fundamental von einem offenen
Messenger und entschärft die meisten Pflichten. **Nicht öffentlich bewerben** — als privates Hobby-Werkzeug für
einen festen Kreis positionieren.

### Was du trotzdem haben solltest (günstige Absicherung)
1. **Impressum** (§ 5 DDG) — Name, Anschrift, E-Mail — auf der Web-App + in der App. Sobald andere es regelmäßig
   nutzen, greift die Impressumspflicht.
2. **Datenschutzerklärung** (DSGVO Art. 13) — hier hilft dein Design enorm: „Privacy by Design" (Art. 25).
   Reinschreiben: **keine Inhalte gespeichert** (Ende-zu-Ende), **keine Konten/Telefonnummern**, Briefkasten nur
   **kurzlebig & verschlüsselt**, Server sieht nur technische Verbindungsdaten. Cloudflare als Auftragsverarbeiter
   nennen.
3. **Nutzungsbedingungen / Haftungsausschluss** — Nutzer sind für ihre eigenen Inhalte und legale Nutzung selbst
   verantwortlich; Bereitstellung „ohne Gewähr", keine Garantie.
4. **Datensparsamkeit am Server** (technisch, machen wir): keine Inhalts-Logs, keine dauerhaften IP-Logs,
   Briefkasten mit Auto-Ablauf (TTL), kein Tracking, keine Cookies → dann auch **kein Cookie-Banner** nötig.
5. **Cloudflare-Auftragsverarbeitung (DPA)** im Dashboard akzeptieren; wenn möglich EU-Datenhaltung wählen.

### Wo es ernst wird (nur falls es öffentlich/groß wird)
Ein „nummernunabhängiger interpersoneller Telekommunikationsdienst" (wie Signal/WhatsApp) kann unter **TKG/TTDSG**
fallen — mit Pflichten wie Fernmeldegeheimnis, Sicherheitskonzept, evtl. Meldung bei der Bundesnetzagentur.
**Für einen privaten Freundeskreis praktisch nicht einschlägig**, für einen öffentlichen Dienst schon. Ebenso
**DSA** (Haftung für Nutzerinhalte) — kleine/private Dienste haben Erleichterungen, und durch E2E hast du gar
keinen Inhaltszugriff (= keine Kenntnis = geschützt, aber auch keine Moderation möglich). **Wenn du es öffentlich
machst: vorher Anwalt.**

### Kurzfassung
- **Nur Freundeskreis, nicht bewerben** → Risiko minimal, Impressum + Datenschutz + Disclaimer genügen.
- **Öffentlich/kommerziell** → Anwalt (IT-Recht) wegen TKG/BNetzA/DSA.

---

## 4. Reihenfolge — was zuerst

1. **Kuppler deployen** (Cloudflare) → erster echter Test (siehe `DEPLOY.md`). *[heute, gemeinsam]*
2. **Impressum + Datenschutz + Disclaimer** als Seiten in die Web-App (ich baue die Vorlagen, du füllst Name/Adresse). 
3. **Release-Keystore** erzeugen + **Repo** `s3lfcod3r/selfmessenger` anlegen + Code pushen.
4. **APK-Release** + **SelfStore-Eintrag** + Profil-README.
5. Feinschliff: Briefkasten-TTL, Android↔Web-Krypto angleichen, Push.
