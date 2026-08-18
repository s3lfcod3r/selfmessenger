// Lokaler Test-Kuppler (spiegelt Cloudflare-Worker). Reicht Verbindungs-Zettel durch (sieht nie Inhalte)
// UND bietet einen "Briefkasten": verschlüsselte Blobs für offline-Empfänger zwischenlagern.
// Liefert zusätzlich den Web-Client aus ../web aus.
import http from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { WebSocketServer } from 'ws';
import { fileURLToPath } from 'node:url';
import { dirname, join, extname } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PUBLIC = join(__dirname, '..', 'web');
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css', '.wasm': 'application/wasm', '.png': 'image/png', '.svg': 'image/svg+xml' };

const server = http.createServer((req, res) => {
  let p = new URL(req.url, 'http://x').pathname;
  if (p === '/' || p === '') p = '/index.html';
  const file = join(PUBLIC, p);
  if (!file.startsWith(PUBLIC) || !existsSync(file)) { res.writeHead(404); return res.end('not found'); }
  res.writeHead(200, { 'content-type': MIME[extname(p)] || 'application/octet-stream' });
  res.end(readFileSync(file));
});

const wss = new WebSocketServer({ server, path: '/ws' });
const rooms = new Map();       // roomId -> Set<ws>
const mailboxes = new Map();   // mbxId -> { queue: [blob], ws }

function box(id) { let b = mailboxes.get(id); if (!b) { b = { queue: [], ws: null }; mailboxes.set(id, b); } return b; }
function storeMail(mbx, blob) {
  const b = box(mbx);
  if (b.ws && b.ws.readyState === 1) b.ws.send(JSON.stringify({ type: 'mail', items: [blob] }));
  else b.queue.push(blob);
}

wss.on('connection', (ws, req) => {
  const u = new URL(req.url, 'http://x');

  const mbx = u.searchParams.get('mbx');
  if (mbx) {                                    // Briefkasten-Verbindung
    const b = box(mbx); b.ws = ws;
    if (b.queue.length) { ws.send(JSON.stringify({ type: 'mail', items: b.queue })); b.queue = []; }
    ws.on('close', () => { if (b.ws === ws) b.ws = null; });
    return;
  }

  const room = u.searchParams.get('room') || 'default';   // Rendezvous-Raum
  let set = rooms.get(room);
  if (!set) { set = new Set(); rooms.set(room, set); }
  if (set.size >= 2) { ws.send(JSON.stringify({ type: 'full' })); return ws.close(); }
  set.add(ws);
  if (set.size === 2) {
    const [first, second] = [...set];
    first.send(JSON.stringify({ type: 'ready', initiator: false }));
    second.send(JSON.stringify({ type: 'ready', initiator: true }));
  }
  ws.on('message', (buf) => {
    const txt = buf.toString();
    let parsed = null; try { parsed = JSON.parse(txt); } catch (_) {}
    if (parsed && parsed.type === 'store') { storeMail(parsed.mailbox, parsed.blob); return; }
    for (const peer of set) if (peer !== ws && peer.readyState === 1) peer.send(txt);
  });
  ws.on('close', () => {
    set.delete(ws);
    if (set.size === 0) { rooms.delete(room); return; }
    for (const peer of set) if (peer.readyState === 1) peer.send(JSON.stringify({ type: 'peer-left' }));
  });
});

const PORT = 8780;
server.listen(PORT, '127.0.0.1', () => console.log('signaling+web+mailbox http://127.0.0.1:' + PORT + '  (?me=anna / ?me=ben)'));
