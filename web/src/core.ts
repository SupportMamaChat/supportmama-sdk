/**
 * The SupportMama protocol client (docs/protocol.md), with no UI: one
 * conversation per browser, kept in storage so it survives reloads.
 *
 *   const chat = new SupportChat({ endpoint, appKey });
 *   chat.subscribe(() => render(chat.messages));
 *   await chat.send('The export button does nothing');
 */

export interface Message {
  /** The server's id; 0 until the message has been sent. */
  id: number;
  /** Tells unsent messages apart. */
  localId: string;
  from: 'user' | 'team';
  text: string;
  at: number;
  /** A screenshot was attached. */
  image: boolean;
  /** The screenshot as picked in this page, while it's open. */
  imageUrl?: string;
  state: 'sent' | 'sending' | 'failed';
}

/** localStorage, or anything shaped like it. */
export interface KeyValueStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface ChatOptions {
  /** Your SupportMama server, e.g. `https://supportmama.you.workers.dev`. */
  endpoint: string;
  /** Hosted SupportMama: the app key from the dashboard. Self-hosted: leave out. */
  appKey?: string;
  /** The "ℹ️ …" line for your team, worked out at each send. */
  context?: () => string;
  storage?: KeyValueStore;
  fetch?: typeof fetch;
}

export class ApiError extends Error {
  constructor(public status: number, public code: string) {
    super(code);
  }
}

interface WireMessage {
  id: number;
  from: 'user' | 'team';
  text: string;
  at: number;
  image?: boolean;
}

export class SupportChat {
  messages: Message[];
  /** A team reply newer than the user has seen. */
  unread: boolean;
  /** The last refresh failed. */
  offline = false;
  /** Why the last send failed, if it did: `slow_down`, `not_connected`, `network`, … */
  lastError: string | null = null;
  name: string;

  private readonly endpoint: string;
  private readonly prefix: string;
  private readonly store: KeyValueStore;
  private readonly http: typeof fetch;
  private readonly listeners = new Set<() => void>();
  private inflight: Promise<Message[]> | null = null;

  constructor(private readonly options: ChatOptions) {
    this.endpoint = options.endpoint.replace(/\/+$/, '');
    this.prefix = `supportmama:${options.appKey || this.endpoint}:`;
    this.store = options.storage ?? safeLocalStorage();
    this.http = options.fetch ?? globalThis.fetch.bind(globalThis);
    // A send cut off by closing the page is shown as unsent, to retry.
    this.messages = this.load<Message[]>('messages', []).map((m) => (m.state === 'sending' ? { ...m, state: 'failed' } : m));
    this.name = this.store.getItem(this.prefix + 'name') ?? '';
    this.unread = this.computeUnread();
  }

  /** Called after every change; returns an unsubscribe function. */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Nothing to fetch until the user has written. */
  get hasConversation(): boolean {
    return this.messages.length > 0;
  }

  setName(name: string): void {
    this.name = name.trim().slice(0, 40);
    this.store.setItem(this.prefix + 'name', this.name);
    this.emit();
  }

  async send(text: string, image?: Blob): Promise<void> {
    const trimmed = text.trim();
    if (!trimmed && !image) return;
    const pending: Message = {
      id: 0, localId: uuid(), from: 'user', text: trimmed, at: Date.now(), image: Boolean(image),
      imageUrl: image && typeof URL !== 'undefined' && URL.createObjectURL ? URL.createObjectURL(image) : undefined,
      state: 'sending',
    };
    this.change((list) => [...list, pending]);
    await this.deliver(pending, image);
  }

  /** Sends a failed message again. A screenshot only survives while the page that picked it is open. */
  async retry(localId: string, image?: Blob): Promise<void> {
    const failed = this.messages.find((m) => m.localId === localId && m.state === 'failed');
    if (!failed) return;
    const pending = { ...failed, state: 'sending' as const };
    this.change((list) => list.map((m) => (m.localId === localId ? pending : m)));
    await this.deliver(pending, image ?? this.images.get(localId));
  }

  /** Pulls what's new; resolves to the team replies this browser didn't have. */
  refresh(): Promise<Message[]> {
    if (!this.hasConversation) return Promise.resolve([]);
    this.inflight ??= this.fetchNew().finally(() => (this.inflight = null));
    return this.inflight;
  }

  /** The conversation is on screen: everything in it is read. */
  markSeen(): void {
    const newest = this.newestTeamId();
    if (newest > this.seen) this.store.setItem(this.prefix + 'seen', String(newest));
    if (this.unread) {
      this.unread = false;
      this.emit();
    }
  }

  // ── Internals ─────────────────────────────────────────────────────────────

  /** Screenshots of unsent messages, kept for a retry while the page is open. */
  private readonly images = new Map<string, Blob>();

