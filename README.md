# presentation_displays (AppsDevTeam fork)

Flutter plugin for running a second Flutter UI on an external screen — a tablet or POS terminal
connected to a customer display over HDMI, USB-C or wirelessly. The secondary screen runs its own
`FlutterEngine` and receives data from the main app over a method channel.

This is a fork of [ZonalUS/presentation-displays](https://github.com/ZonalUS/presentation-displays),
which itself forks [smew-tech/presentation-displays](https://github.com/smew-tech/presentation-displays).
Upstream has been unmaintained since March 2024.

## Why this fork exists

Upstream crashes the whole app natively when the presentation engine is created at the wrong moment.
The crash lands in `flutter::AttachJNI` with a `SIGSEGV` and it is invisible in Crashlytics unless
`firebase-crashlytics-ndk` is installed, because the Java SDK does not see native signals:

```
#00 pc 0x4cb4b0 libflutter.so (flutter::AttachJNI(_JNIEnv*, _jclass*, _jobject*) [shared_ptr.h:474])
#01 pc 0x4cb4a4 libflutter.so (flutter::AttachJNI(_JNIEnv*, _jclass*, _jobject*) [shell.cc:212])
```

The fixes below are not in any other published fork — every fork of the original carries the same
`createFlutterEngine` implementation verbatim.

### What is fixed

| # | Problem upstream | Fix |
| - | ---------------- | --- |
| 1 | `FlutterEngine` was constructed **before** `FlutterLoader.startInitialization()`, and `ensureInitializationComplete()` was never called at all. The engine constructor calls `FlutterJNI.attachToNative()` against an uninitialized loader → native crash. | Loader is initialized first, `ensureInitializationComplete()` is called, and only then is the engine constructed. |
| 2 | The engine was created with the **activity** context, so it kept a destroyed activity alive across recreation. | Engine uses the application context. The `Presentation` itself still uses the activity context — it is a `Dialog` and needs a window token. |
| 3 | `hidePresentation` only called `dismiss()`. The `FlutterView` stayed attached to the shared cached engine, so the next `show()` attached a **second** view to the same engine. | `PresentationDisplay.onStop()` detaches the `FlutterView`. This also covers the case where Android dismisses the presentation on its own after the display is removed or reconfigured. |
| 4 | `showPresentation` replied `success(true)` even when the presentation object was never built, so the Dart side could not tell a shown presentation from a silent failure — the display kept mirroring the app. | Missing activity or engine is reported through `result.error(...)`. A stale presentation is dismissed before a new one is shown. |
| 5 | The `transferDataToMain` handler never called `result.success(...)`, so the `Future` returned on the Dart side never completed and every call leaked a pending completer. | The handler always replies. |
| 6 | Presentations outlived the activity that created them across configuration changes. | `onDetachedFromActivity` and `onDetachedFromActivityForConfigChanges` dismiss the presentation. |
| 7 | Logs were tagged `ContentValues` (`import android.content.ContentValues.TAG`), which made them impossible to find. | Proper `PresentationDisplays` log tag. |

Everything ZonalUS added over the original is kept, including `transferDataToMain` — the back-channel
from the secondary display to the main app, which the original does not have.

### iOS: migrated to the UIScene lifecycle

The iOS half was built on `UIScreen.didConnectNotification` and `UIWindow.screen`, which Apple
deprecated when scenes were introduced. Under the scene lifecycle those notifications no longer
produce a usable window, so external display support was effectively dead on modern iOS.

It now uses `UIWindowScene`: a `PresentationDisplaysSceneDelegate` picks up the external display
scene, the plugin creates a `UIWindow(windowScene:)` for it, and connect/disconnect events come
from the scene delegate instead of `NotificationCenter`. Along the way iOS gained parity with
Android — a dedicated engine running the `secondaryDisplayMain` entry point, real JSON from
`listDisplay` including `flags`, `transferDataToMain` support, and errors instead of a bare `false`.

The minimum deployment target is now **iOS 13**. `controllerAdded` is no longer force-unwrapped, so
an app that does not set it no longer crashes.

## Installation

```yaml
dependencies:
  presentation_displays:
    git:
      url: https://github.com/AppsDevTeam/presentation-displays.git
      ref: master
```

Pin `ref` to a tag or commit for reproducible builds.

## Usage

### 1. Declare the secondary entry point

The secondary display runs a separate `FlutterEngine` with its own entry point. It must be annotated
with `@pragma('vm:entry-point')` and named exactly `secondaryDisplayMain`:

```dart
@pragma('vm:entry-point')
void secondaryDisplayMain() {
  runApp(const MySecondaryDisplayApp());
}
```

This engine is a full second instance of your app: it initializes its own plugins, its own database
handles and its own Firebase. Keep its bootstrap minimal and do not assume it shares state with the
main engine.

### 1b. iOS only — opt into the scene lifecycle

External displays on iOS are delivered as scenes, so the host app has to declare them. Add to
`ios/Runner/Info.plist`:

```xml
<key>UIApplicationSceneManifest</key>
<dict>
    <key>UIApplicationSupportsMultipleScenes</key>
    <true/>
    <key>UISceneConfigurations</key>
    <dict>
        <key>UIWindowSceneSessionRoleApplication</key>
        <array>
            <dict>
                <key>UISceneConfigurationName</key>
                <string>Default Configuration</string>
                <key>UISceneDelegateClassName</key>
                <string>$(PRODUCT_MODULE_NAME).SceneDelegate</string>
            </dict>
        </array>
        <key>UIWindowSceneSessionRoleExternalDisplayNonInteractive</key>
        <array>
            <dict>
                <key>UISceneConfigurationName</key>
                <string>External Display</string>
                <key>UISceneDelegateClassName</key>
                <string>presentation_displays.PresentationDisplaysSceneDelegate</string>
            </dict>
        </array>
    </dict>
</dict>
```

Declaring a scene manifest opts the whole app into the scene lifecycle, so the **main** window must
be created by your own `SceneDelegate` rather than in `AppDelegate` — see
`example/ios/Runner/SceneDelegate.swift`.

To let the secondary engine use your app's plugins, set the hook in `AppDelegate`:

```swift
SwiftPresentationDisplaysPlugin.controllerAdded = { controller in
    GeneratedPluginRegistrant.register(with: controller)
}
```

Android needs none of this.

### 2. Find the display

```dart
final DisplayManager displayManager = DisplayManager();

final List<Display>? displays = await displayManager.getDisplays(
  category: DISPLAY_CATEGORY_PRESENTATION,
);
```

Pass `DISPLAY_CATEGORY_PRESENTATION`. A plain `getDisplays()` also returns virtual displays — screen
casting, developer overlays, vendor internals — and attaching the presentation to one of those
succeeds while the physical customer display keeps mirroring the app.

Skip `DEFAULT_DISPLAY` and anything with `FLAG_PRIVATE`; a private display belongs to another app.

### 3. Show the presentation

```dart
final bool? shown = await displayManager.showSecondaryDisplay(
  displayId: display.displayId,
  routerName: 'presentation',
);
```

**Check the return value.** Anything other than `true` is a failure, and the physical display will
show a mirror of your app rather than your content.

`routerName` is passed to the secondary engine as its initial route.

### 4. Send data to the secondary display

```dart
await displayManager.transferDataToPresentation(jsonEncode(payload));
```

Received on the secondary side:

```dart
@override
Widget build(BuildContext context) {
  return SecondaryDisplay(
    callback: (argument) => setState(() => value = argument),
    child: const MyContent(),
  );
}
```

### 5. Send data back to the main display

This is the part the original plugin does not have:

```dart
// on the secondary display
await displayManager.transferDataToMain(jsonEncode(ack));
```

```dart
// on the main display
MethodChannel('main_display_channel').setMethodCallHandler((call) async {
  if (call.method == 'dataToMain') {
    // call.arguments is the payload sent from the secondary display
  }
  return null;
});
```

### 6. React to displays being connected and disconnected

```dart
displayManager.connectedDisplaysChangedStream?.listen((int? event) {
  // 1 = display connected, 0 = display disconnected
});
```

Invalidate any cached `displayId` on either event.

### 7. Hide the presentation

```dart
await displayManager.hideSecondaryDisplay(displayId: displayId);
```

## Known limitations

- **A dismissed presentation is not reported to Dart.** Android dismisses a presentation on its own
  when its display is reconfigured. The `FlutterView` is detached correctly, but nothing tells the
  main app, so the only reliable detection is a heartbeat: have the secondary display call
  `transferDataToMain` periodically and treat a missing beat as a dead presentation.
- **The cached engine is never destroyed.** It is reused across `show()`/`hide()` cycles for the
  lifetime of the process, which is what makes state survive a hide/show.
- **The iOS side compiles but has not been exercised on real hardware.** The UIScene migration was
  verified by building the example for the simulator; a simulator cannot attach a physical external
  display, so the runtime path is unproven. The Android side is the tested one.

## Development

### Running the example

```bash
cd example
flutter run
```

The example's Android build was migrated off the imperative `apply from:` Gradle setup that recent
Flutter versions reject — it now uses the declarative `plugins { }` block, AGP 8.11.1, Kotlin 2.2.20
and Gradle 8.14.3. Jetifier is disabled: the project is AndroidX only, and jetifying the Flutter
engine jars exhausts the heap.

### Testing against a real app

To verify a change against an existing app instead, point a `dependency_overrides` entry at a local
checkout:

```yaml
dependency_overrides:
  presentation_displays:
    path: ../presentation-displays
```

## License

Same as upstream — see [LICENSE](LICENSE).
