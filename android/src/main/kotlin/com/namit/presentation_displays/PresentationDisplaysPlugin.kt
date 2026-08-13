// android/src/main/kotlin/com/namit/presentation_displays/PresentationDisplaysPlugin.kt
package com.namit.presentation_displays

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.annotation.NonNull
import com.google.gson.Gson
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/** PresentationDisplaysPlugin (Flutter v2 embedding) */
class PresentationDisplaysPlugin :
	FlutterPlugin,
	ActivityAware,
	MethodChannel.MethodCallHandler {

	private lateinit var channel: MethodChannel
	private lateinit var eventChannel: EventChannel
	private var flutterEngineChannel: MethodChannel? = null
	private var context: Context? = null
	private var presentation: PresentationDisplay? = null
	private var flutterBinding: FlutterPlugin.FlutterPluginBinding? = null

	override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
		flutterBinding = flutterPluginBinding
		channel = MethodChannel(flutterPluginBinding.binaryMessenger, VIEW_TYPE_ID)
		channel.setMethodCallHandler(this)

		eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, VIEW_TYPE_EVENTS_ID)
		displayManager = flutterPluginBinding
			.applicationContext
			.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
		eventChannel.setStreamHandler(DisplayConnectedStreamHandler(displayManager))
	}

	override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
		channel.setMethodCallHandler(null)
		eventChannel.setStreamHandler(null)
		dismissPresentation()
		flutterEngineChannel = null
		flutterBinding = null
	}

	override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
		Log.i(LOG_TAG, "Channel: method: ${call.method} | arguments: ${call.arguments}")

		when (call.method) {
			"showPresentation" -> showPresentation(call, result)
			"hidePresentation" -> {
				try {
					dismissPresentation()
					result.success(true)
				} catch (e: Exception) {
					result.error(call.method, e.message, null)
				}
			}
			"listDisplay" -> {
				val listJson = ArrayList<DisplayJson>()
				val category = call.arguments as? String
				val displays = displayManager?.getDisplays(category)
				displays?.forEach { d ->
					listJson.add(DisplayJson(d.displayId, d.flags, d.rotation, d.name))
				}
				result.success(Gson().toJson(listJson))
			}
			"transferDataToPresentation" -> {
				try {
					flutterEngineChannel?.invokeMethod("DataTransfer", call.arguments)
					result.success(true)
				} catch (_: Exception) {
					result.success(false)
				}
			}
			else -> result.notImplemented()
		}
	}

	private fun showPresentation(call: MethodCall, result: MethodChannel.Result) {
		try {
			val obj = JSONObject(call.arguments as String)
			val displayId = obj.getInt("displayId")
			val routeTag = obj.getString("routerName")
			val display = displayManager?.getDisplay(displayId)

			if (display == null) {
				result.error("404", "Can't find display with displayId=$displayId", null)
				return
			}

			// A presentation left over from an earlier show() keeps its FlutterView attached
			// to the shared cached engine. Building a new one on top of it would attach a
			// second view to the same engine and leave a dead window on the display.
			dismissPresentation()

			val flutterEngine = createFlutterEngine(routeTag)

			if (flutterEngine == null) {
				result.error("404", "Can't find FlutterEngine", null)
				return
			}

			// Presentation is a Dialog, so it needs the activity context — the application
			// context has no window token and show() would throw BadTokenException.
			val activityContext = context

			if (activityContext == null) {
				result.error("500", "Plugin is not attached to an activity", null)
				return
			}

			flutterEngineChannel = MethodChannel(
				flutterEngine.dartExecutor.binaryMessenger,
				"${VIEW_TYPE_ID}_engine"
			)

			val dataToMainCallback: (Any?) -> Unit = { argument ->
				flutterBinding?.let { binding ->
					MethodChannel(binding.binaryMessenger, MAIN_DISPLAY_CHANNEL)
						.invokeMethod("dataToMain", argument)
				}
			}

			val newPresentation = PresentationDisplay(activityContext, routeTag, display, dataToMainCallback)
			newPresentation.show()
			presentation = newPresentation

			Log.i(LOG_TAG, "presentation shown on displayId=$displayId")

			result.success(true)
		} catch (e: Exception) {
			// Reported as a failure instead of success(true) so the Dart side can tell a
			// shown presentation from one that never made it onto the display.
			Log.e(LOG_TAG, "showPresentation failed: ${e.message}")
			result.error(call.method, e.message, null)
		}
	}

	private fun dismissPresentation() {
		presentation?.let { current ->
			try {
				current.dismiss()
			} catch (e: Exception) {
				Log.w(LOG_TAG, "Failed to dismiss presentation: ${e.message}")
			}
		}
		presentation = null
	}

	private fun createFlutterEngine(tag: String): FlutterEngine? {
		val appContext = flutterBinding?.applicationContext
			?: context?.applicationContext
			?: return null

		FlutterEngineCache.getInstance().get(tag)?.let { return it }

		// The loader has to be initialized before the engine is constructed. The other way
		// round, FlutterEngine's constructor calls FlutterJNI.attachToNative() against an
		// uninitialized loader, which crashes natively in flutter::AttachJNI.
		val loader = FlutterInjector.instance().flutterLoader()
		loader.startInitialization(appContext)
		loader.ensureInitializationComplete(appContext, null)

		// The application context outlives activity recreation — an activity context here
		// leaves the engine holding a destroyed activity.
		val flutterEngine = FlutterEngine(appContext)
		flutterEngine.navigationChannel.setInitialRoute(tag)

		val entrypoint = DartExecutor.DartEntrypoint(loader.findAppBundlePath(), SECONDARY_ENTRYPOINT)
		flutterEngine.dartExecutor.executeDartEntrypoint(entrypoint)
		flutterEngine.lifecycleChannel.appIsResumed()

		FlutterEngineCache.getInstance().put(tag, flutterEngine)

		return flutterEngine
	}

	/* ActivityAware */
	override fun onAttachedToActivity(binding: ActivityPluginBinding) {
		context = binding.activity
		displayManager = context?.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
	}

	override fun onDetachedFromActivityForConfigChanges() {
		// The presentation is tied to the activity that created it, so it must not outlive it.
		dismissPresentation()
		context = null
	}

	override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
		context = binding.activity
		displayManager = context?.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
	}

	override fun onDetachedFromActivity() {
		dismissPresentation()
		context = null
	}

	companion object {
		private const val LOG_TAG = "PresentationDisplays"
		private const val VIEW_TYPE_ID = "presentation_displays_plugin"
		private const val VIEW_TYPE_EVENTS_ID = "presentation_displays_plugin_events"
		private const val MAIN_DISPLAY_CHANNEL = "main_display_channel"
		private const val SECONDARY_ENTRYPOINT = "secondaryDisplayMain"
		private var displayManager: DisplayManager? = null
	}
}

/* Stream handler */
class DisplayConnectedStreamHandler(private var displayManager: DisplayManager?) :
	EventChannel.StreamHandler {
	private var sink: EventChannel.EventSink? = null
	private var handler: Handler? = null

	private val displayListener = object : DisplayManager.DisplayListener {
		override fun onDisplayAdded(displayId: Int) { sink?.success(1) }
		override fun onDisplayRemoved(displayId: Int) { sink?.success(0) }
		override fun onDisplayChanged(p0: Int) {}
	}

	override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
		sink = events
		handler = Handler(Looper.getMainLooper())
		displayManager?.registerDisplayListener(displayListener, handler)
	}

	override fun onCancel(arguments: Any?) {
		sink = null
		handler = null
		displayManager?.unregisterDisplayListener(displayListener)
	}
}
