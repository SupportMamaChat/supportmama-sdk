/** Browser-only helpers: the context line and screenshot preparation. */

/**
 * The facts a team asks first, e.g.
 * `Acme 2.3.1 · Chrome 131 · macOS · en-IN · /checkout · plan: pro`.
 */
export function contextLine(appName: string | undefined, attributes?: () => Record<string, string | number | boolean | null | undefined>): string {
  const parts: string[] = [];
  parts.push(appName || location.hostname || 'Web');
  parts.push(...browserAndOs());
  if (navigator.language) parts.push(navigator.language);
  if (location.pathname && location.pathname !== '/') parts.push(location.pathname);
  try {
    for (const [k, v] of Object.entries(attributes?.() ?? {})) {
      if (k.trim() && v != null && String(v).trim()) parts.push(`${k}: ${v}`);
    }
  } catch {
    // A failing callback shouldn't stop the message.
  }
  return parts.join(' · ');
}

function browserAndOs(): string[] {
  const ua = navigator.userAgent;
  const browser =
    match(ua, /Edg\/(\d+)/, 'Edge') ||
    match(ua, /OPR\/(\d+)/, 'Opera') ||
    match(ua, /SamsungBrowser\/(\d+)/, 'Samsung Internet') ||
    match(ua, /Firefox\/(\d+)/, 'Firefox') ||
    match(ua, /Chrome\/(\d+)/, 'Chrome') ||
    (/Safari\//.test(ua) && match(ua, /Version\/(\d+(?:\.\d+)?)/, 'Safari')) ||
    'Browser';
  const os =
    (/iPhone|iPad|iPod/.test(ua) && match(ua, /OS (\d+)[_.](\d+)?/, 'iOS', (m) => `${m[1]}${m[2] ? `.${m[2]}` : ''}`)) ||
    match(ua, /Android (\d+(?:\.\d+)?)/, 'Android') ||
    (/Mac OS X/.test(ua) && 'macOS') ||
    (/Windows/.test(ua) && 'Windows') ||
    (/CrOS/.test(ua) && 'ChromeOS') ||
    (/Linux/.test(ua) && 'Linux') ||
    '';
  return [browser, os].filter(Boolean);
}

function match(ua: string, re: RegExp, name: string, version = (m: RegExpMatchArray) => m[1]): string | null {
  const m = ua.match(re);
  return m ? `${name} ${version(m)}` : null;
}

/**
 * Screenshots go up as a JPEG of at most 1600 px on the long side, like the
 * native SDKs: readable, and far under the server's 5 MB limit.
 */
export async function prepareImage(file: Blob, maxEdge = 1600): Promise<Blob> {
  if (!/^image\//.test(file.type)) throw new Error('not_an_image');
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    throw new Error('not_an_image');
  }
  const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
  if (scale === 1 && file.size < 1.5 * 1024 * 1024 && /^image\/(jpeg|png|webp)$/.test(file.type)) {
    bitmap.close();
    return file;
  }
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  const ctx = canvas.getContext('2d')!;
  ctx.fillStyle = '#fff'; // transparent PNGs would turn black as JPEG
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  return new Promise((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('not_an_image'))), 'image/jpeg', 0.85),
  );
}
