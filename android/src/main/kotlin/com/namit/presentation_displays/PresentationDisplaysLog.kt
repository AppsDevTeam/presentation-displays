package com.namit.presentation_displays

import android.util.Log

/**
 * The plugin's logcat output.
 *
 * Informational lines - the method calls and the data passed to the presentation - are off
 * unless the app turns them on with `DisplayManager.setLoggingEnabled`. That data is whatever
 * the app shows on the secondary display, often a customer's purchase, and has no place in the
 * log of a release build. Warnings and errors are always logged and never carry that data.
 *
 * One switch for the whole process: the main engine and the presentation's engine share it.
 */
internal object PresentationDisplaysLog {
	private const val TAG = "PresentationDisplays"

	@Volatile
	var enabled: Boolean = false

	/** Built only when logging is on, so a disabled log costs no string formatting. */
	fun i(message: () -> String) {
		if (enabled) {
			Log.i(TAG, message())
		}
	}

	fun w(message: String) {
		Log.w(TAG, message)
	}

	fun e(message: String) {
		Log.e(TAG, message)
	}
}
