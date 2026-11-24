import BackgroundTasks
import Foundation
import UserNotifications

/// Checks for replies while the app is closed, and notifies. Without push, a
/// background app refresh task asks the server. It runs only while a
/// conversation is live, and stops `keepCheckingDays` after the last message.
/// iOS decides when it actually runs. While the server pushes replies
/// (`SupportMama.setPushToken`), it's off.
enum BackgroundRefresh {
    static let taskId = "dev.supportmama.refresh"
    static let notificationId = "dev.supportmama.reply"

    private static var registered = false

    static func register() {
        let permitted = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] ?? []
        // Registering an id missing from Info.plist crashes the app; skip instead.
        guard permitted.contains(taskId), !registered else {
            if !permitted.contains(taskId) {
                print("[SupportMama] Add \(taskId) to BGTaskSchedulerPermittedIdentifiers in Info.plist to get replies in the background.")
            }
            return
        }
        registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            handle(task)
        }
    }

    static func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskId)
    }

    static func schedule(after minutes: Int) {
        guard registered else { return }
        let request = BGAppRefreshTaskRequest(identifier: taskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(max(15, minutes) * 60))
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func handle(_ task: BGAppRefreshTask) {
        let work = Task { @MainActor in
            let chat = SupportChatModel.shared
            let config = chat.config
            let days = config?.keepCheckingDays ?? 7
            guard config?.notifications != false,
                  !chat.store.pushActive,
                  let newest = chat.newestActivity,
                  Date().timeIntervalSince(newest) < TimeInterval(days) * 86_400 else {
                task.setTaskCompleted(success: true)
                return
            }
            schedule(after: config?.backgroundCheckMinutes ?? 30)
            let replies = await chat.refresh()
            if let reply = replies.last, !chat.visible, chat.unread {
                await notify(title: config?.teamLabel ?? "Support", body: reply.text.isEmpty ? "📷 Screenshot" : reply.text)
            }
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = {
            work.cancel()
            task.setTaskCompleted(success: false)
        }
    }

    private static func notify(title: String, body: String) async {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = "supportmama"
        // So a tap opens the chat through SupportMama.didReceive, like a push.
        content.userInfo = ["supportmama": "reply"]
        let request = UNNotificationRequest(identifier: notificationId, content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}
