import Foundation
import UIKit

/// The facts a team asks first, posted to them once per change:
/// `Acme 2.3.1 (41) · iOS 18.1 · iPhone16,1 · en-IN · plan: pro`.
enum DeviceContext {
    @MainActor
    static func line(attributes: () -> [String: String]) -> String {
        let info = Bundle.main.infoDictionary ?? [:]
        let name = (info["CFBundleDisplayName"] as? String) ?? (info["CFBundleName"] as? String) ?? "App"
        let version = (info["CFBundleShortVersionString"] as? String) ?? "?"
        let build = info["CFBundleVersion"] as? String
        let extra = attributes()
            .filter { !$0.key.trimmingCharacters(in: .whitespaces).isEmpty && !$0.value.trimmingCharacters(in: .whitespaces).isEmpty }
            .sorted { $0.key < $1.key }
            .map { "\($0.key): \($0.value)" }
        return compose(app: name, version: version, build: build, os: "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)",
                       device: modelIdentifier(), locale: Locale.current.identifier.replacingOccurrences(of: "_", with: "-"), extra: extra)
    }

    static func compose(app: String, version: String, build: String?, os: String, device: String, locale: String, extra: [String]) -> String {
        let first = build.map { "\(app) \(version) (\($0))" } ?? "\(app) \(version)"
        return ([first, os, device, locale] + extra).joined(separator: " · ")
    }

    /// "iPhone16,1"; in the simulator, the simulated model.
    static func modelIdentifier() -> String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return "\(simulated) (Simulator)" }
        var system = utsname()
        uname(&system)
        return withUnsafeBytes(of: &system.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
    }
}
