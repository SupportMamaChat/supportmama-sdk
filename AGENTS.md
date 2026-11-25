# Adding SupportMama to an app: instructions for coding agents

You are adding an in-app support chat to an app. Users write from a
"Chat with us" screen; the app's team answers from a Telegram group or Slack
channel. Follow these steps in order. Don't skip the check at the end.

- Service endpoint: `https://supportmama.pages.dev` (always this; it is not a placeholder)
- Dashboard: `https://supportmama.pages.dev/dashboard/`
- SDK source and docs: `https://github.com/SupportMamaChat/supportmama-sdk`
- HTTP API, for other platforms: `https://github.com/SupportMamaChat/supportmama-sdk/blob/main/docs/protocol.md`

## 0. What you need from the human

You need an **app key**: a string that starts with `smk_`. It comes from the
dashboard and only a person signed in there can create one.

- If the human gave you one, use it.
- If not, **ask for it** and tell them how to get it: sign in at the dashboard,
  create an app under **New app**, copy its key, and connect Telegram or Slack on the same
  page. Never invent a key, and don't block on it: you can write the code with
  the key in one config value and fill it in when they reply.

The key is public (it ships inside the app, like a Firebase API key). It's
fine to commit it or put it in a build config field. Don't put it in a URL.

Before a message can reach anyone, the app must also be **connected** to a
team channel in the dashboard (Telegram `/connect <code>` or "Add to Slack").
Until then, sends fail with `503 not_connected`. That's the human's step.

## 1. Pick the platform

Look at the project. Use the native SDK where there is one:

