// `npm run demo`: the widget on a demo page, against a real local server whose
// "Telegram" is a fake, so you can play both sides without any accounts.
//
//   http://localhost:8796          the demo page (widget + a "reply as the team" box)
//
// The server runs under wrangler dev on :8795, so this needs the (private)
// server checkout, where this repo is the `sdks/` submodule.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { fakeApi, startWorker } from '../../../server/test/harness.js';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const PAGE_PORT = 8796;
const TOKEN = 'demo-token';
const CHAT = '-100123';
let nextTopic = 1;
let lastTopic = null;
const feed = []; // what "Telegram" received, newest last

const telegram = await fakeApi(({ method, payload }) => {
  if (method === 'createForumTopic') {
    feed.push({ kind: 'topic', text: `New topic: ${payload.name}` });
    return { ok: true, result: { message_thread_id: nextTopic++ } };
  }
  if (payload?.message_thread_id) lastTopic = Number(payload.message_thread_id);
  if (method === 'sendMessage') feed.push({ kind: 'user', text: payload.text });
  if (method === 'sendPhoto') feed.push({ kind: 'user', text: `📷 screenshot ${payload.caption ?? ''}` });
  return { ok: true, result: { message_id: Date.now(), photo: [{ file_id: 'demo' }] } };
});

const worker = await startWorker({
  port: 8795,
  vars: { TELEGRAM_API: telegram.url, TELEGRAM_TOKEN: TOKEN, TELEGRAM_CHAT_ID: CHAT, ALLOWED_ORIGINS: `http://localhost:${PAGE_PORT}` },
});
const webhookSecret = createHash('sha256').update(`webhook:${TOKEN}`).digest('hex').slice(0, 48);

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.map': 'application/json' };

createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/team/feed') {
    res.setHeader('Content-Type', 'application/json');
    return res.end(JSON.stringify({ feed, endpoint: worker.base }));
  }
  if (url.pathname === '/team/reply' && req.method === 'POST') {
    let body = '';
    for await (const chunk of req) body += chunk;
    const { text } = JSON.parse(body || '{}');
    if (lastTopic && text) {
      await fetch(`${worker.base}/telegram`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': webhookSecret },
        body: JSON.stringify({ message: { message_id: Date.now() % 1e9, date: Math.floor(Date.now() / 1000), chat: { id: Number(CHAT) }, from: { id: 7 }, is_topic_message: true, message_thread_id: lastTopic, text } }),
      });
      feed.push({ kind: 'team', text });
    }
    return res.end('{}');
  }
  const path = normalize(join(ROOT, url.pathname === '/' ? 'demo/index.html' : url.pathname));
  if (!path.startsWith(ROOT)) return res.writeHead(403).end();
  try {
    const data = await readFile(path);
    res.setHeader('Content-Type', TYPES[extname(path)] ?? 'application/octet-stream');
    res.end(data);
  } catch {
    res.writeHead(404).end('not found');
  }
}).listen(PAGE_PORT, () => console.log(`Demo: http://localhost:${PAGE_PORT}  (server ${worker.base})`));

for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => { worker.stop(); telegram.close(); process.exit(0); });
