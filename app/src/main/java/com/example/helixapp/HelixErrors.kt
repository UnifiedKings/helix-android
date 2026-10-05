package com.example.helixapp

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The Helix backend answered, but with a non-2xx status. */
class HelixHttpException(val code: Int) : IOException("HTTP $code")

/** A request succeeded but the expected result never showed up (e.g. a station never started). */
class HelixTimeoutException(message: String) : IOException(message)

/** A multi-step action got partway; [message] says what did and didn't happen. */
class HelixPartialException(message: String, cause: Throwable) : IOException(message, cause)

/**
 * A short message for the person using the app, e.g. "Play failed (HTTP 500)".
 * [action] names what they tried: "Play", "Queue", "Shuffle"…
 */
fun Throwable.toUserMessage(action: String): String = when (this) {
    is HelixHttpException ->
        if (code == 401) "$action failed: your session expired. Log in again in Settings."
        else "$action failed (HTTP $code)"
    is HelixTimeoutException -> message ?: "$action timed out"
    is HelixPartialException -> message ?: "$action only partly worked"
    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
        "$action failed: can't reach the Helix server"
    else -> "$action failed: ${javaClass.simpleName}"
}
