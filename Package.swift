// swift-tools-version:5.9
// The iOS SDK lives in ios/; this manifest sits at the repository root
// because Swift Package Manager installs packages from a git URL's root.
import PackageDescription

let package = Package(
    name: "SupportMama",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "SupportMama", targets: ["SupportMama"]),
    ],
    targets: [
        .target(name: "SupportMama", path: "ios/Sources/SupportMama"),
        .testTarget(name: "SupportMamaTests", dependencies: ["SupportMama"], path: "ios/Tests/SupportMamaTests"),
    ]
)
