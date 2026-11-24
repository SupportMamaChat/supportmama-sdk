/**
 * The chat screen, built with plain DOM in a shadow root. Every string that
 * came from a person goes in as text, never as HTML.
 */
import { prepareImage } from './browser';
import type { Message, SupportChat } from './core';
import { styles } from './styles';

export interface Strings {
  /** Header title. Default "Chat with us". */
  title?: string;
  /** First line of the empty screen. Default "Talk to the team". */
  headline?: string;
  /** Under the headline. */
  intro?: string;
  /** Label over your team's replies. Default "Team". */
  teamLabel?: string;
  placeholder?: string;
  namePlaceholder?: string;
}

export interface ViewOptions extends Strings {
  /** Offer a "What should we call you?" field. Default true. */
  askForName?: boolean;
  /** Let users attach a screenshot. Default true. */
  screenshots?: boolean;
}

const DEFAULTS: Required<Strings> = {
  title: 'Chat with us',
  headline: 'Talk to the team',
  intro: 'Ask anything or report a problem. Real people read every message and reply right here.',
  teamLabel: 'Team',
  placeholder: 'Write a message…',
  namePlaceholder: 'What should we call you? (optional)',
};

const ERRORS: Record<string, string> = {
  slow_down: 'That’s a lot of messages at once. Please try again in a few minutes.',
  not_connected: 'Support isn’t set up yet. Your message is saved here; try again later.',
  too_long: 'That message is too long. Please shorten it.',
  too_big: 'That picture is too big.',
  not_an_image: 'That file isn’t a picture we can send.',
};

const ICONS = {
  chat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"/></svg>',
  close: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>',
  image: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="3"/><circle cx="9" cy="10" r="2"/><path d="M21 16l-5-5-9 9"/></svg>',
  send: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M3.4 20.4l17.4-7.5a1 1 0 0 0 0-1.8L3.4 3.6a1 1 0 0 0-1.4 1.1L4 11l9 1-9 1-2 6.3a1 1 0 0 0 1.4 1.1z"/></svg>',
};

function h<K extends keyof HTMLElementTagNameMap>(tag: K, props: Record<string, unknown> = {}, ...children: (Node | string | null | false)[]) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = String(v);
    else if (k === 'html') el.innerHTML = String(v); // only for our own icons above
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v as EventListener);
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of children) if (c != null && c !== false) el.append(c);
  return el;
}

/** Plain text with http(s) links made clickable. */
function linkified(text: string): DocumentFragment {
  const frag = document.createDocumentFragment();
  const re = /\bhttps?:\/\/[^\s<>"']+[^\s<>"'.,;:!?)\]]/g;
  let last = 0;
  for (const m of text.matchAll(re)) {
    frag.append(text.slice(last, m.index));
    frag.append(h('a', { href: m[0], target: '_blank', rel: 'noopener noreferrer nofollow' }, m[0]));
    last = m.index! + m[0].length;
  }
  frag.append(text.slice(last));
  return frag;
}

const timeFormat = typeof Intl !== 'undefined' ? new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }) : null;

export class ChatView {
  readonly element: HTMLElement;
  private readonly list: HTMLElement;
  private readonly banner: HTMLElement;
  private readonly toast: HTMLElement;
  private readonly textarea: HTMLTextAreaElement;
  private readonly sendButton: HTMLButtonElement;
  private readonly stagedBox: HTMLElement;
  private readonly fileInput: HTMLInputElement;
  private staged: Blob | null = null;
  private stagedUrl: string | null = null;
  private visible = false;
  private unsubscribe: () => void;
  private readonly s: Required<Strings>;

