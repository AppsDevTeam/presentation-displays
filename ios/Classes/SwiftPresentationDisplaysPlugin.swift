import Flutter
import UIKit

public class SwiftPresentationDisplaysPlugin: NSObject, FlutterPlugin {

    /// Called with the `FlutterViewController` created for an external display, so the host app
    /// can register its plugins on the secondary engine:
    ///
    /// ```swift
    /// SwiftPresentationDisplaysPlugin.controllerAdded = { controller in
    ///     GeneratedPluginRegistrant.register(with: controller)
    /// }
    /// ```
    ///
    /// Optional — a presentation without app plugins still renders.
    public static var controllerAdded: ((FlutterViewController) -> Void)?

    private static let mainDisplayChannelName = "main_display_channel"
    private static let secondaryEntrypoint = "secondaryDisplayMain"

    private var flutterEngineChannel: FlutterMethodChannel?
    private var mainDisplayChannel: FlutterMethodChannel?
    private var secondaryEngine: FlutterEngine?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(
            name: "presentation_displays_plugin",
            binaryMessenger: registrar.messenger()
        )
        let instance = SwiftPresentationDisplaysPlugin()
        instance.mainDisplayChannel = FlutterMethodChannel(
            name: mainDisplayChannelName,
            binaryMessenger: registrar.messenger()
        )
        registrar.addMethodCallDelegate(instance, channel: channel)

        let eventChannel = FlutterEventChannel(
            name: "presentation_displays_plugin_events",
            binaryMessenger: registrar.messenger()
        )
        eventChannel.setStreamHandler(DisplayConnectedStreamHandler())
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard #available(iOS 13.0, *) else {
            result(FlutterError(code: "unsupported", message: "Requires iOS 13 or newer", details: nil))
            return
        }

        switch call.method {
        case "listDisplay":
            let category = call.arguments as? String
            let includeBuiltIn = category != "android.hardware.display.category.PRESENTATION"
            let displays = PresentationDisplaysRegistry.shared.displays(includingBuiltIn: includeBuiltIn)

            do {
                let json = try JSONSerialization.data(withJSONObject: displays, options: [])
                result(String(data: json, encoding: .utf8) ?? "[]")
            } catch {
                result(FlutterError(code: call.method, message: error.localizedDescription, details: nil))
            }

        case "showPresentation":
            guard let json = decode(call.arguments) else {
                result(FlutterError(code: call.method, message: "Malformed arguments", details: nil))
                return
            }

            let displayId = json["displayId"] as? Int ?? 1
            let routerName = json["routerName"] as? String ?? "presentation"

            showPresentation(displayId: displayId, routerName: routerName, result: result)

        case "hidePresentation":
            let displayId = decode(call.arguments)?["displayId"] as? Int ?? 1

            hidePresentation(displayId: displayId)
            result(true)

        case "transferDataToPresentation":
            flutterEngineChannel?.invokeMethod("DataTransfer", arguments: call.arguments)
            result(true)

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    // MARK: - Private

    private func decode(_ arguments: Any?) -> [String: Any]? {
        guard let string = arguments as? String, let data = string.data(using: .utf8) else {
            return nil
        }

        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    @available(iOS 13.0, *)
    private func showPresentation(displayId: Int, routerName: String, result: @escaping FlutterResult) {
        let registry = PresentationDisplaysRegistry.shared

        guard let scene = registry.scene(forDisplayId: displayId) else {
            // Reported as an error rather than false, so the Dart side can tell a shown
            // presentation from one that never made it onto the display.
            result(FlutterError(
                code: "404",
                message: "Can't find display with displayId=\(displayId)",
                details: nil
            ))
            return
        }

        // Tear down a window left over from an earlier show on the same display.
        hidePresentation(displayId: displayId)

        // A dedicated engine running the secondary entry point, matching Android. The engine
        // outlives hide/show so the secondary UI keeps its state.
        let engine = secondaryEngine ?? FlutterEngine(name: "presentation_displays_engine")

        if secondaryEngine == nil {
            engine.run(withEntrypoint: SwiftPresentationDisplaysPlugin.secondaryEntrypoint, initialRoute: routerName)
            secondaryEngine = engine
        }

        let controller = FlutterViewController(engine: engine, nibName: nil, bundle: nil)
        SwiftPresentationDisplaysPlugin.controllerAdded?(controller)

        let window = UIWindow(windowScene: scene)
        window.rootViewController = controller
        window.isHidden = false
        registry.setWindow(window, forDisplayId: displayId)

        flutterEngineChannel = FlutterMethodChannel(
            name: "presentation_displays_plugin_engine",
            binaryMessenger: engine.binaryMessenger
        )

        // Back-channel from the secondary display to the main app, matching the Android side.
        FlutterMethodChannel(
            name: SwiftPresentationDisplaysPlugin.mainDisplayChannelName,
            binaryMessenger: engine.binaryMessenger
        ).setMethodCallHandler { [weak self] call, reply in
            if call.method == "transferDataToMain" {
                self?.mainDisplayChannel?.invokeMethod("dataToMain", arguments: call.arguments)
                reply(nil)
            } else {
                reply(FlutterMethodNotImplemented)
            }
        }

        result(true)
    }

    @available(iOS 13.0, *)
    private func hidePresentation(displayId: Int) {
        let registry = PresentationDisplaysRegistry.shared

        guard let window = registry.window(forDisplayId: displayId) else { return }

        window.isHidden = true
        window.rootViewController = nil
        registry.setWindow(nil, forDisplayId: displayId)
    }
}

class DisplayConnectedStreamHandler: NSObject, FlutterStreamHandler {

    private var sink: FlutterEventSink?

    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        sink = events

        guard #available(iOS 13.0, *) else { return nil }

        PresentationDisplaysRegistry.shared.onConnectionChanged = { [weak self] event in
            self?.sink?(event)
        }

        return nil
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        sink = nil

        if #available(iOS 13.0, *) {
            PresentationDisplaysRegistry.shared.onConnectionChanged = nil
        }

        return nil
    }
}
