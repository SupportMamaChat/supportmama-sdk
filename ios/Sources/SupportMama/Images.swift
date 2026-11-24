import UIKit

enum Images {
    /// A JPEG of at most `maxEdge` points on the long side, like the other SDKs:
    /// readable, and far under the server's 5 MB limit.
    static func jpeg(_ image: UIImage, maxEdge: CGFloat = 1600, quality: CGFloat = 0.82) -> Data? {
        let size = image.size
        let longest = max(size.width, size.height)
        guard longest > 0 else { return nil }
        let scale = min(1, maxEdge / longest)
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let resized = UIGraphicsImageRenderer(size: target, format: format).image { ctx in
            UIColor.white.setFill() // transparent PNGs would turn black as JPEG
            ctx.fill(CGRect(origin: .zero, size: target))
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        return resized.jpegData(compressionQuality: quality)
    }
}
