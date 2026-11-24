# SupportMama for iOS

A "Chat with us" screen in SwiftUI (usable from UIKit); your team answers from
Telegram or Slack. iOS 16+, no dependencies, no push set-up. MIT licensed.

## Add it

Xcode → **File → Add Package Dependencies…** →
`https://github.com/hsyvy/supportmama-sdk`, product **SupportMama**, from
version 0.3.0. (The `Package.swift` sits at the repository root.)

Or in a `Package.swift`:

```swift
.package(url: "https://github.com/hsyvy/supportmama-sdk", from: "0.3.0")
```

## Use it

```swift
import SupportMama

// At launch: App.init, or application(_:didFinishLaunchingWithOptions:)
SupportMama.configure(.init(
    endpoint: URL(string: "https://supportmama.pages.dev")!,
    appKey: "smk_…",                         // from the dashboard
    attributes: { ["plan": Account.current.plan] }
))
SupportMama.registerBackgroundRefresh()      // optional, see below
```

SwiftUI:

```swift
Button("Help") { showHelp = true }
    .sheet(isPresented: $showHelp) { SupportChatView() }

// Or push it inside your own NavigationStack:
NavigationLink("Help") { SupportChatContent() }
```

UIKit:

```swift
SupportMama.present(from: self)
SupportMama.present(from: self, draft: "My order didn't arrive")
```

Unread badge:

```swift
@ObservedObject var chat = SupportMama.chat
// …
.badge(chat.unread ? 1 : 0)
// and refresh when your app comes to the foreground:
.onChange(of: scenePhase) { if $0 == .active { Task { await SupportMama.refresh() } } }
```

## Config

| Option | Default | |
| --- | --- | --- |
| `endpoint` | required | `https://supportmama.pages.dev` |
| `appKey` | required | The app key from the dashboard (`smk_…`). |
| `title`, `headline`, `intro`, `teamLabel` | English | The words on the screen. |
| `askForName` | true | Offer a "What should we call you?" field. |
| `screenshots` | true | Let users attach a photo. |
| `notifications` | true | Reply notifications, from background checks or push. |
| `backgroundCheckMinutes` | 30 | The earliest gap between checks; iOS picks the actual time. |
| `keepCheckingDays` | 7 | Stop background checks this long after the last message. |
| `accentColor` | app tint | Buttons and the user's bubbles. |
| `attributes` | `[:]` | Extra facts for your team's context line. |
| `openChat` | nil | What a tap on a reply notification does. Default: present the chat over the top view controller. SwiftUI apps can show their own sheet. |

## Replies while the app is closed

By default SupportMama uses a background app refresh task to check for
replies and posts a local notification. To turn it on:

1. In your target's **Signing & Capabilities**, add **Background Modes** and
   tick **Background fetch**.
2. In Info.plist, add `dev.supportmama.refresh` to
   **Permitted background task scheduler identifiers**
   (`BGTaskSchedulerPermittedIdentifiers`).
3. Call `SupportMama.registerBackgroundRefresh()` before launch finishes.

Without these steps, nothing breaks: replies arrive whenever the app is open.
Notification permission is requested after the user's first message.

### Push (optional)

iOS runs background refresh when it chooses, sometimes hours later. With push,
a reply arrives within seconds:

1. Give SupportMama your push keys. On the dashboard, use **Push
   notifications → Apple Push (APNs)** with your `.p8` key. If your app uses
   Firebase Messaging, use the Firebase card instead, with your APNs key
   uploaded in Firebase.
2. Add the **Push Notifications** capability. Call
   `UIApplication.shared.registerForRemoteNotifications()` at launch, as apps
   with push already do.
3. Hand SupportMama the token and its notifications:

```swift
func application(_ app: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken token: Data) {
    SupportMama.setPushToken(token)                 // Firebase apps: SupportMama.setPushToken(fcm: fcmToken)
}

// UNUserNotificationCenterDelegate
func userNotificationCenter(_ c: UNUserNotificationCenter, willPresent n: UNNotification) async -> UNNotificationPresentationOptions {
    await SupportMama.willPresent(n) ?? [.banner, .sound]
}
func userNotificationCenter(_ c: UNUserNotificationCenter, didReceive r: UNNotificationResponse) async {
    if await SupportMama.didReceive(r) { return }
    // your own notifications
}
```

- **When the token is sent:** it reaches the server once the user has written
  in, then again when it changes or a week has passed.
- **APNs host:** debug builds register for Apple's sandbox host and release
  builds for production. If the guess is wrong, the server finds the right
  host and remembers it.
- **Switching:** the background refresh stops while the server confirms it
  pushes, and starts again whenever the server says pushes stopped working.
- **Tapping a notification:** `didReceive` opens the chat, through
  `Config.openChat` if you set it. It does the same for the background
  check's notifications.
- **Sign-out:** call `SupportMama.clearPushToken()` to stop pushes to this
  install.

## Notes

- The identity (`<uuid>.<secret>`) is kept in the Keychain, so the
  conversation survives a reinstall. Messages are kept in Application Support.
- The context line looks like `Acme 2.3.1 (41) · iOS 18.1 · iPhone16,1 · en-IN · plan: pro`.

## Develop

```bash
xcodebuild test -scheme SupportMama -destination 'platform=iOS Simulator,name=iPhone 16'
```

from the repository root. CI runs this on macOS.
