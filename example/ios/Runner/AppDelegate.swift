import Flutter
import UIKit
import presentation_displays

@main
@objc class AppDelegate: FlutterAppDelegate {

    override func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // Lets the plugin register this app's plugins on the secondary engine it creates for
        // an external display.
        SwiftPresentationDisplaysPlugin.controllerAdded = { controller in
            GeneratedPluginRegistrant.register(with: controller)
        }

        return super.application(application, didFinishLaunchingWithOptions: launchOptions)
    }

    // Under the scene lifecycle the window is created by SceneDelegate, and external displays
    // by PresentationDisplaysSceneDelegate — both wired up in Info.plist.
}
