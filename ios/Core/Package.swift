// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "LocationSessionCore",
    platforms: [.macOS(.v12), .iOS(.v15)],
    products: [.library(name: "LocationSessionCore", targets: ["LocationSessionCore"])],
    targets: [
        .target(name: "LocationSessionCore"),
        .testTarget(name: "LocationSessionCoreTests", dependencies: ["LocationSessionCore"]),
    ]
)
