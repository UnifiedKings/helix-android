package com.example.helixapp.playback

import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sends play-from-search the way Google Assistant does: a platform MediaBrowser connects to
 * PlaybackService, then its session's transport controls call playFromSearch. Runs against
 * the signed-in server and changes what's playing.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSearchInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private lateinit var browser: MediaBrowser
    private lateinit var controller: MediaController

    @Before
    fun connect() {
        val connected = CountDownLatch(1)
        instrumentation.runOnMainSync {
            browser = MediaBrowser(
                ctx,
                ComponentName(ctx, PlaybackService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                },
                null,
            )
            browser.connect()
        }
        assertTrue("browser connected", connected.await(15, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { controller = MediaController(ctx, browser.sessionToken) }
    }

    @After
    fun disconnect() {
        instrumentation.runOnMainSync {
            controller.transportControls.pause()
            browser.disconnect()
        }
    }

    private fun serverTitle(): String = runBlocking {
        PlayerStateStore.refresh(ctx)
        PlayerStateStore.state.value?.now?.title.orEmpty()
    }

    private fun serverQueueItemId(): String = runBlocking {
        PlayerStateStore.refresh(ctx)
        PlayerStateStore.state.value?.now?.queueItemId.orEmpty()
    }

    /** Sends the search and waits until the server's current song changes and satisfies [check]. */
    private fun playFromSearch(query: String, extras: Bundle? = null, timeoutS: Int = 20, check: (String) -> Boolean): String {
        val before = serverQueueItemId()
        instrumentation.runOnMainSync { controller.transportControls.playFromSearch(query, extras ?: Bundle()) }
        var title = ""
        for (i in 0 until timeoutS * 2) {
            Thread.sleep(500)
            title = serverTitle()
            if (serverQueueItemId() != before && check(title)) break
        }
        android.util.Log.i("HELIX_TEST", "voice '$query' -> server now '$title'")
        return title
    }

    private fun waitForPhonePlaying(): Boolean {
        for (i in 0 until 30) {
            Thread.sleep(500)
            var state = 0
            instrumentation.runOnMainSync { state = controller.playbackState?.state ?: 0 }
            if (state == android.media.session.PlaybackState.STATE_PLAYING) return true
        }
        return false
    }

    @Test
    fun songByName() {
        val title = playFromSearch("How To Love by Lil Wayne") { it.contains("How To Love", ignoreCase = true) }
        assertTrue("server plays How To Love, got '$title'", title.contains("How To Love", ignoreCase = true))
        assertTrue("phone plays", waitForPhonePlaying())
    }

    @Test
    fun songWithAssistantHints() {
        val extras = Bundle().apply {
            putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            putString(android.provider.MediaStore.EXTRA_MEDIA_TITLE, "Cowboys And Angels")
            putString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST, "Jessie Murph")
        }
        val title = playFromSearch("cowboys and angels by jessie murph", extras) { it.contains("Cowboys", ignoreCase = true) }
        assertTrue("server plays Cowboys And Angels, got '$title'", title.contains("Cowboys", ignoreCase = true))
        assertTrue("phone plays", waitForPhonePlaying())
    }

    @Test
    fun playlistByName() {
        val playlists = runBlocking { HelixLibraryBrowser.children(ctx, HelixLibraryBrowser.PLAYLISTS_ID) }
        if (playlists.none { it.mediaMetadata.title.toString().equals("Liked Songs", true) }) return
        val title = playFromSearch("my liked songs") { it.isNotBlank() }
        assertTrue("something from Liked Songs plays", title.isNotBlank())
        assertTrue("phone plays", waitForPhonePlaying())
    }

    @Test
    fun existingStationByName() {
        val stations = runBlocking { HelixLibraryBrowser.children(ctx, HelixLibraryBrowser.STATIONS_ID) }
        val station = stations.firstOrNull() ?: return
        val name = station.mediaMetadata.title.toString()
        val title = playFromSearch("$name radio", timeoutS = 45) { it.isNotBlank() }
        assertTrue("station '$name' started", title.isNotBlank())
        assertTrue("phone plays", waitForPhonePlaying())
    }

    @Test
    fun androidAutoSearchButton() {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        fun <T> onMain(block: () -> ListenableFuture<T>): T {
            var f: ListenableFuture<T>? = null
            instrumentation.runOnMainSync { f = block() }
            return f!!.get(30, TimeUnit.SECONDS)
        }
        val media3 = onMain { androidx.media3.session.MediaBrowser.Builder(ctx, token).buildAsync() }
        try {
            assertEquals(LibraryResult.RESULT_SUCCESS, onMain { media3.search("jessie murph", null) }.resultCode)
            val results = onMain { media3.getSearchResult("jessie murph", 0, 50, null) }
            assertEquals(LibraryResult.RESULT_SUCCESS, results.resultCode)
            val items = results.value!!
            android.util.Log.i("HELIX_TEST", "search: ${items.map { it.mediaId.substringBefore(':') + "=" + it.mediaMetadata.title }}")
            assertTrue("has song results", items.any { it.mediaId.startsWith("song:") })
            assertTrue("finds the Jessie Murph station", items.any { it.mediaId.startsWith("station:") })
            items.forEach { assertTrue(it.mediaMetadata.isPlayable == true) }
        } finally {
            instrumentation.runOnMainSync { media3.release() }
        }
    }
}
