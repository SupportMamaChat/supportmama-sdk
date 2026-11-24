import SwiftUI
import UIKit
import UserNotifications

/// An in-app "Chat with us" whose conversations your team answers from
/// Telegram or Slack.
///
/// ```swift
/// // At launch (App.init or application(_:didFinishLaunchingWithOptions:))
/// SupportMama.configure(.init(endpoint: URL(string: "https://supportmama.you.workers.dev")!, appKey: "smk_…"))
///
/// // UIKit
/// SupportMama.present(from: viewController)
///
/// // SwiftUI
/// .sheet(isPresented: $showHelp) { SupportChatView() }
/// ```
public enum SupportMama {

    public struct Config {
        /// Your SupportMama server.
        public var endpoint: URL
        /// Hosted SupportMama: the app key from the dashboard. Self-hosted: nil.
        public var appKey: String?
        /// Title. Default "Chat with us".
        public var title: String?
        /// First line of the empty screen. Default "Talk to the team".
        public var headline: String?
        /// Under the headline.
        public var intro: String?
        /// Label over your team's replies. Default "Team".
        public var teamLabel: String?
        /// Offer a "What should we call you?" field; the name labels the thread for your team.
        public var askForName: Bool
        /// Let users attach a screenshot.
        public var screenshots: Bool
        /// Notify when a reply arrives while the chat isn't on screen. By default
        /// the app checks in the background (set up with
        /// `registerBackgroundRefresh()`); with `setPushToken`, replies come by
        /// push instead. False turns both off.
        public var notifications: Bool
        /// The earliest gap between background checks. iOS decides the actual timing.
        public var backgroundCheckMinutes: Int
        /// Background checks stop this many days after the last message.
        public var keepCheckingDays: Int
        /// Your brand colour for buttons and your user's bubbles.
        public var accentColor: Color?
        /// Extra facts for your team, sent with the app version, iOS version,
        /// device and language whenever any of them change. E.g. `{ ["plan": user.plan] }`.
        public var attributes: () -> [String: String]
        /// Opens the chat when the user taps a reply notification. SwiftUI apps
        /// set this to show their own sheet; without it, SupportMama presents
        /// the chat over the top view controller.
        public var openChat: (@MainActor () -> Void)?

        public init(
            endpoint: URL,
            appKey: String? = nil,
            title: String? = nil,
            headline: String? = nil,
            intro: String? = nil,
            teamLabel: String? = nil,
            askForName: Bool = true,
            screenshots: Bool = true,
            notifications: Bool = true,
            backgroundCheckMinutes: Int = 30,
            keepCheckingDays: Int = 7,
            accentColor: Color? = nil,
            attributes: @escaping () -> [String: String] = { [:] },
            openChat: (@MainActor () -> Void)? = nil
        ) {
            self.endpoint = endpoint
            self.appKey = appKey
            self.title = title
            self.headline = headline
            self.intro = intro
            self.teamLabel = teamLabel
            self.askForName = askForName
            self.screenshots = screenshots
            self.notifications = notifications
            self.backgroundCheckMinutes = backgroundCheckMinutes
            self.keepCheckingDays = keepCheckingDays
            self.accentColor = accentColor
            self.attributes = attributes
            self.openChat = openChat
        }
    }

    /// Call once at launch.
    @MainActor
    public static func configure(_ config: Config) {
        SupportChatModel.shared.configure(config)
    }

    /// Presents the chat as a sheet, optionally with a draft in the message box (it isn't sent).
    @MainActor
    public static func present(from presenter: UIViewController, draft: String? = nil, animated: Bool = true) {
        presenter.present(makeViewController(draft: draft), animated: animated)
    }

    /// The chat screen, with its own navigation bar and Close button, for presenting yourself.
    @MainActor
    public static func makeViewController(draft: String? = nil) -> UIViewController {
        let controller = UIHostingController(rootView: SupportChatView(draft: draft))
        controller.modalPresentationStyle = .pageSheet
        return controller
    }

    /// The conversation model, observable from SwiftUI: messages, `unread`, `offline`.
    @MainActor
    public static var chat: SupportChatModel { SupportChatModel.shared }

    /// A team reply the user hasn't seen. Observe `SupportMama.chat.$unread` for changes.
    @MainActor
    public static var hasUnread: Bool { SupportChatModel.shared.unread }

