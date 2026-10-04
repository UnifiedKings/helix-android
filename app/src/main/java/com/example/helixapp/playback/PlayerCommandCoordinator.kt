package com.example.helixapp.playback

import android.content.Context
import android.util.Log
import com.example.helixapp.HelixApi
import com.example.helixapp.HelixClient
import com.example.helixapp.HelixHttpException
import com.example.helixapp.HelixPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * Serializes player mutations with realtime Media3 synchronization.
 *
 * Fast back/forward taps used to launch independent coroutines. A previous/next request,
 * websocket refresh, and Media3 reload could therefore complete out of order, leaving the
 * backend playing while the local media session still advertised paused (or vice versa).
 *
 * Helix remains authoritative; this object only guarantees that one native playback
 * transition is applied at a time.
 */
object PlayerCommandCoordinator {
    private const val ENDED_MAX_ATTEMPTS = 3
    private const val ENDED_RETRY_BASE_DELAY_MS = 1_000L

    private val mutex = Mutex()

    suspend fun syncFromBackend(context: Context, forceLoadStream: Boolean = false) {
        mutex.withLock {
            HelixTransport.refreshAndSync(context, forceLoadStream = forceLoadStream)
        }
    }

    /**
     * Report the current track as finished, then load whatever the backend plays next.
     *
     * The /ended call is retried outside the lock so user commands aren't blocked during
     * backoff. Only a confirmed /ended force-reloads the stream: reloading after a failed call
     * would restart the track that just finished.
     */
    suspend fun trackEnded(context: Context) {
        val api = HelixClient.create(context, HelixPrefs.getBaseUrl(context))
        var failure = "unknown error"
        for (attempt in 1..ENDED_MAX_ATTEMPTS) {
            val result = runCatching { withContext(Dispatchers.IO) { api.ended() } }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }

            val resp = result.getOrNull()
            if (resp != null && resp.isSuccessful) {
                mutex.withLock {
                    HelixTransport.refreshAndSync(context, forceLoadStream = true)
                }
                return
            }

            failure = resp?.let { "HTTP ${it.code()}" } ?: result.exceptionOrNull().toString()
            // A 4xx won't succeed on retry.
            if (resp != null && resp.code() in 400..499) break
            if (attempt < ENDED_MAX_ATTEMPTS) {
                Log.w("HELIX_PLAYER", "POST /api/playback/ended failed ($failure); retrying")
                delay(ENDED_RETRY_BASE_DELAY_MS shl (attempt - 1))
            }
        }
        throw IllegalStateException("POST /api/playback/ended failed: $failure")
    }

    /**
     * Run a backend request that changes what's playing (play a track/album/playlist, jump,
     * next, previous...), then load the backend's new current item into Media3.
     *
     * Every such change goes through here so taps, lock-screen commands and websocket syncs
     * are applied one at a time. Throws [HelixHttpException] when the backend rejects the
     * request; network failures propagate as IOExceptions.
     */
    suspend fun changePlayback(
        context: Context,
        forceLoadStream: Boolean = false,
        forceRestart: Boolean = false,
        request: suspend (HelixApi) -> Response<String>,
    ) {
        mutex.withLock {
            val api = HelixClient.create(context, HelixPrefs.getBaseUrl(context))
            val resp = withContext(Dispatchers.IO) { request(api) }
            if (!resp.isSuccessful) throw HelixHttpException(resp.code())
            HelixTransport.refreshAndSync(context, forceLoadStream = forceLoadStream, forceRestart = forceRestart)
        }
    }

    suspend fun next(context: Context) {
        changePlayback(context, forceLoadStream = true) { it.next() }
    }

    suspend fun previous(context: Context) {
        changePlayback(context, forceLoadStream = true) { it.prev() }
    }

    suspend fun pause(context: Context) {
        mutex.withLock {
            // Optimistically pause the local player immediately so headset buttons feel responsive
            PlaybackController.pause(context)

            val api = HelixClient.create(context, HelixPrefs.getBaseUrl(context))
            val resp = runCatching { withContext(Dispatchers.IO) { api.pause() } }.getOrNull()
            
            if (resp?.isSuccessful != true) {
                // If the backend call fails (e.g. no network), we leave the local player paused.
                // The user intended to pause, and local playback is halted. The next successful
                // sync will resolve any backend mismatch.
            } else {
                // Re-apply backend truth to ensure perfect sync
                HelixTransport.refreshAndSync(context)
            }
        }
    }

    suspend fun resume(context: Context) {
        mutex.withLock {
            // Optimistically resume the local player immediately
            PlaybackController.resume(context)

            val api = HelixClient.create(context, HelixPrefs.getBaseUrl(context))
            val resp = runCatching { withContext(Dispatchers.IO) { api.resume() } }.getOrNull()
            
            if (resp?.isSuccessful != true) {
                // If we fail to tell the backend we resumed (e.g. no network), we might 
                // encounter playback errors eventually, but we let it try to play locally.
            } else {
                HelixTransport.refreshAndSync(context, forceLoadStream = true)
                HelixTransport.markInitialSynced()
            }
        }
    }
}
