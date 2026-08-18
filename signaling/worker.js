// SelfMessenger — Signaling (Cloudflare Worker + Durable Objects)
// Rolle: NUR Kuppler + anonymer Briefkasten. Sieht NIE Inhalte, speichert nur verschlüsselte Blobs.
// Referenz-Implementierung ist der lokale dev/server.mjs (dort verifiziert); dieser Worker spiegelt sie.

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === '/ws') {
      const mbx = url.searchParams.get('mbx');
      if (mbx) return env.MAILBOX.get(env.MAILBOX.idFromName(mbx)).fetch(req);
      const room = url.searchParams.get('room') || 'default';
      return env.ROOMS.get(env.ROOMS.idFromName(room)).fetch(req);
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
    ws.addEventListener('message', (evt) => {
      let parsed = null; try { parsed = JSON.parse(evt.data); } catch (_) {}
      if (parsed && parsed.type === 'store') {           // verschlüsselten Blob in den Briefkasten legen
        const stub = this.env.MAILBOX.get(this.env.MAILBOX.idFromName(parsed.mailbox));
        stub.fetch('https://mbx/enqueue', { method: 'POST', body: JSON.stringify(parsed.blob) });
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
  constructor(state, env) { this.state = state; this.ws = null; }

  async fetch(req) {
    const url = new URL(req.url);
    if (url.pathname === '/enqueue') {
      const blob = await req.json();
      if (this.ws) { try { this.ws.send(JSON.stringify({ type: 'mail', items: [blob] })); return new Response('sent'); } catch (_) {} }
      const q = (await this.state.storage.get('q')) || [];
      q.push(blob); await this.state.storage.put('q', q);
      return new Response('queued');
    }
    if (req.headers.get('Upgrade') !== 'websocket') return new Response('expected websocket', { status: 426 });
    const [client, server] = Object.values(new WebSocketPair());
    server.accept(); this.ws = server;
    const q = (await this.state.storage.get('q')) || [];
    if (q.length) { server.send(JSON.stringify({ type: 'mail', items: q })); await this.state.storage.delete('q'); }
    server.addEventListener('close', () => { if (this.ws === server) this.ws = null; });
    return new Response(null, { status: 101, webSocket: client });
  }
}
