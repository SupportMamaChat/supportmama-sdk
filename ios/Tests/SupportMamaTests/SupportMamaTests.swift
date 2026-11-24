import XCTest
@testable import SupportMama

final class ConversationTests: XCTestCase {
    private func message(_ id: Int64, team: Bool = false, state: SupportMessage.State = .sent) -> SupportMessage {
        SupportMessage(serverId: id, localId: UUID(), fromTeam: team, text: "m\(id)", at: Date(), hasImage: false, imageFile: nil, state: state)
    }

    func testMergeAddsNewInIdOrderAndKeepsUnsentLast() {
        let unsent = message(0, state: .failed)
        let list = [message(3), unsent, message(1)]
        let incoming = [
            WireMessage(id: 2, from: "team", text: "two", at: 1_000, image: nil),
            WireMessage(id: 3, from: "user", text: "dup", at: 1_000, image: nil),
        ]
        let merged = Conversation.merge(list, incoming)
        XCTAssertEqual(merged.map(\.serverId), [1, 2, 3, 0])
        XCTAssertEqual(merged[2].text, "m3", "a message already here is kept as is")
        XCTAssertEqual(merged[1].fromTeam, true)
        XCTAssertEqual(merged[3].localId, unsent.localId)
    }

    func testWireMessageDecodes() throws {
        let json = #"{"messages":[{"id":42,"from":"team","text":"Fixed!","at":1795363900000},{"id":43,"from":"user","text":"","at":1795363900001,"image":true}]}"#
        struct Page: Decodable { let messages: [WireMessage] }
        let page = try JSONDecoder().decode(Page.self, from: Data(json.utf8))
        XCTAssertEqual(page.messages[0].message.fromTeam, true)
        XCTAssertEqual(page.messages[0].message.at.timeIntervalSince1970, 1_795_363_900, accuracy: 0.001)
        XCTAssertEqual(page.messages[1].message.hasImage, true)
    }
}

final class ContextTests: XCTestCase {
    func testContextLine() {
        let line = DeviceContext.compose(app: "Acme", version: "2.3.1", build: "41", os: "iOS 18.1", device: "iPhone16,1",
                                         locale: "en-IN", extra: ["plan: pro"])
        XCTAssertEqual(line, "Acme 2.3.1 (41) · iOS 18.1 · iPhone16,1 · en-IN · plan: pro")
    }
}

final class ApiTests: XCTestCase {
    private var session: URLSession!

    override func setUp() {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        session = URLSession(configuration: config)
        StubProtocol.requests = []
    }

    private func api(appKey: String? = "smk_test") -> Api {
        Api(endpoint: URL(string: "https://support.example/")!, credentials: "id.secret", appKey: appKey, session: session)
    }

