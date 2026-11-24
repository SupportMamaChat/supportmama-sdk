import Foundation
import Security

/// Everything kept on the device: the identity (Keychain), the conversation
/// (a JSON file, so the screen opens on it offline), screenshots, and the read
/// markers (UserDefaults). All under names starting with "supportmama".
final class Store {
    private let defaults: UserDefaults
    private let directory: URL
    private let keychainService: String

    init(defaults: UserDefaults = .standard, directory: URL? = nil, keychainService: String = "dev.supportmama") {
        self.defaults = defaults
        self.keychainService = keychainService
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("supportmama", isDirectory: true)
        self.directory = base
        try? FileManager.default.createDirectory(at: base.appendingPathComponent("images"), withIntermediateDirectories: true)
    }

    // ── Identity ────────────────────────────────────────────────────────────

    /// `<id>.<secret>`, made on first use. The server stores only a hash of the
    /// secret. Keychain items outlive a reinstall, so the conversation does too.
    func credentials() -> String {
        if let existing = keychainRead() { return existing }
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let secret = Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        let value = "\(UUID().uuidString.lowercased()).\(secret)"
        keychainWrite(value)
        return keychainRead() ?? value
    }

    private var keychainQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: keychainService,
         kSecAttrAccount as String: "credentials"]
    }

    private func keychainRead() -> String? {
        var query = keychainQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func keychainWrite(_ value: String) {
        var item = keychainQuery
        item[kSecValueData as String] = Data(value.utf8)
        // Background refresh runs while the phone may be locked.
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemDelete(keychainQuery as CFDictionary)
        SecItemAdd(item as CFDictionary, nil)
    }

    // ── Conversation ────────────────────────────────────────────────────────

    private var messagesFile: URL { directory.appendingPathComponent("messages.json") }

    func cached() -> [SupportMessage] {
        guard let data = try? Data(contentsOf: messagesFile),
              let list = try? JSONDecoder().decode([SupportMessage].self, from: data) else { return [] }
        // A send cut off by the app being closed shows as unsent, to retry.
        return list.map { m in
            var m = m
            if m.state == .sending { m.state = .failed }
            return m
        }
    }

    func save(_ messages: [SupportMessage]) {
        guard let data = try? JSONEncoder().encode(messages) else { return }
        try? data.write(to: messagesFile, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func keepImage(_ jpeg: Data, for localId: UUID) -> String? {
        let name = "\(localId.uuidString).jpg"
        do {
            try jpeg.write(to: imageURL(name), options: .atomic)
            return name
        } catch {
            return nil
        }
    }

    func imageURL(_ name: String) -> URL {
        directory.appendingPathComponent("images", isDirectory: true).appendingPathComponent(name)
    }

    // ── Markers ─────────────────────────────────────────────────────────────

    /// The largest id a GET returned. Never moved by our own sends.
    var readCursor: Int64 {
        get { Int64(defaults.integer(forKey: "supportmama.cursor")) }
        set { defaults.set(Int(newValue), forKey: "supportmama.cursor") }
    }

    /// The newest team reply the user has seen.
    var lastSeen: Int64 {
        get { Int64(defaults.integer(forKey: "supportmama.seen")) }
        set { defaults.set(Int(newValue), forKey: "supportmama.seen") }
    }

    var name: String {
        get { defaults.string(forKey: "supportmama.name") ?? "" }
        set { defaults.set(newValue, forKey: "supportmama.name") }
    }

    var askedNotifications: Bool {
        get { defaults.bool(forKey: "supportmama.askedNotifications") }
        set { defaults.set(newValue, forKey: "supportmama.askedNotifications") }
    }

    // ── Push (docs/protocol.md, "PUT /v1/push") ─────────────────────────────────────────────────

    /// The token the app handed over, if any.
    var pushToken: PushToken? {
        get { defaults.data(forKey: "supportmama.pushToken").flatMap { try? JSONDecoder().decode(PushToken.self, from: $0) } }
        set { defaults.set(newValue.flatMap { try? JSONEncoder().encode($0) }, forKey: "supportmama.pushToken") }
    }

    /// The token the server last accepted, and when: re-registered when it changes, or weekly.
    var pushRegistered: (token: String, at: Date)? {
        get {
            guard let token = defaults.string(forKey: "supportmama.pushRegistered") else { return nil }
            return (token, Date(timeIntervalSince1970: defaults.double(forKey: "supportmama.pushRegisteredAt")))
        }
        set {
            defaults.set(newValue?.token, forKey: "supportmama.pushRegistered")
            defaults.set(newValue?.at.timeIntervalSince1970 ?? 0, forKey: "supportmama.pushRegisteredAt")
        }
    }

    /// The token was taken back (sign-out, or notifications turned off) and the
    /// server hasn't confirmed forgetting it yet: DELETE /v1/push is retried until it has.
    var pushForgetPending: Bool {
        get { defaults.bool(forKey: "supportmama.pushForgetPending") }
        set { defaults.set(newValue, forKey: "supportmama.pushForgetPending") }
    }

    /// The server said it pushes replies to this install, so the background refresh is off.
    var pushActive: Bool {
        get { defaults.bool(forKey: "supportmama.pushActive") }
        set { defaults.set(newValue, forKey: "supportmama.pushActive") }
    }
}
