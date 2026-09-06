// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ZedClient",
    platforms: [
        .macOS(.v12),
        .iOS(.v15),
        .tvOS(.v15),
        .watchOS(.v8),
    ],
    products: [
        .library(name: "ZedClient", targets: ["ZedClient"]),
    ],
    targets: [
        .target(name: "ZedClient"),
        .testTarget(name: "ZedClientTests", dependencies: ["ZedClient"]),
    ],
    swiftLanguageVersions: [.v5]
)
