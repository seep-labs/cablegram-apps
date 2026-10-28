package app.cablegram.phone

import android.util.Log
import app.cablegram.phone.diagnostics.SentryScrubber

object PairLog {
    const val TAG = "CablegramPair"

    fun pinTail(pin: String?): String {
        val value = pin.orEmpty()
        if (value.length < 3) return "pin=?"
        return "pin=…${value.takeLast(3)}"
    }

    // Telegram chat ids never reach the log (spec 008 FR-002).
    fun i(message: String) = Log.i(TAG, SentryScrubber.scrubChatIds(message))
    fun w(message: String, error: Throwable? = null) = SentryScrubber.scrubChatIds(message).let {
        if (error == null) Log.w(TAG, it) else Log.w(TAG, it, error)
    }
    fun e(message: String, error: Throwable? = null) = SentryScrubber.scrubChatIds(message).let {
        if (error == null) Log.e(TAG, it) else Log.e(TAG, it, error)
    }
}
