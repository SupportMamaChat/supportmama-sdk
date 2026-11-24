import Foundation

/// One message in the conversation, as this device keeps it.
public struct SupportMessage: Codable, Identifiable, Equatable, Sendable {
    /// The server's id; 0 until the message has been sent.
    public var serverId: Int64
    /// Tells unsent messages apart; stable for the message's life on this device.
    public var localId: UUID
    public var fromTeam: Bool
    public var text: String
    public var at: Date
    /// A screenshot was attached.
    public var hasImage: Bool
    /// The attached screenshot as kept on this device (a file name in the SDK's folder), if it is.
    public var imageFile: String?
    public var state: State

    public enum State: String, Codable, Sendable { case sent, sending, failed }

    public var id: UUID { localId }
    public var isSent: Bool { serverId > 0 }
}

/// A message as the server sends it (docs/protocol.md).
struct WireMessage: Decodable, Equatable {
    let id: Int64
    let from: String
    let text: String
    let at: Int64
    let image: Bool?

    var message: SupportMessage {
        SupportMessage(
            serverId: id, localId: UUID(), fromTeam: from == "team", text: text,
            at: Date(timeIntervalSince1970: TimeInterval(at) / 1000), hasImage: image ?? false,
            imageFile: nil, state: .sent
        )
    }
}

enum Conversation {
    /// Sent messages in id order (new ones from the server added), then the unsent ones.
    static func merge(_ list: [SupportMessage], _ incoming: [WireMessage]) -> [SupportMessage] {
        var byId: [Int64: SupportMessage] = [:]
        for m in list where m.isSent { byId[m.serverId] = m }
        for w in incoming where byId[w.id] == nil { byId[w.id] = w.message }
        let sent = byId.values.sorted { $0.serverId < $1.serverId }
        return sent + list.filter { !$0.isSent }
    }
}
