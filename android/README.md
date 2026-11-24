# SupportMama for Android

A drop-in "Chat with us" screen; your team answers from Telegram or Slack.
Get an app key at [supportmama.pages.dev](https://supportmama.pages.dev/dashboard/).
Kotlin, Jetpack Compose, minSdk 24. It adds no HTTP client and no Firebase.
MIT licensed.

## Add it

From [JitPack](https://jitpack.io/#hsyvy/supportmama-sdk):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }
// app/build.gradle.kts
implementation("com.github.hsyvy.supportmama-sdk:supportmama:v0.3.0")
```

Or build it locally with `./gradlew :supportmama:publishToMavenLocal` and use
`implementation("dev.supportmama:supportmama:0.3.0")` with `mavenLocal()`.

## Use it

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SupportMama.init(this, SupportMama.Config(
            endpoint = "https://supportmama.pages.dev",
            appKey = "smk_…", // from the dashboard
        ))
    }
}

// Anywhere:
SupportMama.open(context)                                  // the chat screen
SupportMama.open(context, draft = "My order didn't arrive") // with text already typed
```

Call `init` from `Application.onCreate`, so background reply checks, which can
start in a fresh process, find the configuration.

### Unread badge

```kotlin
val unread by SupportMama.hasUnread(context).collectAsStateWithLifecycle()
BadgedBox(badge = { if (unread) Badge() }) { Button(onClick = { SupportMama.open(context) }) { Text("Help") } }
```

New replies are fetched when the app comes back to the foreground (at most once
a minute), every 5 s while the chat is open, and every 30 min in the
background for a week after the last message.

### Inside your own Compose navigation

```kotlin
composable("support") { SupportChat(onBack = { nav.popBackStack() }) }
```

`SupportChat` uses your `MaterialTheme`. The standalone activity uses dynamic
colours on Android 12+ and follows the system's dark mode.

### Options

```kotlin
SupportMama.Config(
    endpoint = "https://…",
    title = "Help",                         // top bar
    headline = "Talk to the makers",        // big first line
    intro = "We usually reply within a day.",
    teamLabel = "Acme team",                // label over your replies + notification title
    askForName = true,                      // optional "what should we call you?" field
    screenshots = true,                     // the attach button
    notifications = true,                   // reply notifications (background checks, or push)
    notificationIcon = R.drawable.ic_stat_chat,
    email = "help@acme.com",                // "Or email …" on the empty screen
    attributes = { mapOf("plan" to billing.plan, "user" to account.id) },
)
SupportMama.setUserName(context, account.displayName)   // names their Telegram topic
```

`attributes` are added to the context line your team sees:
`ℹ️ Acme 2.3.1 (41) · Android 14 · Google Pixel 8 · en-IN · plan: pro · user: 8812`.
That line is posted again whenever it changes.

### Push instead of background checks (optional)

By default the app checks for replies every 15–30 minutes while a
conversation is live. If your app already uses Firebase Cloud Messaging,
replies can arrive by push within seconds instead:

1. Give SupportMama your FCM keys: on the dashboard, **Push
   notifications → Firebase Cloud Messaging**. Use a service account
   with only the *Firebase Cloud Messaging API Admin* role.
2. Hand SupportMama your FCM token, and its pushes, in your existing service:

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = SupportMama.setPushToken(this, token)
    override fun onMessageReceived(message: RemoteMessage) {
        if (SupportMama.handlePush(this, message.data)) return
        // your own pushes
    }
}

// Application.onCreate, after SupportMama.init:
FirebaseMessaging.getInstance().token.addOnSuccessListener { SupportMama.setPushToken(this, it) }
```

The library has no Firebase dependency: it takes the token and the data as
plain strings.

- **When the token is sent:** it reaches the server once the user has written
  in, then again when it changes or a week has passed.
- **What a push does:** `handlePush` fetches the reply from your server and
  posts the usual notification, unless the chat is on screen. So the
  notification shows the real text even when previews are turned off.
- **Switching:** the background check stops while the server confirms it
  pushes, and starts again whenever the server says pushes stopped working
  (a dead token, or broken keys).
- **Sign-out:** call `SupportMama.clearPushToken(context)` to stop pushes to
  this install.

### Strings and languages

Every string is a resource named `supportmama_*` (see
`supportmama/src/main/res/values/strings.xml`). Override any of them in your
app's `res/values-xx/strings.xml` to change the wording or add a language.

### Permissions

The library's manifest adds `INTERNET`, `ACCESS_NETWORK_STATE` (Android 14+
needs it for the network-constrained reply check) and `POST_NOTIFICATIONS`.
The notification permission is asked for once, right after the user's first
message. With `notifications = false` you can remove it:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" tools:node="remove" />
```

## Privacy

There's no account and no device id. On first use the app creates a random
UUID plus a 256-bit secret. The server stores the UUID and a SHA-256 of the
secret, the user's messages and the optional name, plus a hash of the IP for
rate limiting. Clearing the app's data starts a new, unlinked conversation.

## Sample

`sample/` shows the integration with an unread badge. Point it at hosted
SupportMama with your app key:

```bash
./gradlew :sample:installDebug -Psupportmama.endpoint=https://supportmama.pages.dev -Psupportmama.appKey=smk_…
```

For push, put your Firebase project's `google-services.json` in `sample/`;
`SampleMessagingService` shows the two lines above. Debug builds can also walk
the push path without Firebase, against a server whose FCM is a fake:

```bash
adb shell am broadcast -a dev.supportmama.sample.TRY_PUSH -p dev.supportmama.sample --es token fake-token-0123456789abcdef
adb shell am broadcast -a dev.supportmama.sample.TRY_PUSH -p dev.supportmama.sample --es supportmama reply --es text "New reply"
```

## Tests

```bash
./gradlew :supportmama:testDebugUnitTest
```
