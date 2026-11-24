/** Lives in a shadow root, so none of it leaks into the host page or the other way. */
export const styles = `
:host {
  --sm-accent: #2f6fed;
  --sm-accent-text: #fff;
  --sm-bg: #ffffff;
  --sm-surface: #f4f4f2;
  --sm-text: #1c1c1a;
  --sm-muted: #6b6b66;
  --sm-line: #e4e4df;
  --sm-danger: #c4320a;
  --sm-radius: 16px;
  all: initial;
  font: 15px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  color: var(--sm-text);
}
:host([data-theme="dark"]) {
  --sm-bg: #1b1b1a; --sm-surface: #2a2a28; --sm-text: #ececea; --sm-muted: #a0a09a; --sm-line: #33332f; --sm-danger: #ff7a59;
}
@media (prefers-color-scheme: dark) {
  :host([data-theme="auto"]) {
    --sm-bg: #1b1b1a; --sm-surface: #2a2a28; --sm-text: #ececea; --sm-muted: #a0a09a; --sm-line: #33332f; --sm-danger: #ff7a59;
  }
}
* { box-sizing: border-box; }
button { font: inherit; color: inherit; cursor: pointer; }
[hidden] { display: none !important; }

.launcher {
  position: fixed; bottom: 20px; right: 20px; width: 56px; height: 56px; border-radius: 50%;
  border: none; background: var(--sm-accent); color: var(--sm-accent-text);
  box-shadow: 0 6px 20px rgba(0,0,0,.18); display: grid; place-items: center; z-index: var(--sm-z, 2147483000);
  transition: transform .15s ease;
}
.launcher:hover { transform: scale(1.05); }
.launcher:focus-visible { outline: 3px solid var(--sm-accent); outline-offset: 3px; }
.launcher svg { width: 26px; height: 26px; }
.left .launcher { right: auto; left: 20px; }
.badge {
  position: absolute; top: 4px; right: 4px; width: 14px; height: 14px; border-radius: 50%;
  background: #e5484d; border: 2px solid var(--sm-accent);
}

.panel {
  display: flex; flex-direction: column; background: var(--sm-bg); color: var(--sm-text);
  width: 100%; height: 100%; overflow: hidden;
}
.floating .panel {
  position: fixed; bottom: 88px; right: 20px; width: 380px; height: min(620px, calc(100vh - 120px));
  border-radius: var(--sm-radius); border: 1px solid var(--sm-line);
  box-shadow: 0 12px 40px rgba(0,0,0,.2); z-index: var(--sm-z, 2147483000);
}
.floating.left .panel { right: auto; left: 20px; }
@media (max-width: 480px) {
  .floating .panel { inset: 0; width: 100%; height: 100%; border-radius: 0; border: none; }
}

header {
  display: flex; align-items: center; gap: 8px; padding: 14px 16px;
  background: var(--sm-accent); color: var(--sm-accent-text);
}
header .title { flex: 1; font-weight: 650; font-size: 16px; }
.icon-btn {
  background: none; border: none; width: 32px; height: 32px; border-radius: 8px;
  display: grid; place-items: center; color: inherit; padding: 0;
}
.icon-btn:hover { background: rgba(127,127,127,.15); }
.icon-btn svg { width: 20px; height: 20px; }
.inline header { display: none; }

.banner { padding: 8px 16px; font-size: 13px; background: var(--sm-surface); color: var(--sm-muted); border-bottom: 1px solid var(--sm-line); }
.list { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 6px; overscroll-behavior: contain; }

.empty { margin: auto 0; text-align: center; padding: 8px 12px; }
.empty h2 { margin: 0 0 6px; font-size: 20px; }
.empty p { margin: 0 0 16px; color: var(--sm-muted); }
.empty input {
  width: 100%; font: inherit; color: var(--sm-text); background: var(--sm-surface);
  border: 1px solid var(--sm-line); border-radius: 10px; padding: 10px 12px;
}

.who { font-size: 12px; color: var(--sm-muted); margin: 8px 4px 0; }
.msg { max-width: 82%; display: flex; flex-direction: column; }
.msg.user { align-self: flex-end; align-items: flex-end; }
.msg.team { align-self: flex-start; align-items: flex-start; }
.bubble {
  padding: 8px 12px; border-radius: 16px; white-space: pre-wrap; overflow-wrap: anywhere;
  background: var(--sm-surface);
}
.user .bubble { background: var(--sm-accent); color: var(--sm-accent-text); border-bottom-right-radius: 4px; }
.team .bubble { border-bottom-left-radius: 4px; }
.bubble a { color: inherit; }
.shot { display: block; max-width: 220px; max-height: 260px; border-radius: 12px; margin-bottom: 4px; border: 1px solid var(--sm-line); }
.shot-note { font-size: 13px; opacity: .85; }
.meta { font-size: 11px; color: var(--sm-muted); margin: 2px 6px 0; }
.failed .bubble { opacity: .6; }
.retry { background: none; border: none; color: var(--sm-danger); font-size: 12px; padding: 2px 6px; }

.staged { display: flex; align-items: center; gap: 8px; padding: 8px 12px 0; }
.staged img { height: 48px; border-radius: 8px; border: 1px solid var(--sm-line); }
.staged span { flex: 1; font-size: 13px; color: var(--sm-muted); }

.composer { display: flex; align-items: flex-end; gap: 6px; padding: 10px 12px 12px; border-top: 1px solid var(--sm-line); }
.composer textarea {
  flex: 1; resize: none; font: inherit; color: var(--sm-text); background: var(--sm-surface);
  border: 1px solid transparent; border-radius: 18px; padding: 8px 14px; max-height: 140px; min-height: 38px; line-height: 1.4;
}
.composer textarea:focus { outline: none; border-color: var(--sm-accent); }
.composer .icon-btn { color: var(--sm-muted); height: 38px; }
.composer .send { color: var(--sm-accent); }
.composer .send:disabled { opacity: .4; cursor: default; }
.toast { margin: 0 12px; padding: 8px 12px; font-size: 13px; border-radius: 10px; background: var(--sm-surface); color: var(--sm-danger); }
`;
