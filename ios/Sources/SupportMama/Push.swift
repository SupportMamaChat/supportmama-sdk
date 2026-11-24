import Foundation

/// The push token the app handed over (docs/protocol.md, "PUT /v1/push").
struct PushToken: Codable, Equatable {
    /// `apns` (the raw device token, hex) or `fcm` (apps on Firebase Messaging).
    let kind: String
    let token: String
    /// APNs only: `sandbox` or `production`, guessed from the build. The server
    /// corrects a wrong guess.
    let env: String?
}

/// The plain rules behind push, kept apart from UIKit so they can be unit-tested.
enum Push {
    /// Re-registering an unchanged token once a week tells the server the install is still around.
    static let reregisterAfter: TimeInterval = 7 * 86_400

    /// A SupportMama notification: APNs sends `{"supportmama": {"id": 42}}`;
    /// through Firebase the data arrives flat, with `supportmama: "reply"`.
    /// The background check's local notification carries the same marker.
    static func isOurs(_ userInfo: [AnyHashable: Any]) -> Bool {
        if userInfo["supportmama"] is [AnyHashable: Any] { return true }
        return userInfo["supportmama"] as? String == "reply"
    }

    /// Whether `token` should go to the server now, given what it last accepted.
    static func registrationDue(token: String?, registered: (token: String, at: Date)?, now: Date) -> Bool {
        guard let token, !token.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        guard let registered, registered.token == token else { return true }
        return now.timeIntervalSince(registered.at) >= reregisterAfter
    }

    /// An APNs device token as the server takes it: lowercase hex.
    static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}
