// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SolistraLivenessSDK",
    platforms: [
        .iOS(.v15)
    ],
    products: [
        .library(
            name: "SolistraLivenessSDK",
            targets: ["SolistraLivenessCore", "SolistraLivenessActive", "SolistraLivenessPassive", "SolistraLivenessEngine", "SolistraLivenessUI"]
        ),
    ],
    dependencies: [
        // MediaPipe Tasks Vision (Google)
        // .package(url: "https://github.com/google/mediapipe", from: "0.10.14")
    ],
    targets: [
        .target(
            name: "SolistraLivenessCore",
            path: "Sources/SolistraLivenessCore"
        ),
        .target(
            name: "SolistraLivenessActive",
            dependencies: ["SolistraLivenessCore"],
            path: "Sources/SolistraLivenessActive"
        ),
        .target(
            name: "SolistraLivenessPassive",
            dependencies: ["SolistraLivenessCore"],
            path: "Sources/SolistraLivenessPassive"
        ),
        .target(
            name: "SolistraLivenessEngine",
            dependencies: ["SolistraLivenessCore", "SolistraLivenessActive", "SolistraLivenessPassive"],
            path: "Sources/SolistraLivenessEngine"
        ),
        .target(
            name: "SolistraLivenessUI",
            dependencies: ["SolistraLivenessCore", "SolistraLivenessEngine"],
            path: "Sources/SolistraLivenessUI"
        ),
        .testTarget(
            name: "SolistraLivenessSDKTests",
            dependencies: ["SolistraLivenessCore", "SolistraLivenessEngine"],
            path: "Tests/SolistraLivenessSDKTests"
        )
    ]
)
