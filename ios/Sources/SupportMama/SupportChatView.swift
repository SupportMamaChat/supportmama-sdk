import PhotosUI
import SwiftUI

/// The chat screen with its own navigation bar and Close button: for a sheet.
///
/// ```swift
/// .sheet(isPresented: $showHelp) { SupportChatView() }
/// ```
@MainActor
public struct SupportChatView: View {
    private let draft: String?
    @Environment(\.dismiss) private var dismiss

    public init(draft: String? = nil) {
        self.draft = draft
    }

    public var body: some View {
        NavigationStack {
            SupportChatContent(draft: draft)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { dismiss() }
                    }
                }
        }
    }
}

/// The chat without a navigation container, to push inside your own `NavigationStack`.
@MainActor
public struct SupportChatContent: View {
    @ObservedObject private var chat = SupportChatModel.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var text: String
    @State private var pickerItem: PhotosPickerItem?
    @State private var staged: UIImage?
    @FocusState private var composerFocused: Bool

    public init(draft: String? = nil) {
        _text = State(initialValue: draft ?? "")
    }

    private var config: SupportMama.Config? { chat.config }
    private var accent: Color { config?.accentColor ?? .accentColor }
    private var canSend: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || staged != nil }

    public var body: some View {
        VStack(spacing: 0) {
            if chat.offline {
                Text("Can’t reach support right now. We’ll keep trying.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(8)
                    .background(Color(.secondarySystemBackground))
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        if chat.messages.isEmpty {
                            emptyState
                        } else {
                            ForEach(Array(chat.messages.enumerated()), id: \.element.id) { index, message in
                                if message.fromTeam && (index == 0 || !chat.messages[index - 1].fromTeam) {
                                    Text(config?.teamLabel ?? "Team")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                        .padding(.top, 8)
                                }
                                MessageBubble(message: message, store: chat.store, accent: accent) {
                                    chat.retry(message.localId)
                                }
                            }
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(16)
                }
                .scrollDismissesKeyboard(.interactively)
                .onAppear { proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: chat.messages.count) { _ in
                    withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
                }
            }
            if let code = chat.lastError {
                Text(Self.errorText(code))
                    .font(.footnote)
                    .foregroundColor(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.top, 6)
            }
            if let staged {
                HStack(spacing: 10) {
                    Image(uiImage: staged)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 44, height: 44)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    Text("Screenshot attached").font(.footnote).foregroundColor(.secondary)
                    Spacer()
                    Button {
                        self.staged = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill").foregroundColor(.secondary)
                    }
                    .accessibilityLabel("Remove screenshot")
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Divider().padding(.top, 8)
            composer
        }
        .navigationTitle(config?.title ?? "Chat with us")
        .navigationBarTitleDisplayMode(.inline)
        .tint(accent)
        .onAppear { chat.visible = true }
        .onDisappear { chat.visible = false }
        // Every 5 s while on screen and the app is in front.
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                await chat.refresh()
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            }
        }
        .onChange(of: pickerItem) { item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                    staged = image
                }
                pickerItem = nil
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Text(config?.headline ?? "Talk to the team")
                .font(.title2.weight(.semibold))
            Text(config?.intro ?? "Ask anything or report a problem. Real people read every message and reply right here.")
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            if config?.askForName != false {
                TextField("What should we call you? (optional)", text: $chat.name)
                    .textContentType(.name)
                    .padding(10)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 60)
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if config?.screenshots != false {
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Image(systemName: "photo")
                        .font(.system(size: 20))
                        .frame(width: 36, height: 36)
                }
                .accessibilityLabel("Attach a screenshot")
            }
            TextField("Write a message…", text: $text, axis: .vertical)
                .lineLimit(1...5)
                .focused($composerFocused)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            Button(action: submit) {
                Image(systemName: "arrow.up.circle.fill").font(.system(size: 30))
            }
            .disabled(!canSend)
            .accessibilityLabel("Send")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private func submit() {
        guard canSend else { return }
        chat.send(text, image: staged)
        text = ""
        staged = nil
    }

    static func errorText(_ code: String) -> String {
        switch code {
        case "slow_down": return "That’s a lot of messages at once. Please try again in a few minutes."
        case "not_connected": return "Support isn’t set up yet. Your message is saved here; try again later."
        case "not_configured": return "Call SupportMama.configure(…) at launch."
        case "too_long": return "That message is too long. Please shorten it."
        default: return "Not sent. Check your connection and tap Retry."
        }
    }
}

struct MessageBubble: View {
    let message: SupportMessage
    let store: Store
    let accent: Color
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: message.fromTeam ? .leading : .trailing, spacing: 2) {
            VStack(alignment: .leading, spacing: 4) {
                if message.hasImage {
                    if let file = message.imageFile, let image = UIImage(contentsOfFile: store.imageURL(file).path) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 220, maxHeight: 260)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    } else {
                        Text("📷 Screenshot").font(.footnote)
                    }
                }
                if !message.text.isEmpty {
                    Text(Self.linkified(message.text))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(message.fromTeam ? Color(.secondarySystemBackground) : accent)
            .foregroundColor(message.fromTeam ? .primary : .white)
            .tint(message.fromTeam ? accent : .white)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .opacity(message.state == .failed ? 0.6 : 1)
            .textSelection(.enabled)

            if message.state == .failed {
                Button("Not sent · Retry", action: onRetry)
                    .font(.caption)
                    .foregroundColor(.red)
            } else {
                Text(message.state == .sending ? "Sending…" : message.at.formatted(date: .omitted, time: .shortened))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: message.fromTeam ? .leading : .trailing)
    }

    /// Plain text, with http(s) links tappable. Nothing is read as markup.
    static func linkified(_ text: String) -> AttributedString {
        var attributed = AttributedString(text)
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return attributed }
        for match in detector.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let url = match.url, ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
                  let range = Range(match.range, in: text),
                  let lower = AttributedString.Index(range.lowerBound, within: attributed),
                  let upper = AttributedString.Index(range.upperBound, within: attributed) else { continue }
            attributed[lower..<upper].link = url
        }
        return attributed
    }
}
