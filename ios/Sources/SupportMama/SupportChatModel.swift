import Foundation
import UIKit
import UserNotifications

/// The one conversation this install has: what the screen shows, what the
/// badge reads, and what the background check updates.
@MainActor
public final class SupportChatModel: ObservableObject {
    public static let shared = SupportChatModel()

    @Published public private(set) var messages: [SupportMessage]
    /// A team reply newer than the user has seen.
    @Published public private(set) var unread: Bool = false
    /// The last refresh failed.
    @Published public private(set) var offline = false
    /// Why the last send failed: `slow_down`, `not_connected`, `network`, …
    @Published public private(set) var lastError: String?
    @Published public var name: String {
        didSet {
            let trimmed = String(name.prefix(40))
            if trimmed != name { name = trimmed }
            store.name = trimmed
        }
    }

    private(set) var config: SupportMama.Config?
    /// The chat is on screen: replies are read as they arrive.
    var visible = false {
        didSet { if visible { markSeen() } }
    }

    let store: Store
    private var refreshTask: Task<[SupportMessage], Never>?

    init(store: Store = Store()) {
        self.store = store
        self.messages = store.cached()
        self.name = store.name
        self.unread = computeUnread()
    }

    func configure(_ config: SupportMama.Config) {
        self.config = config
        syncPush()
    }

    private func api() -> Api? {
        guard let config else { return nil }
        return Api(endpoint: config.endpoint, credentials: store.credentials(), appKey: config.appKey)
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    public func send(_ text: String, image: UIImage? = nil) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty || image != nil else { return }
        let localId = UUID()
        let jpeg = image.flatMap { Images.jpeg($0) }
        let file = jpeg.flatMap { store.keepImage($0, for: localId) }
        let pending = SupportMessage(serverId: 0, localId: localId, fromTeam: false, text: trimmed, at: Date(),
                                     hasImage: jpeg != nil, imageFile: file, state: .sending)
        change { $0 + [pending] }
        Task { await deliver(pending, image: jpeg) }
        askForNotificationsOnce()
    }

    public func retry(_ localId: UUID) {
        guard let failed = messages.first(where: { $0.localId == localId && $0.state == .failed }) else { return }
        var pending = failed
        pending.state = .sending
        change { $0.map { $0.localId == localId ? pending : $0 } }
        let jpeg = failed.imageFile.flatMap { try? Data(contentsOf: store.imageURL($0)) }
        Task { await deliver(pending, image: jpeg) }
    }

    private func deliver(_ pending: SupportMessage, image: Data?) async {
        guard let api = api() else {
            fail(pending, code: "not_configured")
            return
        }
        let context = DeviceContext.line(attributes: config?.attributes ?? { [:] })
        do {
            let wire = try await api.send(text: pending.text, context: context, name: name.isEmpty ? nil : name, image: image)
            var sent = wire.message
            sent.localId = pending.localId
            sent.imageFile = pending.imageFile
            lastError = nil
            offline = false
            change { list in
                // A poll may have brought it back already, without the local picture.
                if list.contains(where: { $0.serverId == sent.serverId }) {
                    return list.filter { $0.localId != pending.localId }
                        .map { $0.serverId == sent.serverId ? sent : $0 }
                }
                return list.map { $0.localId == pending.localId ? sent : $0 }
            }
            // The first send makes the conversation a push token attaches to.
            await syncPush().value
            if !store.pushActive { BackgroundRefresh.schedule(after: config?.backgroundCheckMinutes ?? 30) }
        } catch {
            fail(pending, code: (error as? Api.Failure)?.code ?? "network")
        }
    }

    private func fail(_ pending: SupportMessage, code: String) {
        lastError = code
        var failed = pending
        failed.state = .failed
        change { $0.map { $0.localId == pending.localId ? failed : $0 } }
    }

    // ── Receiving ───────────────────────────────────────────────────────────

    /// Pulls what's new; returns the team replies this install didn't have.
    @discardableResult
    public func refresh() async -> [SupportMessage] {
        if let refreshTask { return await refreshTask.value }
        let task = Task { await fetchNew() }
        refreshTask = task
        let result = await task.value
        refreshTask = nil
        return result
    }

