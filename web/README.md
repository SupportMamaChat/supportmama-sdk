# SupportMama for the web

A "Chat with us" widget for websites and web apps; your team answers from
Telegram or Slack. About 8 KB gzipped, no dependencies, and Shadow DOM, so it
can't clash with your styles. MIT licensed.

## Add it

**Script tag**, the quickest way:

```html
<script src="https://supportmama.chat/sdk/supportmama.js"
        data-endpoint="https://supportmama.chat"
        data-app-key="smk_…"></script>
```

**npm** (not on the registry yet; install from this repo's `web/` folder, or
use the script tag):

```bash
npm install @supportmama/web
```

```js
import SupportMama from '@supportmama/web';

SupportMama.init({
  endpoint: 'https://supportmama.chat',
  appKey: 'smk_…',                           // from the dashboard
  appName: 'Acme 2.3.1',                     // first thing your team sees
  attributes: () => ({ plan: user.plan, user: user.id }),
});
```

Add your site's origin (e.g. `https://example.com`) under **Web origins** in
the dashboard, or the browser's requests are refused.

## Use it

```js
SupportMama.open();                          // from your own "Help" link
SupportMama.open('My order #123 didn’t arrive'); // with a draft (not sent)
SupportMama.close();
SupportMama.setUserName('Priya');            // labels the thread for your team
const stop = SupportMama.onUnread((unread) => badge.hidden = !unread);
```

Options for `init`:

| Option | Default | |
| --- | --- | --- |
| `endpoint` | required | `https://supportmama.chat` |
| `appKey` | required | The app key from the dashboard. |
| `appName` | the host name | Starts the context line, e.g. `Acme 2.3.1 · Chrome 131 · macOS · en-IN · /checkout`. |
| `attributes` | | Extra `key: value` facts for the context line. |
| `launcher` | `true` | Show the floating button. `false` to open only from your own button. |
| `position` | `'right'` | Or `'left'`. |
| `accent` | `#2f6fed` | Any CSS colour. |
| `theme` | `'auto'` | `'light'`, `'dark'`, or follow the system. |
| `title`, `headline`, `intro`, `teamLabel`, `placeholder`, `namePlaceholder` | English | The words on the screen. |
| `askForName` | `true` | Offer a "What should we call you?" field. |
| `screenshots` | `true` | Let users attach a picture (or paste one). |
| `zIndex` | very high | If something of yours must sit above the widget. |

### Inside your own layout

```html
<supportmama-chat endpoint="https://supportmama.chat" app-key="smk_…"
                  style="height: 600px"></supportmama-chat>
```

Attributes: `app-name`, `title`, `headline`, `intro`, `team-label`, `accent`,
`theme`, `ask-for-name="false"`, `screenshots="false"`.

### Your own UI

`@supportmama/web/core` is the protocol client alone (about 2 KB gzipped):

```js
import { SupportChat } from '@supportmama/web/core';
const chat = new SupportChat({ endpoint, appKey, context: () => 'Acme web' });
chat.subscribe(() => render(chat.messages, chat.unread));
await chat.send('Hello', optionalImageBlob);
await chat.refresh();   // call every few seconds while your chat is open
chat.markSeen();
```

## How it behaves

- On first use the browser makes an id and a secret and keeps them in
  `localStorage`. The same browser keeps the same conversation; there are no
  accounts.
- New replies are fetched every 5 s while the chat is open, every minute while
  it's closed (for the badge), and right away when the tab comes back. Nothing
  is fetched until the user has written.
- Messages that fail to send stay in the chat with "Retry".
- Screenshots are shrunk to 1600 px JPEGs before upload.

## Develop

```bash
npm install
npm run build   # dist/: the script-tag bundle and the npm modules
npm test        # builds; the end-to-end tests need the (private) server checkout and are skipped without it
```
