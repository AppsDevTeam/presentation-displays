import UIKit

/// Keeps track of the external displays currently connected to the app.
///
/// Since iOS 13 an external display is delivered as a `UIWindowScene`, not through the
/// `UIScreen.didConnectNotification` notifications the plugin used to observe. Those APIs are
/// deprecated and under the scene lifecycle they no longer produce a usable window, so the
/// scene has to be picked up from `UISceneDelegate` instead.
///
/// Display ids are stable for the lifetime of a connection: `0` is always the built-in screen
/// and external displays get `1`, `2`, ... in the order they connect. An id is not reused while
/// its display is still attached.
@available(iOS 13.0, *)
public final class PresentationDisplaysRegistry {

    public static let shared = PresentationDisplaysRegistry()

    /// Called with `1` when a display connects and `0` when it disconnects.
    var onConnectionChanged: ((Int) -> Void)?

    private var entries: [Entry] = []
    private var nextDisplayId = 1

    private init() {}

    // MARK: - Scene lifecycle

    func attach(scene: UIWindowScene) {
        guard entry(for: scene) == nil else { return }

        entries.append(Entry(displayId: nextDisplayId, scene: scene, window: nil))
        nextDisplayId += 1

        onConnectionChanged?(1)
    }

    func detach(scene: UIWindowScene) {
        guard let index = entries.firstIndex(where: { $0.scene === scene }) else { return }

        entries[index].window?.isHidden = true
        entries.remove(at: index)

        onConnectionChanged?(0)
    }

    // MARK: - Lookup

    /// Displays as `[[String: Any]]`, ready to be serialized for the Dart side.
    ///
    /// The built-in screen is omitted when the presentation category is requested, mirroring
    /// Android's `DisplayManager.getDisplays(category)`.
    func displays(includingBuiltIn: Bool) -> [[String: Any]] {
        var result: [[String: Any]] = []

        if includingBuiltIn {
            result.append(["displayId": 0, "name": "Built-in Screen", "flags": 0, "rotation": 0])
        }

        for entry in entries {
            result.append([
                "displayId": entry.displayId,
                "name": entry.scene.session.persistentIdentifier,
                // FLAG_PRESENTATION (1 << 3) — an external scene is presentation capable by
                // definition, so the Dart side can filter identically on both platforms.
                "flags": 8,
                "rotation": 0,
            ])
        }

        return result
    }

    func scene(forDisplayId displayId: Int) -> UIWindowScene? {
        return entries.first(where: { $0.displayId == displayId })?.scene
    }

    func window(forDisplayId displayId: Int) -> UIWindow? {
        return entries.first(where: { $0.displayId == displayId })?.window
    }

    func setWindow(_ window: UIWindow?, forDisplayId displayId: Int) {
        guard let index = entries.firstIndex(where: { $0.displayId == displayId }) else { return }

        entries[index].window = window
    }

    // MARK: - Private

    private func entry(for scene: UIWindowScene) -> Entry? {
        return entries.first(where: { $0.scene === scene })
    }

    private struct Entry {
        let displayId: Int
        let scene: UIWindowScene
        var window: UIWindow?
    }
}

/// Scene delegate for the external display scene role.
///
/// The host app points the external display role at this class in its `Info.plist`:
///
/// ```xml
/// <key>UIApplicationSceneManifest</key>
/// <dict>
///   <key>UISceneConfigurations</key>
///   <dict>
///     <key>UIWindowSceneSessionRoleExternalDisplayNonInteractive</key>
///     <array>
///       <dict>
///         <key>UISceneDelegateClassName</key>
///         <string>presentation_displays.PresentationDisplaysSceneDelegate</string>
///       </dict>
///     </array>
///   </dict>
/// </dict>
/// ```
@available(iOS 13.0, *)
open class PresentationDisplaysSceneDelegate: UIResponder, UIWindowSceneDelegate {

    public var window: UIWindow?

    public func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }

        PresentationDisplaysRegistry.shared.attach(scene: windowScene)
    }

    public func sceneDidDisconnect(_ scene: UIScene) {
        guard let windowScene = scene as? UIWindowScene else { return }

        PresentationDisplaysRegistry.shared.detach(scene: windowScene)
    }
}
