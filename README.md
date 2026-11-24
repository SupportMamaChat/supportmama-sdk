# SupportMama SDKs

**In-app support chat that your team answers from Telegram or Slack.**

These are the open-source (MIT) client SDKs for
[SupportMama](https://supportmama.pages.dev). Give your Android, iOS or web
app a "Chat with us" screen; every user who writes in gets their own thread
in your Telegram group or Slack channel, and your reply shows up in the app
within seconds.

```
 your app (these SDKs)                    SupportMama                    your team
 Android · iOS · Web ──HTTPS──▶  supportmama.pages.dev  ──▶  Telegram group: a topic per user
          ▲                                                  Slack channel: a thread per user
          └──── replies, in-app + notification ◀──────────── you reply in the thread
```

## 1. Get an app key (2 minutes)

1. Sign in at **[supportmama.pages.dev/dashboard](https://supportmama.pages.dev/dashboard/)**.
2. **Create an app.** You get an app key that starts with `smk_`. It's public:
   it ships inside your app and only says which app a message is for.
3. **Connect your team's chat:**
   - **Telegram:** add the SupportMama bot to a group as an admin with
     *Manage topics*, turn on Topics, and send the `/connect <code>` the
     dashboard shows you.
   - **Slack:** click "Add to Slack" and pick a channel.

## 2. Add the SDK

The endpoint is always `https://supportmama.pages.dev`.

**Android** ([android/README.md](android/README.md)): Kotlin, Compose, minSdk 24

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
// app/build.gradle.kts
implementation("com.github.hsyvy:supportmama-sdk:v0.3.0")
```

```kotlin
// Application.onCreate
SupportMama.init(this, SupportMama.Config(endpoint = "https://supportmama.pages.dev", appKey = "smk_…"))
// From your "Help" button
SupportMama.open(context)
```

**iOS** ([ios/README.md](ios/README.md)): Swift Package
`https://github.com/hsyvy/supportmama-sdk`, iOS 16+

```swift
SupportMama.configure(.init(endpoint: URL(string: "https://supportmama.pages.dev")!, appKey: "smk_…"))
SupportMama.present(from: viewController)        // or .sheet { SupportChatView() }
```

**Web** ([web/README.md](web/README.md)): add your site under *Web origins* in the dashboard

```html
<script src="https://supportmama.pages.dev/sdk/supportmama.js"
        data-endpoint="https://supportmama.pages.dev" data-app-key="smk_…"></script>
```

**Anything else** (Flutter, React Native, a backend): it's two HTTP
endpoints, see [docs/protocol.md](docs/protocol.md).

## 3. Try it

Open the chat in your app and send a message. A new topic (Telegram) or
thread (Slack) appears for that user with their app version, OS and device.
Reply in it; the reply lands in the app, and a 👍 appears on your message
once it's delivered.

## Using a coding agent?

Point Claude Code, Cursor, Codex or any other agent at
**[AGENTS.md](AGENTS.md)** (also at
[supportmama.pages.dev/agents.md](https://supportmama.pages.dev/agents.md)).
It has the exact steps, per platform, and how to check the integration works.

## What's here

| Path | What it is |
| --- | --- |
| [`android/`](android/) | Kotlin library (drop-in activity or Compose screen) and a sample app. |
| [`ios/`](ios/), [`Package.swift`](Package.swift) | Swift package (SwiftUI, with a UIKit wrapper). |
| [`web/`](web/) | Script tag / npm widget, `<supportmama-chat>` element, headless core. |
| [`docs/protocol.md`](docs/protocol.md) | The HTTP API, for writing another client. |

## Privacy

There are no user accounts. Each install makes a random id and secret on
first use; the server stores only a hash of the secret. Your team's platform
tokens never reach the app.

## Licence

MIT. See [LICENSE](LICENSE). The SupportMama service itself is not open
source.
