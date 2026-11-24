import Foundation

/// The calls the server has (docs/protocol.md).
struct Api {
    let endpoint: URL
    /// `<id>.<secret>`
    let credentials: String
    let appKey: String?
    var session: URLSession = .shared

    struct Failure: Error, Equatable {
        let status: Int
        /// The server's error code (`slow_down`, `not_connected`, …), or `network`.
        let code: String
    }

    /// One GET: new messages, and whether the server pushes replies (nil from servers before push).
    struct Page: Decodable {
        let messages: [WireMessage]
        let push: Bool?
    }

    func fetch(after: Int64) async throws -> Page {
        let data = try await request("GET", query: [URLQueryItem(name: "after", value: String(after))], body: nil, contentType: nil)
        return try JSONDecoder().decode(Page.self, from: data)
    }

    /// Registers this install's push token; true when the server will push
    /// replies. Before the first message: `Failure(status: 404, code: "no_conversation")`.
    func registerPush(_ token: PushToken) async throws -> Bool {
        struct Answer: Decodable { let push: Bool }
        var fields = ["kind": token.kind, "token": token.token]
        if let env = token.env { fields["env"] = env }
        let data = try await request("PUT", path: "v1/push", query: [], body: try JSONSerialization.data(withJSONObject: fields),
                                     contentType: "application/json; charset=utf-8")
        return try JSONDecoder().decode(Answer.self, from: data).push
    }

    func forgetPush() async throws {
        _ = try await request("DELETE", path: "v1/push", query: [], body: nil, contentType: nil)
    }

    func send(text: String, context: String, name: String?, image: Data?) async throws -> WireMessage {
        struct Sent: Decodable { let message: WireMessage }
        var fields = ["text": text]
        if !context.isEmpty { fields["context"] = context }
        if let name, !name.isEmpty { fields["name"] = name }
        let data: Data
        if let image {
            let boundary = "supportmama-\(UUID().uuidString)"
            data = try await request("POST", query: [], body: Self.multipart(fields: fields, image: image, boundary: boundary),
                                     contentType: "multipart/form-data; boundary=\(boundary)")
        } else {
            data = try await request("POST", query: [], body: try JSONSerialization.data(withJSONObject: fields),
                                     contentType: "application/json; charset=utf-8")
        }
        return try JSONDecoder().decode(Sent.self, from: data).message
    }

    static func multipart(fields: [String: String], image: Data, boundary: String) -> Data {
        var body = Data()
        func line(_ s: String = "") { body.append(Data((s + "\r\n").utf8)) }
        for key in fields.keys.sorted() {
            line("--\(boundary)")
            line("Content-Disposition: form-data; name=\"\(key)\"")
            line()
            line(fields[key]!)
        }
        line("--\(boundary)")
        line("Content-Disposition: form-data; name=\"image\"; filename=\"screenshot.jpg\"")
        line("Content-Type: image/jpeg")
        line()
        body.append(image)
        line()
        line("--\(boundary)--")
        return body
    }

    private func request(_ method: String, path: String = "v1/messages", query: [URLQueryItem], body: Data?, contentType: String?) async throws -> Data {
        var components = URLComponents(url: endpoint.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query }
        var req = URLRequest(url: components.url!, timeoutInterval: 20)
        req.httpMethod = method
        req.setValue("Bearer \(credentials)", forHTTPHeaderField: "Authorization")
        if let appKey { req.setValue(appKey, forHTTPHeaderField: "X-App-Key") }
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        req.httpBody = body

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch {
            throw Failure(status: 0, code: "network")
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let code = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? "server"
            throw Failure(status: status, code: code)
        }
        return data
    }
}