  private async deliver(pending: Message, image?: Blob): Promise<void> {
    if (image) this.images.set(pending.localId, image);
    const fields: Record<string, string> = { text: pending.text };
    const context = this.contextLine();
    if (context) fields.context = context;
    if (this.name) fields.name = this.name;
    try {
      let body: FormData | Record<string, string> = fields;
      if (image) {
        const form = new FormData();
        for (const [k, v] of Object.entries(fields)) form.set(k, v);
        form.set('image', image, 'screenshot.jpg');
        body = form;
      }
      const { message } = await this.request<{ message: WireMessage }>('POST', '/v1/messages', body);
      const sent = { ...fromWire(message), localId: pending.localId, imageUrl: pending.imageUrl };
      this.images.delete(pending.localId);
      this.lastError = null;
      this.offline = false;
      this.change((list) => {
        // A poll may have brought it back already, without the local picture.
        if (list.some((m) => m.id === sent.id)) {
          return list.filter((m) => m.localId !== pending.localId).map((m) => (m.id === sent.id ? { ...m, imageUrl: pending.imageUrl } : m));
        }
        return list.map((m) => (m.localId === pending.localId ? sent : m));
      });
    } catch (e) {
      this.lastError = e instanceof ApiError ? e.code : 'network';
      this.change((list) => list.map((m) => (m.localId === pending.localId ? { ...pending, state: 'failed' } : m)));
    }
  }

  private async fetchNew(): Promise<Message[]> {
    const cursor = Number(this.store.getItem(this.prefix + 'cursor') || 0);
    let incoming: WireMessage[];
    try {
      incoming = (await this.request<{ messages: WireMessage[] }>('GET', `/v1/messages?after=${cursor}`)).messages;
    } catch {
      if (!this.offline) {
        this.offline = true;
        this.emit();
      }
      return [];
    }
    const wasOffline = this.offline;
    this.offline = false;
    if (!incoming.length) {
      if (wasOffline) this.emit();
      return [];
    }
    const known = new Set(this.messages.filter((m) => m.id).map((m) => m.id));
    // Move the cursor only by what a GET returned (never by our own sends):
    // a reply stored just before a send would otherwise be skipped.
    const newest = Math.max(...incoming.map((m) => m.id));
    if (newest > cursor) this.store.setItem(this.prefix + 'cursor', String(newest));
    this.change((list) => merge(list, incoming));
    this.unread = this.computeUnread();
    this.emit();
    return incoming.filter((w) => w.from === 'team' && !known.has(w.id)).map(fromWire);
  }

  private async request<T>(method: string, path: string, body?: FormData | Record<string, string>): Promise<T> {
    const headers: Record<string, string> = { Authorization: `Bearer ${this.credentials()}` };
    if (this.options.appKey) headers['X-App-Key'] = this.options.appKey;
    let payload: BodyInit | undefined;
    if (body instanceof FormData) payload = body;
    else if (body) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const res = await this.http(this.endpoint + path, { method, headers, body: payload });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new ApiError(res.status, (data as { error?: string }).error || 'server');
    return data as T;
  }

  /**
   * `<id>.<secret>`, made on first use and kept for the life of this browser
   * profile. The server stores only a hash of the secret.
   */
  private credentials(): string {
    let auth = this.store.getItem(this.prefix + 'auth');
    if (!auth) {
      auth = `${uuid()}.${base64url(crypto.getRandomValues(new Uint8Array(32)))}`;
      this.store.setItem(this.prefix + 'auth', auth);
    }
    return auth;
  }

  private contextLine(): string {
    try {
      return (this.options.context?.() ?? '').replace(/\s+/g, ' ').trim().slice(0, 300);
    } catch {
      return '';
    }
  }

  private get seen(): number {
    return Number(this.store.getItem(this.prefix + 'seen') || 0);
  }

  private newestTeamId(): number {
    return this.messages.reduce((max, m) => (m.from === 'team' && m.id > max ? m.id : max), 0);
  }

  private computeUnread(): boolean {
    return this.newestTeamId() > this.seen;
  }

  private change(transform: (list: Message[]) => Message[]): void {
    this.messages = transform(this.messages);
    const persisted = this.messages.map(({ imageUrl, ...m }) => m);
    try {
      this.store.setItem(this.prefix + 'messages', JSON.stringify(persisted));
    } catch {
      // Storage full or blocked: the chat still works for this page.
    }
    this.emit();
  }

  private load<T>(key: string, fallback: T): T {
    try {
      const raw = this.store.getItem(this.prefix + key);
      return raw ? (JSON.parse(raw) as T) : fallback;
    } catch {
      return fallback;
    }
  }

  private emit(): void {
    for (const listener of this.listeners) {
      try {
        listener();
      } catch (e) {
        console.error('[SupportMama]', e);
      }
    }
  }
}

function fromWire(w: WireMessage): Message {
  return { id: w.id, localId: `s${w.id}`, from: w.from, text: w.text, at: w.at, image: Boolean(w.image), state: 'sent' };
}

/** Sent messages in id order, then the unsent ones. */
export function merge(list: Message[], incoming: WireMessage[]): Message[] {
  const byId = new Map(list.filter((m) => m.id).map((m) => [m.id, m]));
  for (const w of incoming) if (!byId.has(w.id)) byId.set(w.id, fromWire(w));
  const sent = [...byId.values()].sort((a, b) => a.id - b.id);
  return [...sent, ...list.filter((m) => !m.id)];
}

/** RFC 4122 v4 from getRandomValues, which (unlike randomUUID) works outside secure contexts too. */
function uuid(): string {
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

function base64url(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** localStorage when it's there and usable (it throws in some private modes); memory otherwise. */
function safeLocalStorage(): KeyValueStore {
  try {
    const ls = globalThis.localStorage;
    ls.setItem('supportmama:probe', '1');
    ls.removeItem('supportmama:probe');
    return ls;
  } catch {
    const mem = new Map<string, string>();
    return { getItem: (k) => mem.get(k) ?? null, setItem: (k, v) => void mem.set(k, v), removeItem: (k) => void mem.delete(k) };
  }
}