    func testFetchSendsCursorAuthAndAppKey() async throws {
        StubProtocol.respond = { _ in (200, #"{"messages":[{"id":7,"from":"team","text":"hi","at":1}],"tag":"ab12"}"#) }
        let page = try await api().fetch(after: 6)
        XCTAssertEqual(page.messages.map(\.id), [7])
        XCTAssertNil(page.push, "a server from before push says nothing about it")
        let req = try XCTUnwrap(StubProtocol.requests.first)
        XCTAssertEqual(req.url?.absoluteString, "https://support.example/v1/messages?after=6")
        XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer id.secret")
        XCTAssertEqual(req.value(forHTTPHeaderField: "X-App-Key"), "smk_test")
    }

    func testSelfHostedSendsNoAppKey() async throws {
        StubProtocol.respond = { _ in (200, #"{"messages":[]}"#) }
        _ = try await api(appKey: nil).fetch(after: 0)
        XCTAssertNil(StubProtocol.requests.first?.value(forHTTPHeaderField: "X-App-Key"))
    }

    func testServerErrorsCarryTheirCode() async {
        StubProtocol.respond = { _ in (429, #"{"error":"slow_down"}"#) }
        do {
            _ = try await api().send(text: "hi", context: "", name: nil, image: nil)
            XCTFail("expected an error")
        } catch let failure as Api.Failure {
            XCTAssertEqual(failure, Api.Failure(status: 429, code: "slow_down"))
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    func testFetchCarriesThePushFlag() async throws {
        StubProtocol.respond = { _ in (200, #"{"messages":[],"tag":"ab12","push":true}"#) }
        let page = try await api().fetch(after: 0)
        XCTAssertEqual(page.push, true)
    }

    func testRegisterPushSendsTheTokenAndReadsTheAnswer() async throws {
        StubProtocol.respond = { _ in (200, #"{"push":true}"#) }
        let push = try await api().registerPush(PushToken(kind: "apns", token: "a1b2", env: "sandbox"))
        XCTAssertTrue(push)
        let req = try XCTUnwrap(StubProtocol.requests.first)
        XCTAssertEqual(req.httpMethod, "PUT")
        XCTAssertEqual(req.url?.absoluteString, "https://support.example/v1/push")
        XCTAssertEqual(req.value(forHTTPHeaderField: "X-App-Key"), "smk_test")
        let body = try XCTUnwrap(req.httpBody ?? req.httpBodyStream.map(Self.read))
        let fields = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(fields, ["kind": "apns", "token": "a1b2", "env": "sandbox"])

        StubProtocol.respond = { _ in (200, #"{"push":false,"reason":"not_configured"}"#) }
        let off = try await api().registerPush(PushToken(kind: "fcm", token: "f", env: nil))
        XCTAssertFalse(off)
    }

    func testRegisteringBeforeTheFirstMessageSaysSo() async {
        StubProtocol.respond = { _ in (404, #"{"error":"no_conversation"}"#) }
        do {
            _ = try await api().registerPush(PushToken(kind: "fcm", token: "f", env: nil))
            XCTFail("expected an error")
        } catch let failure as Api.Failure {
            XCTAssertEqual(failure, Api.Failure(status: 404, code: "no_conversation"))
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    func testForgetPushDeletes() async throws {
        StubProtocol.respond = { _ in (200, #"{"push":false}"#) }
        try await api().forgetPush()
        XCTAssertEqual(StubProtocol.requests.first?.httpMethod, "DELETE")
        XCTAssertEqual(StubProtocol.requests.first?.url?.path, "/v1/push")
    }

    /// URLProtocol sees a request's body as a stream.
    private static func read(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return data
    }

    func testMultipartHasFieldsAndImage() {
        let body = Api.multipart(fields: ["text": "look", "name": "Asha"], image: Data([0xFF, 0xD8, 0xFF]), boundary: "B")
        let text = String(decoding: body, as: UTF8.self)
        XCTAssertTrue(text.hasPrefix("--B\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nAsha\r\n"))
        XCTAssertTrue(text.contains("name=\"text\"\r\n\r\nlook\r\n"))
        XCTAssertTrue(text.contains("name=\"image\"; filename=\"screenshot.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"))
        XCTAssertTrue(text.hasSuffix("\r\n--B--\r\n"))
    }
}

final class PushTests: XCTestCase {
    func testOnlySupportMamaNotificationsAreOurs() {
        XCTAssertTrue(Push.isOurs(["aps": ["alert": "hi"], "supportmama": ["id": 42]]), "direct APNs")
        XCTAssertTrue(Push.isOurs(["supportmama": "reply", "id": "42", "text": "hi"]), "through Firebase")
        XCTAssertFalse(Push.isOurs(["aps": ["alert": "50% off"]]))
        XCTAssertFalse(Push.isOurs(["supportmama": "something-else"]))
    }

    func testATokenIsSentWhenNewChangedOrAWeekOld() {
        let now = Date()
        XCTAssertFalse(Push.registrationDue(token: nil, registered: nil, now: now))
        XCTAssertFalse(Push.registrationDue(token: " ", registered: nil, now: now))
        XCTAssertTrue(Push.registrationDue(token: "t1", registered: nil, now: now))
        XCTAssertTrue(Push.registrationDue(token: "t2", registered: ("t1", now), now: now))
        XCTAssertFalse(Push.registrationDue(token: "t1", registered: ("t1", now.addingTimeInterval(-3600)), now: now))
        XCTAssertTrue(Push.registrationDue(token: "t1", registered: ("t1", now.addingTimeInterval(-Push.reregisterAfter)), now: now))
    }

    func testDeviceTokensBecomeLowercaseHex() {
        XCTAssertEqual(Push.hex(Data([0x00, 0xAB, 0x10, 0xFF])), "00ab10ff")
    }

    func testThePushTokenAndMarkersPersist() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "supportmama-tests-\(UUID().uuidString)"))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = Store(defaults: defaults, directory: dir, keychainService: "dev.supportmama.tests")
        XCTAssertNil(store.pushToken)
        XCTAssertNil(store.pushRegistered)
        store.pushToken = PushToken(kind: "apns", token: "a1b2", env: "production")
        let at = Date(timeIntervalSince1970: 1_795_000_000)
        store.pushRegistered = ("a1b2", at)
        store.pushActive = true
        let again = Store(defaults: defaults, directory: dir, keychainService: "dev.supportmama.tests")
        XCTAssertEqual(again.pushToken, PushToken(kind: "apns", token: "a1b2", env: "production"))
        XCTAssertEqual(again.pushRegistered?.token, "a1b2")
        XCTAssertEqual(again.pushRegistered?.at, at)
        XCTAssertTrue(again.pushActive)
        again.pushRegistered = nil
        XCTAssertNil(again.pushRegistered)
    }
}

final class StubProtocol: URLProtocol {
    static var requests: [URLRequest] = []
    static var respond: (URLRequest) -> (Int, String) = { _ in (200, "{}") }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        let (status, body) = Self.respond(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
