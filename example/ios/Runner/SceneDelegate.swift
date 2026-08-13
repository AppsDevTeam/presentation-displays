import Flutter
import UIKit

/// Scene delegate for the app's own window.
///
/// Once an app declares a `UIApplicationSceneManifest` it opts into the scene lifecycle, and the
/// main window has to be created here instead of in `AppDelegate`. External displays are handled
/// separately by `PresentationDisplaysSceneDelegate` from the plugin.
class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let controller = FlutterViewController()
        GeneratedPluginRegistrant.register(with: controller)

        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
    }
}