    private func fetchNew() async -> [SupportMessage] {
        // Nobody who never wrote has anything to fetch.
        guard !messages.isEmpty, let api = api() else { return [] }
        let cursor = store.readCursor
        let page: Api.Page
        do {
            page = try await api.fetch(after: cursor)
        } catch {
            offline = true
            return []
        }
        offline = false
        // A token taken back but not yet forgotten by the server may still read
        // as `push: true`: that mustn't switch the background refresh off.
        if let push = page.push { setPushActive(push && pushWanted() != nil) }
        if store.pushForgetPending { syncPush() }
        let incoming = page.messages
        guard !incoming.isEmpty else { return [] }
        let known = Set(messages.filter(\.isSent).map(\.serverId))
        // Move the cursor only by what a GET returned, never by our own sends:
        // a reply stored just before a send would otherwise be skipped.
        if let newest = incoming.map(\.id).max(), newest > cursor { store.readCursor = newest }
        change { Conversation.merge($0, incoming) }
        if visible { markSeen() } else { unread = computeUnread() }
        return incoming.filter { $0.from == "team" && !known.contains($0.id) }.map(\.message)
    }

    /// The user has the conversation on screen: everything in it is read.
    public func markSeen() {
        let newest = newestTeamId()
        if newest > store.lastSeen { store.lastSeen = newest }
        unread = false
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [BackgroundRefresh.notificationId])
        // Pushes get identifiers from the system: find ours by their marker.
        center.getDeliveredNotifications { delivered in
            let ours = delivered.filter { Push.isOurs($0.request.content.userInfo) }.map(\.request.identifier)
            if !ours.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ours) }
        }
    }

    // ── Push (docs/protocol.md, "PUT /v1/push") ─────────────────────────────────────────────────

    /// The newest queued sync: each waits for the one before, so a PUT and a
    /// DELETE reach the server in the order the app asked for them.
    private var pushSync: Task<Void, Never>?

    func setPushToken(_ token: PushToken) {
        store.pushToken = token
        syncPush()
    }

    func clearPushToken() {
        if store.pushToken != nil || store.pushRegistered != nil { store.pushForgetPending = true }
        store.pushToken = nil
        store.pushRegistered = nil
        setPushActive(false)
        syncPush()
    }

    /// The token the server should have: none when the app took it back or turned notifications off.
    private func pushWanted() -> PushToken? {
        config?.notifications == false ? nil : store.pushToken
    }

    /// Queues a sync of the push token behind any already running.
    @discardableResult
    func syncPush() -> Task<Void, Never> {
        let previous = pushSync
        let task = Task { [weak self] in
            await previous?.value
            await self?.syncPushNow()
        }
        pushSync = task
        return task
    }

    /// Sends the token when it's new, changed, or a week old (once there's a
    /// conversation to attach it to), or asks the server to forget it when the
    /// app took it back or turned notifications off. The server's answer
    /// decides whether the background refresh runs.
    private func syncPushNow() async {
        guard let api = api() else { return }
        guard let token = pushWanted() else {
            if config?.notifications == false, store.pushRegistered != nil { store.pushForgetPending = true }
            guard store.pushForgetPending else { return }
            do {
                try await api.forgetPush()
                store.pushForgetPending = false
                store.pushRegistered = nil
                setPushActive(false)
            } catch {
                // Tried again on the next launch or refresh.
            }
            return
        }
        guard messages.contains(where: \.isSent),
              Push.registrationDue(token: token.token, registered: store.pushRegistered, now: Date()),
              let push = try? await api.registerPush(token) else { return }
        // Taken back or replaced while the PUT was out: the sync queued behind
        // this one sends the DELETE or the new token.
        guard pushWanted()?.token == token.token else { return }
        store.pushForgetPending = false
        store.pushRegistered = (token.token, Date())
        setPushActive(push)
    }

    private func setPushActive(_ active: Bool) {
        guard store.pushActive != active else { return }
        store.pushActive = active
        if active {
            BackgroundRefresh.cancel()
        } else if newestActivity != nil {
            BackgroundRefresh.schedule(after: config?.backgroundCheckMinutes ?? 30)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private func newestTeamId() -> Int64 {
        messages.filter(\.fromTeam).map(\.serverId).max() ?? 0
    }

    private func computeUnread() -> Bool {
        newestTeamId() > store.lastSeen
    }

    private func change(_ transform: ([SupportMessage]) -> [SupportMessage]) {
        messages = transform(messages)
        store.save(messages)
    }

    /// After the first message, once: replies can then arrive as notifications.
    private func askForNotificationsOnce() {
        guard config?.notifications == true, !store.askedNotifications else { return }
        store.askedNotifications = true
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// For the background check: newest activity, to stop checking after a while.
    var newestActivity: Date? { messages.map(\.at).max() }
}
