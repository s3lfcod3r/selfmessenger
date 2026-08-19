// SelfMessenger — Signaling (Cloudflare Worker + Durable Objects)
// Rolle: NUR Kuppler + anonymer Briefkasten. Sieht NIE Inhalte, speichert nur verschlüsselte Blobs.
// Referenz-Implementierung ist der lokale dev/server.mjs (dort verifiziert); dieser Worker spiegelt sie.

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'POST, GET, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type' };
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });   // Preflight
    if (url.pathname === '/ws') {
      const mbx = url.searchParams.get('mbx');
      if (mbx) return env.MAILBOX.get(env.MAILBOX.idFromName(mbx)).fetch(req);
      const room = url.searchParams.get('room') || 'default';
      return env.ROOMS.get(env.ROOMS.idFromName(room)).fetch(req);
    }
    // Direkter Briefkasten-Einwurf: verschlüsselten Blob in den Briefkasten legen, ganz ohne P2P/Room.
    if (url.pathname === '/send' && req.method === 'POST') {
      let body = null; try { body = await req.json(); } catch (_) {}
      if (!body || !body.mailbox || !body.blob) return new Response('bad request', { status: 400, headers: CORS });
      const stub = env.MAILBOX.get(env.MAILBOX.idFromName(body.mailbox));
      // push: true (Standard) | false (Chunks) | "call" (Anruf-Benachrichtigung). Wert durchreichen.
      const r = await stub.fetch('https://mbx/enqueue', { method: 'POST', body: JSON.stringify({ blob: body.blob, push: body.push === undefined ? true : body.push }) });
      return new Response(r.ok ? 'ok' : 'rejected', { status: r.status, headers: CORS });
    }
    // ICE-Server: STUN immer; optional selbstgehostetes coturn (TURN_URL+TURN_SECRET) und/oder Cloudflare-TURN.
    // Ohne beide Secret-Paare: nur STUN → kein Regress. Clients holen /turn dynamisch (kein App/Web-Update nötig).
    if (url.pathname === '/turn') {
      const cors = { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/json' };
      const ice = [{ urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }];
      // 1a) Selbstgehostetes coturn (kostenlos) — Standard-REST-Format „use-auth-secret":
      //     username = Ablauf-Unixzeit, credential = base64(HMAC-SHA1(secret, username)). TURN_URL kommagetrennt.
      if (env.TURN_URL && env.TURN_SECRET) {
        try {
          const username = String(Math.floor(Date.now() / 1000) + 86400);
          const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(env.TURN_SECRET), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']);
          const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(username));
          const credential = btoa(String.fromCharCode(...new Uint8Array(sig)));
          const urls = env.TURN_URL.split(',').map(s => s.trim()).filter(Boolean);
          ice.push({ urls, username, credential });
        } catch (_) {}
      }
      // 1b) Gehostetes TURN mit festen Zugangsdaten (z. B. ExpressTURN-Free): TURN_URL + TURN_USER + TURN_PASS.
      else if (env.TURN_URL && env.TURN_USER && env.TURN_PASS) {
        const urls = env.TURN_URL.split(',').map(s => s.trim()).filter(Boolean);
        ice.push({ urls, username: env.TURN_USER, credential: env.TURN_PASS });
      }
      // 2) Cloudflare-TURN (kostenpflichtig) — nur wenn konfiguriert.
      if (env.CF_TURN_KEY_ID && env.CF_TURN_API_TOKEN) {
        try {
          const r = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${env.CF_TURN_KEY_ID}/credentials/generate`, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${env.CF_TURN_API_TOKEN}`, 'Content-Type': 'application/json' },
            body: JSON.stringify({ ttl: 86400 })
          });
          const j = await r.json();
          if (j && j.iceServers) ice.push(j.iceServers);
        } catch (_) {}
      }
      return new Response(JSON.stringify({ iceServers: ice }), { headers: cors });
    }
    return new Response('SelfMessenger signaling: ok', { status: 200 });
  }
};

// --- Rendezvous-Raum: reicht offer/answer/ice zwischen 2 Peers durch ---
export class Room {
  constructor(state, env) { this.sessions = []; this.env = env; }

  async fetch(req) {
    if (req.headers.get('Upgrade') !== 'websocket') return new Response('expected websocket', { status: 426 });
    const [client, server] = Object.values(new WebSocketPair());
    this.accept(server);
    return new Response(null, { status: 101, webSocket: client });
  }

  accept(ws) {
    ws.accept();
    if (this.sessions.length >= 2) { ws.send(JSON.stringify({ type: 'full' })); ws.close(); return; }
    this.sessions.push(ws);
    if (this.sessions.length === 2) {
      this.sessions[0].send(JSON.stringify({ type: 'ready', initiator: false }));
      this.sessions[1].send(JSON.stringify({ type: 'ready', initiator: true }));
    }
    ws.addEventListener('message', async (evt) => {
      let parsed = null; try { parsed = JSON.parse(evt.data); } catch (_) {}
      if (parsed && parsed.type === 'store') {           // verschlüsselten Blob in den Briefkasten legen (Alt-Pfad)
        const stub = this.env.MAILBOX.get(this.env.MAILBOX.idFromName(parsed.mailbox));
        try { await stub.fetch('https://mbx/enqueue', { method: 'POST', body: JSON.stringify({ blob: parsed.blob, push: true }) }); } catch (_) {}
        return;
      }
      for (const peer of this.sessions) if (peer !== ws) { try { peer.send(evt.data); } catch (_) {} }
    });
    const bye = () => {
      this.sessions = this.sessions.filter((s) => s !== ws);
      for (const peer of this.sessions) { try { peer.send(JSON.stringify({ type: 'peer-left' })); } catch (_) {} }
    };
    ws.addEventListener('close', bye);
    ws.addEventListener('error', bye);
  }
}