  constructor(private readonly chat: SupportChat, private readonly opts: ViewOptions, onClose?: () => void) {
    this.s = { ...DEFAULTS, ...Object.fromEntries(Object.entries(opts).filter(([, v]) => v != null)) } as Required<Strings>;
    this.banner = h('div', { class: 'banner', hidden: true, role: 'status' }, 'Can’t reach support right now. We’ll keep trying.');
    this.list = h('div', { class: 'list', role: 'log', 'aria-live': 'polite' });
    this.toast = h('div', { class: 'toast', hidden: true, role: 'alert' });
    this.textarea = h('textarea', { rows: '1', placeholder: this.s.placeholder, 'aria-label': this.s.placeholder, maxlength: '4000' }) as HTMLTextAreaElement;
    this.sendButton = h('button', { class: 'icon-btn send', type: 'submit', 'aria-label': 'Send', html: ICONS.send, disabled: true }) as HTMLButtonElement;
    this.fileInput = h('input', { type: 'file', accept: 'image/*', hidden: true }) as HTMLInputElement;
    this.stagedBox = h('div', { class: 'staged', hidden: true });

    const attach = opts.screenshots === false ? null
      : h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Attach a screenshot', html: ICONS.image, onclick: () => this.fileInput.click() });

    const form = h('form', { class: 'composer', onsubmit: (e: Event) => { e.preventDefault(); this.submit(); } },
      attach, this.textarea, this.sendButton);

    this.element = h('div', { class: 'panel', role: 'dialog', 'aria-label': this.s.title },
      h('header', {},
        h('div', { class: 'title' }, this.s.title),
        onClose ? h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Close', html: ICONS.close, onclick: onClose }) : null),
      this.banner, this.list, this.toast, this.stagedBox, form, this.fileInput);

    this.textarea.addEventListener('input', () => this.syncComposer());
    this.textarea.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        this.submit();
      }
    });
    this.textarea.addEventListener('paste', (e) => {
      const file = [...(e.clipboardData?.files ?? [])].find((f) => f.type.startsWith('image/'));
      if (file && opts.screenshots !== false) {
        e.preventDefault();
        void this.stage(file);
      }
    });
    this.fileInput.addEventListener('change', () => {
      const file = this.fileInput.files?.[0];
      this.fileInput.value = '';
      if (file) void this.stage(file);
    });

    this.unsubscribe = chat.subscribe(() => this.render());
    this.render();
  }

  /** On screen or not: on screen, replies count as read. */
  setVisible(visible: boolean): void {
    this.visible = visible;
    if (visible) {
      this.chat.markSeen();
      this.scrollToEnd();
      void this.chat.refresh();
    }
  }

  focus(draft?: string): void {
    if (draft && !this.textarea.value) this.textarea.value = draft;
    this.syncComposer();
    setTimeout(() => this.textarea.focus(), 0);
  }

  destroy(): void {
    this.unsubscribe();
    if (this.stagedUrl) URL.revokeObjectURL(this.stagedUrl);
  }

  private async stage(file: Blob): Promise<void> {
    try {
      const image = await prepareImage(file);
      if (this.stagedUrl) URL.revokeObjectURL(this.stagedUrl);
      this.staged = image;
      this.stagedUrl = URL.createObjectURL(image);
      this.stagedBox.replaceChildren(
        h('img', { src: this.stagedUrl, alt: '' }),
        h('span', {}, 'Screenshot attached'),
        h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Remove screenshot', html: ICONS.close, onclick: () => this.unstage() }),
      );
      this.stagedBox.hidden = false;
      this.syncComposer();
    } catch {
      this.showError('not_an_image');
    }
  }

  private unstage(): void {
    if (this.stagedUrl) URL.revokeObjectURL(this.stagedUrl);
    this.staged = null;
    this.stagedUrl = null;
    this.stagedBox.hidden = true;
    this.stagedBox.replaceChildren();
    this.syncComposer();
  }

  private syncComposer(): void {
    this.sendButton.disabled = !this.textarea.value.trim() && !this.staged;
    this.textarea.style.height = 'auto';
    this.textarea.style.height = `${Math.min(this.textarea.scrollHeight, 140)}px`;
  }

  private submit(): void {
    const text = this.textarea.value;
    const image = this.staged ?? undefined;
    if (!text.trim() && !image) return;
    this.textarea.value = '';
    this.staged = null;
    if (this.stagedUrl) URL.revokeObjectURL(this.stagedUrl); // the message makes its own
    this.stagedUrl = null;
    this.stagedBox.hidden = true;
    this.stagedBox.replaceChildren();
    this.syncComposer();
    this.toast.hidden = true;
    void this.chat.send(text, image).then(() => {
      if (this.chat.lastError) this.showError(this.chat.lastError);
    });
  }

  private showError(code: string): void {
    this.toast.textContent = ERRORS[code] ?? 'Not sent. Check your connection and tap Retry.';
    this.toast.hidden = false;
  }

  private render(): void {
    this.banner.hidden = !this.chat.offline;
    const nearBottom = this.list.scrollHeight - this.list.scrollTop - this.list.clientHeight < 80;
    if (!this.chat.messages.length) {
      this.list.replaceChildren(this.emptyState());
      return;
    }
    const nodes: Node[] = [];
    let prev: Message | null = null;
    for (const m of this.chat.messages) {
      if (m.from === 'team' && prev?.from !== 'team') nodes.push(h('div', { class: 'who' }, this.s.teamLabel));
      nodes.push(this.message(m));
      prev = m;
    }
    this.list.replaceChildren(...nodes);
    if (this.visible) {
      this.chat.markSeen();
      if (nearBottom || prev?.from === 'user') this.scrollToEnd();
    }
  }

  private emptyState(): HTMLElement {
    const box = h('div', { class: 'empty' }, h('h2', {}, this.s.headline), h('p', {}, this.s.intro));
    if (this.opts.askForName !== false) {
      const input = h('input', { type: 'text', maxlength: '40', placeholder: this.s.namePlaceholder, 'aria-label': this.s.namePlaceholder, autocomplete: 'name' }) as HTMLInputElement;
      input.value = this.chat.name;
      input.addEventListener('change', () => this.chat.setName(input.value));
      box.append(input);
    }
    return box;
  }

  private message(m: Message): HTMLElement {
    const bubble = h('div', { class: 'bubble' });
    if (m.image) {
      bubble.append(m.imageUrl ? h('img', { class: 'shot', src: m.imageUrl, alt: 'Screenshot' }) : h('div', { class: 'shot-note' }, '📷 Screenshot'));
    }
    if (m.text) bubble.append(linkified(m.text));
    const meta = m.state === 'failed'
      ? h('button', { class: 'retry', type: 'button', onclick: () => void this.chat.retry(m.localId).then(() => this.chat.lastError && this.showError(this.chat.lastError)) }, 'Not sent · Retry')
      : h('div', { class: 'meta' }, m.state === 'sending' ? 'Sending…' : timeFormat?.format(m.at) ?? '');
    return h('div', { class: `msg ${m.from}${m.state === 'failed' ? ' failed' : ''}` }, bubble, meta);
  }

  private scrollToEnd(): void {
    requestAnimationFrame(() => (this.list.scrollTop = this.list.scrollHeight));
  }
}

/**
 * Keeps the conversation fresh: every 5 s while the chat is on screen, every
 * minute otherwise (for the unread badge), never while the tab is hidden, and
 * at once when the tab comes back.
 */
export class Poller {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private readonly onVisibility = () => {
    if (!document.hidden) this.tick();
  };

  constructor(private readonly chat: SupportChat, private readonly isOpen: () => boolean) {}

  start(): void {
    document.addEventListener('visibilitychange', this.onVisibility);
    this.tick();
  }

  stop(): void {
    document.removeEventListener('visibilitychange', this.onVisibility);
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  /** Poll now and reschedule (the chat was just opened, say). */
  tick(): void {
    if (this.timer) clearTimeout(this.timer);
    if (!document.hidden) void this.chat.refresh();
    this.timer = setTimeout(() => this.tick(), this.isOpen() ? 5_000 : 60_000);
  }
}

export { ICONS, styles };
