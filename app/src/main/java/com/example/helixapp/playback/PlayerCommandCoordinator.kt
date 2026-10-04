package com.example.helixapp.playback

import android.content.Context
import com.example.helixapp.HelixClient
import com.example.helixapp.HelixPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val mutex = Mutex()

    suspend fun syncFromBackend(context: Context, forceLoadStream: Boolean = false) {
        mutex.withLock {
            HelixTransport.refreshAndSync(context, forceLoadStream = forceLoadStream)
        }
    }

    suspend fun next(context: Context) {
        mutex.withLock {
            val api = HelixClient.create(context, HelixPrefs.getBaseUrl(context))
            val resp = withContext(Dispatchers.IO) { api.next() }
            if (!resp.isSuccessful) {
                throw IllegalStateException("Next failed (HTTP ${resp.code()})")
            }
            HelixTransport.refreshAndSync(context, forceLoadStream = true)
        }
    }

    suspend fun previous(context: Context) {
        mutex.withLock {
            val api = HelixClient.create(context, HelixPrefs.getBaseUrl(context))
            val resp = withContext(Dispatchers.IO) { api.prev() }
            if (!resp.isSuccessful) {
                throw IllegalStateException("Previous failed (HTTP ${resp.code()})")
            }
            HelixTransport.refreshAndSync(context, forceLoadStream = true)
        }
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
