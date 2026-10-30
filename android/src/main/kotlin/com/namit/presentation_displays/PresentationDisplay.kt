package com.namit.presentation_displays

import android.app.Presentation
import android.content.Context
import android.os.Bundle
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
            PresentationDisplaysLog.e("Can't find the FlutterEngine with cache name $tag")
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
        // reconfigured, not only when dismiss() is called. Without releasing here the
        // FlutterView stays attached to the shared cached engine and the next show()
        // attaches a second view to the same engine.
        release()

        super.onStop()
    }

    /**
     * Lets go of the shared cached engine: detaches the FlutterView and clears the handler
     * of the channel back to the main display. Safe to call more than once.
     *
     * Has to run before dismiss() when a new presentation takes over. dismiss() does not
     * always stop the dialog at once - with the soft keyboard up Android defers it until
     * the keyboard hides ("Async dismiss ... after IME hide"). By then the new presentation
     * is attached to the same engine and has set its own handler on the same channel, and a
     * late onStop would detach the engine from the new view and clear the new handler: the
     * display goes dark and its heartbeat stops.
     */
    fun release() {
        flutterView?.detachFromFlutterEngine()
        flutterView = null

        mainChannel?.setMethodCallHandler(null)
        mainChannel = null
    }

    companion object {
        private const val MAIN_DISPLAY_CHANNEL = "main_display_channel"
    }
}
