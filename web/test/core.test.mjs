// The protocol client against a real server (wrangler dev) and a fake Telegram.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { SupportChat } from '../dist/core.mjs';
// The server is closed source. Inside its checkout (where this repo is the
// `sdks/` submodule) these tests run against it; on their own they're skipped.
const harness = await import('../../../server/test/harness.js').catch(() => null);
const { fakeApi, startWorker } = harness ?? {};
const t = harness ? test : test.skip;

const CHAT = '-1009';
const TOKEN = 'web-test-token';
const PROXY_KEY = 'proxy';
let telegram, worker;
let nextTopic = 40;
const secret = createHash('sha256').update(`webhook:${TOKEN}`).digest('hex').slice(0, 48);

before(async () => {
  if (!harness) return;
  telegram = await fakeApi(({ method }) => {
    if (method === 'createForumTopic') return { ok: true, result: { message_thread_id: nextTopic++ } };
    if (method === 'sendPhoto') return { ok: true, result: { photo: [{ file_id: 'p' }] } };
    return { ok: true, result: { message_id: 1 } };
  });
  worker = await startWorker({
    port: 8794,
    vars: { TELEGRAM_API: telegram.url, TELEGRAM_TOKEN: TOKEN, TELEGRAM_CHAT_ID: CHAT, PROXY_KEY },
  });
});

after(() => {
  worker?.stop();
  telegram?.close();
});

function memoryStore() {
  const m = new Map();
  return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), map: m };
}

let nextIp = 1;
/** A client whose requests come "from" an address of its own, and are recorded. */
function client(storage = memoryStore(), extra = {}) {
  const ip = `203.0.113.${nextIp++}`;
  const requests = [];
  const chat = new SupportChat({
    endpoint: `${worker.base}/`,
    storage,
    context: () => 'Web test · Node',
    fetch: (url, init) => {
      requests.push({ url, init });
      return fetch(url, { ...init, headers: { ...init.headers, 'X-Client-IP': ip, 'X-Proxy-Key': PROXY_KEY } });
    },
    ...extra,
  });
  return { chat, storage, requests };
}

async function teamReplies(thread, text, id = Math.floor(Math.random() * 1e6)) {
  await fetch(`${worker.base}/telegram`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': secret },
    body: JSON.stringify({ message: { message_id: id, date: 1761900000, chat: { id: Number(CHAT) }, from: { id: 1 }, is_topic_message: true, message_thread_id: thread, text } }),
  });
}

const lastThread = () => telegram.calls.filter((c) => c.payload.message_thread_id).at(-1).payload.message_thread_id;

t('sending, receiving, unread and read', async () => {
  const { chat } = client();
  assert.equal(chat.hasConversation, false);
  assert.deepEqual(await chat.refresh(), []);

  let changes = 0;
  chat.subscribe(() => changes++);
  chat.setName('Meera');
  await chat.send('  the export button does nothing  ');
  assert.ok(changes >= 2);
  assert.equal(chat.lastError, null);
  assert.deepEqual(chat.messages.map((m) => [m.from, m.text, m.state]), [['user', 'the export button does nothing', 'sent']]);
  assert.ok(chat.messages[0].id > 0);
  const topicCall = telegram.calls.findLast((c) => c.method === 'createForumTopic');
  assert.match(topicCall.payload.name, /^Meera #/);
  assert.ok(telegram.calls.some((c) => c.payload.text === 'ℹ️ Web test · Node'));

  await teamReplies(lastThread(), 'Fixed in 2.3.2!');
  const fresh = await chat.refresh();
  assert.deepEqual(fresh.map((m) => m.text), ['Fixed in 2.3.2!']);
  assert.equal(chat.unread, true);
  chat.markSeen();
  assert.equal(chat.unread, false);
  assert.deepEqual(await chat.refresh(), []);
});

t('the conversation, cursor and read marker survive a reload', async () => {
  const first = client();
  await first.chat.send('hello');
  await teamReplies(lastThread(), 'hi!');
  await first.chat.refresh();
  first.chat.markSeen();

  const again = client(first.storage);
  assert.deepEqual(again.chat.messages.map((m) => m.text), ['hello', 'hi!']);
  assert.equal(again.chat.unread, false);
  await teamReplies(lastThread(), 'anything else?');
  await again.chat.refresh();
  assert.match(again.requests.at(-1).url, /after=\d+$/);
  assert.notEqual(again.requests.at(-1).url.split('after=')[1], '0');
  assert.deepEqual(again.chat.messages.map((m) => m.text), ['hello', 'hi!', 'anything else?']);
  assert.equal(again.chat.unread, true);
});

t('own sends do not move the read cursor', async () => {
  const { chat, requests, storage } = client();
  await chat.send('one');
  await chat.send('two');
  await chat.refresh();
  assert.match(requests.at(-1).url, /after=0$/);
  const cursorKey = [...storage.map.keys()].find((k) => k.endsWith(':cursor'));
  assert.equal(Number(storage.map.get(cursorKey)), Math.max(...chat.messages.map((m) => m.id)));
});

t('a failed send is kept, shown as failed, and can be retried', async () => {
  let down = true;
  const storage = memoryStore();
  const { chat } = client(storage, {
    fetch: (url, init) => (down ? Promise.reject(new TypeError('offline')) : fetch(url, { ...init, headers: { ...init.headers, 'X-Client-IP': '203.0.113.200', 'X-Proxy-Key': PROXY_KEY } })),
  });
  await chat.send('are you there?');
  assert.equal(chat.lastError, 'network');
  assert.equal(chat.messages[0].state, 'failed');
  down = false;
  await chat.retry(chat.messages[0].localId);
  assert.equal(chat.messages[0].state, 'sent');
  assert.equal(chat.lastError, null);
});

t('a send cut off by a reload shows as failed', async () => {
  const storage = memoryStore();
  storage.setItem(`supportmama:${worker.base}:messages`, JSON.stringify([{ id: 0, localId: 'x', from: 'user', text: 'half sent', at: 1, image: false, state: 'sending' }]));
  const { chat } = client(storage);
  assert.equal(chat.messages[0].state, 'failed');
});

t('a screenshot goes up as multipart, and the app key rides along', async () => {
  const { chat, requests } = client(memoryStore(), { appKey: undefined });
  const jpeg = new Blob([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, ...new Array(500).fill(7)])], { type: 'image/jpeg' });
  await chat.send('this screen', jpeg);
  assert.equal(chat.messages[0].image, true);
  assert.ok(requests[0].init.body instanceof FormData);
  assert.ok(telegram.calls.some((c) => c.method === 'sendPhoto'));

  const keyed = client(memoryStore(), { appKey: 'smk_test' });
  await keyed.chat.refresh();
  await keyed.chat.send('hi').catch(() => {});
  assert.equal(keyed.requests[0].init.headers['X-App-Key'], 'smk_test');
});
