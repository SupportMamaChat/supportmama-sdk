/**
 * The <script> build: sets `window.SupportMama`, and starts on its own when
 * the tag carries the settings:
 *
 *   <script src="…/supportmama.js" data-endpoint="https://…" data-app-key="smk_…"></script>
 */
import SupportMama from './index';

declare global {
  interface Window {
    SupportMama: typeof SupportMama;
  }
}

window.SupportMama = SupportMama;

const script = document.currentScript as HTMLScriptElement | null;
const endpoint = script?.dataset.endpoint;
if (endpoint) {
  const d = script!.dataset;
  SupportMama.init({
    endpoint,
    appKey: d.appKey,
    appName: d.appName,
    accent: d.accent,
    position: d.position === 'left' ? 'left' : 'right',
    theme: (d.theme as 'auto' | 'light' | 'dark') ?? 'auto',
    title: d.title,
    launcher: d.launcher !== 'false',
  });
}
