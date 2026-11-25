/**
 * SupportMama for the web: a "Chat with us" widget whose conversations your
 * team answers from Telegram or Slack.
 *
 *   import SupportMama from '@supportmama/web';
 *   SupportMama.init({ endpoint: 'https://supportmama.you.workers.dev', appKey: 'smk_…' });
 *
 * or, without a bundler:
 *
 *   <script src="https://supportmama.chat/sdk/supportmama.js"
 *           data-endpoint="https://…" data-app-key="smk_…"></script>
 *
 * To place the chat inside your own page instead of a floating button:
 *
 *   <supportmama-chat endpoint="https://…" app-key="smk_…"></supportmama-chat>
 */
import { contextLine } from './browser';
import { SupportChat } from './core';
import { ChatView, ICONS, Poller, styles, type ViewOptions } from './ui';

export { SupportChat, ApiError, type Message, type ChatOptions, type KeyValueStore } from './core';

export interface SupportMamaOptions extends ViewOptions {
  /** Your SupportMama server. */
  endpoint: string;
  /** Hosted SupportMama: your app key from the dashboard. Self-hosted: leave out. */
  appKey?: string;
  /** Shown to your team first in the context line, e.g. "Acme 2.3.1". Default: the site's host name. */
  appName?: string;
  /** Extra facts for your team, e.g. `() => ({ plan: user.plan, user: user.id })`. */
  attributes?: () => Record<string, string | number | boolean | null | undefined>;
  /** Show the floating button. Set false to open the chat from your own button. Default true. */
  launcher?: boolean;
  position?: 'right' | 'left';
  /** Your brand colour (any CSS colour). */
  accent?: string;
  theme?: 'auto' | 'light' | 'dark';
  zIndex?: number;
}

/** One conversation per (endpoint, app key) per page, shared by the widget and any inline element. */
const chats = new Map<string, { chat: SupportChat; poller?: Poller }>();

function chatFor(opts: SupportMamaOptions): SupportChat {
  const key = `${opts.endpoint}|${opts.appKey ?? ''}`;
  let entry = chats.get(key);
  if (!entry) {
    entry = { chat: new SupportChat({ endpoint: opts.endpoint, appKey: opts.appKey, context: () => contextLine(opts.appName, opts.attributes) }) };
    chats.set(key, entry);
  }
  return entry.chat;
}

function applyTheme(host: HTMLElement, opts: SupportMamaOptions): void {
  host.dataset.theme = opts.theme ?? 'auto';
  if (opts.accent) host.style.setProperty('--sm-accent', opts.accent);
  if (opts.zIndex != null) host.style.setProperty('--sm-z', String(opts.zIndex));
}

// ── The floating widget ─────────────────────────────────────────────────────

class Widget {
  private readonly host: HTMLElement;
  private readonly root: ShadowRoot;
  private readonly wrapper: HTMLElement;
  private readonly launcher: HTMLButtonElement | null;
  private readonly badge: HTMLElement;
  private readonly view: ChatView;
  private readonly poller: Poller;
  private readonly unreadListeners = new Set<(unread: boolean) => void>();
  private readonly unsubscribe: () => void;
  private lastUnread: boolean;
  isOpen = false;

  constructor(readonly chat: SupportChat, opts: SupportMamaOptions) {
    this.host = document.createElement('supportmama-widget');
    applyTheme(this.host, opts);
    this.root = this.host.attachShadow({ mode: 'open' });
    const style = document.createElement('style');
    style.textContent = styles;
    this.wrapper = document.createElement('div');
    this.wrapper.className = `floating${opts.position === 'left' ? ' left' : ''}`;

    this.view = new ChatView(chat, opts, () => this.close());
    this.view.element.hidden = true;
    this.badge = document.createElement('span');
    this.badge.className = 'badge';
    this.badge.hidden = true;
    this.launcher = null;
    if (opts.launcher !== false) {
      this.launcher = document.createElement('button');
      this.launcher.className = 'launcher';
      this.launcher.type = 'button';
      this.launcher.setAttribute('aria-label', opts.title ?? 'Chat with us');
      this.launcher.innerHTML = ICONS.chat;
      this.launcher.append(this.badge);
      this.launcher.addEventListener('click', () => this.toggle());
      this.wrapper.append(this.launcher);
    }
    this.wrapper.append(this.view.element);
    this.root.append(style, this.wrapper);
    document.body.append(this.host);

    this.lastUnread = chat.unread;
    this.unsubscribe = chat.subscribe(() => this.syncUnread());
    this.syncUnread();
    this.root.addEventListener('keydown', (e) => {
      if ((e as KeyboardEvent).key === 'Escape' && this.isOpen) this.close();
    });
    this.poller = new Poller(chat, () => this.isOpen);
    this.poller.start();
  }