    /// The name your team sees on this conversation.
    @MainActor
    public static func setUserName(_ name: String) {
        SupportChatModel.shared.name = name
    }

    /// Checks for replies now (e.g. when your app comes to the foreground, for a badge).
    @MainActor
    public static func refresh() async {
        _ = await SupportChatModel.shared.refresh()
    }

    /// Lets SupportMama check for replies while your app is closed. Call before
    /// your app finishes launching, after adding `dev.supportmama.refresh` to
    /// `BGTaskSchedulerPermittedIdentifiers` in Info.plist and turning on the
    /// "Background fetch" mode. Without that set-up this does nothing.
    public static func registerBackgroundRefresh() {
        BackgroundRefresh.register()
    }

    // ── Push (docs/protocol.md, "PUT /v1/push") ─────────────────────────────────────────────────
    //
    // Optional: replies arrive within seconds instead of whenever iOS runs the
    // background refresh. Needs the Push Notifications capability, keys on the
    // SupportMama server (dashboard → Push notifications), and these calls:
    //
    //     func application(_ app: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken token: Data) {
    //         SupportMama.setPushToken(token)
    //     }
    //     // UNUserNotificationCenterDelegate
    //     func userNotificationCenter(_ c: UNUserNotificationCenter, willPresent n: UNNotification) async -> UNNotificationPresentationOptions {
    //         await SupportMama.willPresent(n) ?? [.banner, .sound]
    //     }
    //     func userNotificationCenter(_ c: UNUserNotificationCenter, didReceive r: UNNotificationResponse) async {
    //         if await SupportMama.didReceive(r) { return }
    //     }

    /// The APNs device token, from `didRegisterForRemoteNotificationsWithDeviceToken`.
    /// It reaches the server once the user has written in. Until the server
    /// confirms it will push, and whenever it says pushes stopped working,
    /// the background refresh carries on as before.
    @MainActor
    public static func setPushToken(_ deviceToken: Data) {
        // Debug builds get tokens for Apple's sandbox host. A wrong guess costs
        // one retry on the server, which then remembers the right host.
        #if DEBUG
        let env = "sandbox"
        #else
        let env = "production"
        #endif
        SupportChatModel.shared.setPushToken(PushToken(kind: "apns", token: Push.hex(deviceToken), env: env))
    }

    /// For apps that use Firebase Messaging on iOS: the FCM registration token
    /// (`Messaging.messaging().token`, and `messaging(_:didReceiveRegistrationToken:)`).
    @MainActor
    public static func setPushToken(fcm token: String) {
        SupportChatModel.shared.setPushToken(PushToken(kind: "fcm", token: token, env: nil))
    }

    /// Stops pushes to this install, say when the user signs out; the background refresh takes over.
    @MainActor
    public static func clearPushToken() {
        SupportChatModel.shared.clearPushToken()
    }

    /// From `userNotificationCenter(_:willPresent:)`: nil if the notification
    /// isn't SupportMama's. For ours it fetches the reply, and shows the banner
    /// unless the chat is already on screen (where the reply just appears).
    @MainActor
    public static func willPresent(_ notification: UNNotification) -> UNNotificationPresentationOptions? {
        guard Push.isOurs(notification.request.content.userInfo) else { return nil }
        let chat = SupportChatModel.shared
        Task { await chat.refresh() }
        // Turned off in the config: a push the server sent before it heard stays quiet.
        if chat.config?.notifications == false { return [] }
        return chat.visible ? [] : [.banner, .list, .sound]
    }

    /// From `userNotificationCenter(_:didReceive:)`: false if the notification
    /// isn't SupportMama's. For ours it opens the chat and fetches the reply
    /// (`Config.openChat`, or presented over the top view controller).
    @MainActor
    public static func didReceive(_ response: UNNotificationResponse) async -> Bool {
        guard Push.isOurs(response.notification.request.content.userInfo) else { return false }
        let chat = SupportChatModel.shared
        // Open first: on a slow connection the fetch can take seconds, and the
        // reply appears in the open chat when it lands.
        if let openChat = chat.config?.openChat {
            openChat()
        } else if !chat.visible, let top = topViewController() {
            top.present(makeViewController(), animated: true)
        }
        await chat.refresh()
        return true
    }

    @MainActor
    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let windows = scenes.flatMap(\.windows)
        var top = (windows.first(where: \.isKeyWindow) ?? windows.first)?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