// --- Briefkasten: lagert verschlüsselte Blobs für offline-Empfänger, liefert bei Anmeldung ---
export class Mailbox {
  constructor(state, env) { this.state = state; this.env = env; this.ws = null; }

  async fetch(req) {
    const url = new URL(req.url);
    if (url.pathname === '/enqueue') {
      const body = await req.json();
      const blob = body && body.blob ? body.blob : body;     // {blob,push} oder (alt) roher Blob
      const pushVal = body ? body.push : true;
      const doPush = pushVal !== false;
      if (JSON.stringify(blob).length > 120000) return new Response('too large', { status: 413 });   // DO-Wert-Limit 128KB
      if (this.ws) { try { this.ws.send(JSON.stringify({ type: 'mail', items: [blob] })); return new Response('sent'); } catch (_) {} }
      // Queue-Cap gegen unbegrenztes Speicherwachstum (Storage-DoS)
      let qcount = (await this.state.storage.get('qcount')) || 0;
      if (qcount >= 3000) return new Response('mailbox full', { status: 429 });
      // Offline: jeden Eintrag als eigenen Key ablegen (umgeht das 128-KB-pro-Wert-Limit; große Bilder ok)
      const seq = ((await this.state.storage.get('seq')) || 0) + 1;
      await this.state.storage.put('seq', seq);
      await this.state.storage.put('q:' + String(seq).padStart(9, '0'), blob);
      await this.state.storage.put('qcount', qcount + 1);
      if (doPush) {
        // Push-Rate-Limit gegen Benachrichtigungs-Bombing (v.a. gefälschte "Anruf"-Pushes)
        const now = Date.now();
        let pt = ((await this.state.storage.get('pushTimes')) || []).filter((t) => now - t < 60000);
        if (pt.length < 12) {
          pt.push(now); await this.state.storage.put('pushTimes', pt);
          const token = await this.state.storage.get('token');
          if (token) { try { await sendPush(this.env, token, pushVal === 'call'); } catch (_) {} }
        }
      }
      return new Response('queued');
    }
    if (req.headers.get('Upgrade') !== 'websocket') return new Response('expected websocket', { status: 426 });
    const [client, server] = Object.values(new WebSocketPair());
    server.accept(); this.ws = server;
    const entries = await this.state.storage.list({ prefix: 'q:' });
    if (entries.size) {
      const items = [...entries.values()];
      for (let i = 0; i < items.length; i += 20)             // in Häppchen senden (WS-Frame-Limit ~1 MB)
        server.send(JSON.stringify({ type: 'mail', items: items.slice(i, i + 20) }));
      await this.state.storage.delete([...entries.keys()]);
      await this.state.storage.put('qcount', 0);
    }
    server.addEventListener('message', async (evt) => {                 // Push-Token anmelden
      let m = null; try { m = JSON.parse(evt.data); } catch (_) {}
      if (m && m.type === 'pushtoken' && m.token) await this.state.storage.put('token', m.token);
    });
    server.addEventListener('close', () => { if (this.ws === server) this.ws = null; });
    return new Response(null, { status: 101, webSocket: client });
  }
}

// ===== FCM-Push (HTTP v1) — weckt die geschlossene App, ohne Inhalt zu übertragen =====
// Braucht das Dienstkonto (Service Account) als Worker-Secret FCM_SA (JSON-String).
let _fcmToken = null; // { access_token, exp } je Worker-Instanz zwischengespeichert
async function sendPush(env, token, isCall) {
  if (!env.FCM_SA) return;                       // ohne Dienstkonto: still nichts tun
  const sa = typeof env.FCM_SA === 'string' ? JSON.parse(env.FCM_SA) : env.FCM_SA;
  const access = await fcmAccessToken(sa);
  await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${access}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message: {
        token,
        data: {
          title: 'SelfMessenger',
          body: isCall ? 'Eingehender Anruf' : 'Neue Nachricht',
          type: isCall ? 'call' : 'msg'
        },
        android: { priority: 'high' }
      }
    })
  });
}

async function fcmAccessToken(sa) {
  const now = Math.floor(Date.now() / 1000);
  if (_fcmToken && _fcmToken.exp > now + 60) return _fcmToken.access_token;
  const header = { alg: 'RS256', typ: 'JWT' };
  const claim = {
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now, exp: now + 3600
  };
  const unsigned = b64url(new TextEncoder().encode(JSON.stringify(header))) + '.' +
                   b64url(new TextEncoder().encode(JSON.stringify(claim)));
  const key = await importPkcs8(sa.private_key);
  const sig = await crypto.subtle.sign({ name: 'RSASSA-PKCS1-v1_5' }, key, new TextEncoder().encode(unsigned));
  const jwt = unsigned + '.' + b64url(new Uint8Array(sig));
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`
  });
  const j = await res.json().catch(() => ({}));
  if (!res.ok || !j.access_token) throw new Error('fcm oauth failed');   // NICHT cachen — sonst 1 h lang kein Push
  _fcmToken = { access_token: j.access_token, exp: now + 3500 };
  return j.access_token;
}

async function importPkcs8(pem) {
  const body = pem.replace(/-----BEGIN PRIVATE KEY-----/, '').replace(/-----END PRIVATE KEY-----/, '').replace(/\s+/g, '');
  const der = Uint8Array.from(atob(body), c => c.charCodeAt(0));
  return crypto.subtle.importKey('pkcs8', der.buffer, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
}

function b64url(bytes) {
  let s = btoa(String.fromCharCode(...bytes));
  return s.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