  open(draft?: string): void {
    this.isOpen = true;
    this.view.element.hidden = false;
    this.view.setVisible(true);
    this.view.focus(draft);
    this.poller.tick();
  }

  close(): void {
    this.isOpen = false;
    this.view.element.hidden = true;
    this.view.setVisible(false);
    this.launcher?.focus();
  }

  toggle(): void {
    if (this.isOpen) this.close();
    else this.open();
  }

  onUnread(listener: (unread: boolean) => void): () => void {
    this.unreadListeners.add(listener);
    listener(this.chat.unread);
    return () => this.unreadListeners.delete(listener);
  }

  destroy(): void {
    this.poller.stop();
    this.unsubscribe();
    this.view.destroy();
    this.host.remove();
  }

  private syncUnread(): void {
    this.badge.hidden = !this.chat.unread;
    if (this.chat.unread !== this.lastUnread) {
      this.lastUnread = this.chat.unread;
      for (const l of this.unreadListeners) l(this.chat.unread);
    }
  }
}

let widget: Widget | null = null;

/** Sets up the chat. Call once per page; calling again replaces the previous setup. */
export function init(options: SupportMamaOptions): void {
  if (typeof document === 'undefined') return; // server-side rendering
  if (!options?.endpoint) throw new Error('SupportMama.init: `endpoint` is required');
  const start = () => {
    widget?.destroy();
    widget = new Widget(chatFor(options), options);
  };
  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start, { once: true });
}

/** Opens the chat, optionally with a draft in the message box (it isn't sent). */
export function open(draft?: string): void {
  widget?.open(draft);
}

export function close(): void {
  widget?.close();
}

export function toggle(): void {
  widget?.toggle();
}

/** Calls back now and whenever a team reply becomes unread or read; returns an unsubscribe function. */
export function onUnread(listener: (unread: boolean) => void): () => void {
  if (!widget) throw new Error('SupportMama.onUnread: call init first');
  return widget.onUnread(listener);
}

/** The name your team sees on this conversation's thread. */
export function setUserName(name: string): void {
  widget?.chat.setName(name);
}

export function destroy(): void {
  widget?.destroy();
  widget = null;
}

// ── <supportmama-chat>: the chat inside your own layout ─────────────────────

/** Registers `<supportmama-chat>`; done automatically in browsers. */
export function defineElement(): void {
  if (typeof customElements === 'undefined' || customElements.get('supportmama-chat')) return;

  class SupportMamaChatElement extends HTMLElement {
    private view: ChatView | null = null;
    private poller: Poller | null = null;

    connectedCallback(): void {
      const endpoint = this.getAttribute('endpoint');
      if (!endpoint || this.view) return;
      const opts: SupportMamaOptions = {
        endpoint,
        appKey: this.getAttribute('app-key') ?? undefined,
        appName: this.getAttribute('app-name') ?? undefined,
        title: this.getAttribute('title') ?? undefined,
        headline: this.getAttribute('headline') ?? undefined,
        intro: this.getAttribute('intro') ?? undefined,
        teamLabel: this.getAttribute('team-label') ?? undefined,
        accent: this.getAttribute('accent') ?? undefined,
        theme: (this.getAttribute('theme') as SupportMamaOptions['theme']) ?? undefined,
        askForName: this.getAttribute('ask-for-name') !== 'false',
        screenshots: this.getAttribute('screenshots') !== 'false',
      };
      applyTheme(this, opts);
      const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
      const style = document.createElement('style');
      style.textContent = `${styles} :host { display: block; height: 100%; min-height: 360px; }`;
      const wrapper = document.createElement('div');
      wrapper.className = 'inline';
      wrapper.style.height = '100%';
      const chat = chatFor(opts);
      this.view = new ChatView(chat, opts);
      wrapper.append(this.view.element);
      root.replaceChildren(style, wrapper);
      this.view.setVisible(true);
      this.poller = new Poller(chat, () => true);
      this.poller.start();
    }

    disconnectedCallback(): void {
      this.poller?.stop();
      this.view?.destroy();
      this.view = null;
      this.poller = null;
    }
  }
  customElements.define('supportmama-chat', SupportMamaChatElement);
}

defineElement();

const SupportMama = { init, open, close, toggle, onUnread, setUserName, destroy, defineElement, SupportChat };
export default SupportMama;