| Project | SDK |
| --- | --- |
| Android (Gradle, Kotlin or Java) | [Android](#android) |
| iOS (Xcode / Swift Package Manager) | [iOS](#ios) |
| Website or web app | [Web](#web) |
| Flutter, React Native, desktop, backend | [Other platforms](#other-platforms) |

A project with several apps (e.g. `android/` and `ios/`) gets each one, with
the same app key or one key per app, as the human prefers.

## Android

Requirements: minSdk 24+, compileSdk 36, Kotlin 2.3+ (the library is built
with Kotlin 2.4 and Jetpack Compose; the host app doesn't have to use Compose).

1. Add JitPack to the repositories in `settings.gradle.kts` (keep the existing
   ones):

   ```kotlin
   dependencyResolutionManagement {
       repositories {
           google()
           mavenCentral()
           maven("https://jitpack.io")
       }
   }
   ```

   If the project uses a version catalog, add
   `supportmama = { group = "com.github.SupportMamaChat", name = "supportmama-sdk", version = "v0.3.0" }`
   and reference it; otherwise:

2. Add the dependency to the app module:

   ```kotlin
   implementation("com.github.SupportMamaChat:supportmama-sdk:v0.3.0")
   ```

3. Initialise it in the `Application` subclass's `onCreate` (create one and
   register it in the manifest's `<application android:name=…>` if there isn't
   one). It must be `Application.onCreate`, not an activity: background reply
   checks start in a fresh process.

   ```kotlin
   import dev.supportmama.SupportMama

   class MyApp : Application() {
       override fun onCreate() {
           super.onCreate()
           SupportMama.init(this, SupportMama.Config(
               endpoint = "https://supportmama.pages.dev",
               appKey = "smk_…",
               teamLabel = "Acme team",          // optional: label over replies and on notifications
           ))
       }
   }
   ```

4. Open the chat from wherever the app offers help (settings screen, menu,
   "Contact us" row):

   ```kotlin
   SupportMama.open(context)
   ```

   Inside a Compose `NavHost` you can embed it instead:
   `composable("support") { SupportChat(onBack = { nav.popBackStack() }) }`.

5. Optional extras, only if they fit the app:
   - Name the thread when the app knows the user: `SupportMama.setUserName(context, name)`.
   - Facts for the team (plan, user id): `attributes = { mapOf("plan" to plan) }` in `Config`.
   - Unread badge: `SupportMama.hasUnread(context)` is a `StateFlow<Boolean>`.
   - FCM push for instant replies: see the Android README, "Push instead of background checks".

The library adds `INTERNET`, `ACCESS_NETWORK_STATE` and `POST_NOTIFICATIONS`
to the merged manifest. It has no Firebase or OkHttp dependency. If the app
uses R8 / ProGuard, no extra rules are needed (the library ships its own).

Full reference: [android/README.md](https://github.com/SupportMamaChat/supportmama-sdk/blob/main/android/README.md).

## iOS

Requirements: iOS 16+.

1. Add the package `https://github.com/SupportMamaChat/supportmama-sdk` from version
   `0.3.0`, product `SupportMama`. In an Xcode project, add it to the project's
   package dependencies (or in XcodeGen's `project.yml`, under `packages:`);
   in a `Package.swift`:
   `.package(url: "https://github.com/SupportMamaChat/supportmama-sdk", from: "0.3.0")`.

2. Configure at launch (`App.init` or `application(_:didFinishLaunchingWithOptions:)`):

   ```swift
   import SupportMama

   SupportMama.configure(.init(
       endpoint: URL(string: "https://supportmama.pages.dev")!,
       appKey: "smk_…"
   ))
   ```

3. Show it:

   ```swift
   // SwiftUI
   .sheet(isPresented: $showHelp) { SupportChatView() }
   // UIKit
   SupportMama.present(from: self)
   ```

4. Optional: background reply checks (Background Modes → Background fetch,
   `dev.supportmama.refresh` in `BGTaskSchedulerPermittedIdentifiers`, and
   `SupportMama.registerBackgroundRefresh()` at launch), or APNs push. See
   [ios/README.md](https://github.com/SupportMamaChat/supportmama-sdk/blob/main/ios/README.md).

## Web

1. The human must add the site's origin (e.g. `https://example.com`, and
   `http://localhost:5173` for development) under **Web origins** on the app's
   dashboard page. Without it the browser gets `403 origin_not_allowed`.

2. Add the script before `</body>`:

   ```html
   <script src="https://supportmama.pages.dev/sdk/supportmama.js"
           data-endpoint="https://supportmama.pages.dev"
           data-app-key="smk_…"></script>
   ```

   It adds a floating chat button. To open it from your own link instead,
   add `data-launcher="false"` and call `SupportMama.open()`.

In a framework app (React, Vue, Svelte…), load the same script once (e.g. in
`index.html` or the root layout). Full reference: [web/README.md](https://github.com/SupportMamaChat/supportmama-sdk/blob/main/web/README.md).

## Other platforms

The API is two HTTP endpoints. Read [docs/protocol.md](https://github.com/SupportMamaChat/supportmama-sdk/blob/main/docs/protocol.md) and
implement it:

- Generate once per install and store securely: `id` (UUID v4) and `secret`
  (32 random bytes, base64url). Send `Authorization: Bearer <id>.<secret>`.
- Send every request with `X-App-Key: smk_…`.
- `POST /v1/messages` with JSON `{ "text", "name"?, "context"? }` to send.
- `GET /v1/messages?after=<last id>` to receive; poll every 5 s while the chat
  is open.

## 2. Check that it works

Don't report the integration as done until you've checked it.

1. Build the app. Fix any build errors you introduced.
2. Run it (emulator, simulator or browser), open the chat, and send a message
   such as `Test from <your agent name>: integration check`.
3. Expected: the message shows as sent (no red "retry"), and in the team's
   Telegram group or Slack channel a new topic/thread appears with the
   message and a context line like
   `ℹ️ Acme 2.3.1 (41) · Android 14 · Pixel 8 · en-IN`. You can't see the
   team's chat yourself: ask the human to confirm it arrived and to **reply in
   that thread**.
4. The reply should appear in the app's chat within about 5 seconds while the
   chat is open.

You can also check the API directly, without the app:

```bash
ID=$(uuidgen | tr A-Z a-z); SECRET=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
curl -s https://supportmama.pages.dev/v1/messages \
  -H "Authorization: Bearer $ID.$SECRET" -H "X-App-Key: smk_…" \
  -H "Content-Type: application/json" -d '{"text":"curl test"}'
```

## Errors

Responses are JSON `{ "error": "<code>" }`.

| Code | Meaning | What to do |
| --- | --- | --- |
| `unknown_app` (401) | The app key is missing or wrong. | Check the key against the dashboard. |
| `not_connected` (503) | No Telegram group or Slack channel yet. | Ask the human to connect one in the dashboard. |
| `origin_not_allowed` (403) | Web only: the page's origin isn't listed. | Ask the human to add it under Web origins. |
| `unauthorized` (401) | Bad `Authorization`. | The SDKs handle this; in your own client, check the id/secret format. |
| `slow_down` (429) | Rate limit (20 messages / 10 min per user). | Wait; don't loop. |

## Reporting back

Tell the human: which files you changed, where the chat opens from in the
app, the result of the check above, and anything they still have to do (app
key, connecting Telegram/Slack, Web origins).
