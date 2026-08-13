package com.namit.presentation_displays

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import android.widget.FrameLayout
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel

class PresentationDisplay(
    context: Context,
    private val tag: String,
    display: Display,
    private val callBack: (tip: Any?) -> Unit
) : Presentation(context, display) {

    private var flutterView: FlutterView? = null
    private var mainChannel: MethodChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val flContainer = FrameLayout(context)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        flContainer.layoutParams = params

        setContentView(flContainer)

        val flutterEngine = FlutterEngineCache.getInstance().get(tag)

        if (flutterEngine == null) {
            Log.e(LOG_TAG, "Can't find the FlutterEngine with cache name $tag")
            return
        }

        val view = FlutterView(context)
        flContainer.addView(view, params)
        view.attachToFlutterEngine(flutterEngine)
        flutterView = view

        mainChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            MAIN_DISPLAY_CHANNEL
        ).also { channel ->
            channel.setMethodCallHandler { call, result ->
                if (call.method == "transferDataToMain") {
                    callBack(call.arguments)
                    // The reply has to be sent, otherwise the Future returned by
                    // transferDataToMain() on the Dart side never completes and every
                    // call leaks a pending completer in the secondary engine.
                    result.success(null)
                } else {
                    result.notImplemented()
                }
            }
        }
    }

    override fun onStop() {
        // Android dismisses a presentation on its own when its display is removed or
        // reconfigured, not only when dismiss() is called. Without detaching here the
        // FlutterView stays attached to the shared cached engine and the next show()
        // attaches a second view to the same engine.
        flutterView?.detachFromFlutterEngine()
        flutterView = null

        mainChannel?.setMethodCallHandler(null)
        mainChannel = null

        super.onStop()
    }

    companion object {
        private const val LOG_TAG = "PresentationDisplay"
        private const val MAIN_DISPLAY_CHANNEL = "main_display_channel"
    }
}
