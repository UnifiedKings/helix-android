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
            val api = HelixClient.create(context, HelixPrefs.getBaseUrl(context))
            val resp = withContext(Dispatchers.IO) { api.pause() }
            if (!resp.isSuccessful) {
                throw IllegalStateException("Pause failed (HTTP ${resp.code()})")
            }
            // Re-apply backend truth so Media3/lockscreen cannot remain in the opposite state.
            HelixTransport.refreshAndSync(context)
        }
    }

    suspend fun resume(context: Context) {
        mutex.withLock {
            val api = HelixClient.create(context, HelixPrefs.getBaseUrl(context))
            val resp = withContext(Dispatchers.IO) { api.resume() }
            if (!resp.isSuccessful) {
                throw IllegalStateException("Resume failed (HTTP ${resp.code()})")
            }
            // Force reload so a stale/ended local stream cannot immediately fire ENDED.
            HelixTransport.refreshAndSync(context, forceLoadStream = true)
            HelixTransport.markInitialSynced()
        }
    }
}
