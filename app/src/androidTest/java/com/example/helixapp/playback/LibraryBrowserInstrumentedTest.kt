package com.example.helixapp.playback

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connects a MediaBrowser to PlaybackService the way Android Auto does and walks the browse
 * tree. Runs against the signed-in server; [playingAQueueItemJumpsToIt] changes the song.
 */
@RunWith(AndroidJUnit4::class)
class LibraryBrowserInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private lateinit var browser: MediaBrowser

    @Before
    fun connect() {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        browser = onMain { MediaBrowser.Builder(ctx, token).buildAsync() }
    }

    @After
    fun disconnect() {
        instrumentation.runOnMainSync { browser.release() }
    }

    private fun <T> onMain(block: () -> ListenableFuture<T>): T {
        var f: ListenableFuture<T>? = null
        instrumentation.runOnMainSync { f = block() }
        return f!!.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun rootHasFourTabs() {
        val root = onMain { browser.getLibraryRoot(null) }
        assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
        val children = onMain { browser.getChildren(root.value!!.mediaId, 0, 100, null) }
        assertEquals(LibraryResult.RESULT_SUCCESS, children.resultCode)
        assertEquals(listOf("Queue", "Stations", "Playlists", "Recent"), children.value!!.map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun everyTabLoadsFromTheServer() {
        for (tab in listOf("queue", "stations", "playlists", "recent")) {
            val result = onMain { browser.getChildren(tab, 0, 200, null) }
            assertEquals("$tab result", LibraryResult.RESULT_SUCCESS, result.resultCode)
            val items = result.value!!
            android.util.Log.i("HELIX_TEST", "$tab: ${items.size} items, first=${items.firstOrNull()?.mediaMetadata?.title}")
            items.forEach {
                assertTrue("$tab item ${it.mediaId} playable", it.mediaMetadata.isPlayable == true)
                assertTrue("$tab item id prefix", it.mediaId.startsWith(tab.removeSuffix("s") + ":"))
            }
        }
    }

    @Test
    fun pagingSplitsTheList() {
        val all = onMain { browser.getChildren("recent", 0, 200, null) }.value!!
        if (all.size < 3) return
        val first = onMain { browser.getChildren("recent", 0, 2, null) }.value!!
        val second = onMain { browser.getChildren("recent", 1, 2, null) }.value!!
        assertEquals(all.take(2).map { it.mediaId }, first.map { it.mediaId })
        assertEquals(all.drop(2).take(2).map { it.mediaId }, second.map { it.mediaId })
    }

    @Test
    fun unknownItemIsAnError() {
        val result = onMain { browser.getItem("nope") }
        assertTrue(result.resultCode != LibraryResult.RESULT_SUCCESS)
    }

    @Test
    fun playingAQueueItemJumpsToIt() {
        val queue = onMain { browser.getChildren("queue", 0, 200, null) }.value!!
        val currentId = PlayerStateStore.state.value?.now?.queueItemId
        val currentIndex = queue.indexOfFirst { it.mediaId.endsWith(":$currentId") }
        val target = queue.getOrNull(currentIndex + 1) ?: return // nothing after the current song
        val targetQid = target.mediaId.substringAfterLast(":")

        // What Android Auto sends: a bare browse id, no stream URI.
        instrumentation.runOnMainSync {
            browser.setMediaItem(MediaItem.Builder().setMediaId(target.mediaId).build())
            browser.prepare()
            browser.play()
        }

        var playingId = ""
        var state = -1
        for (i in 0 until 40) {
            Thread.sleep(500)
            instrumentation.runOnMainSync {
                playingId = browser.currentMediaItem?.mediaId.orEmpty()
                state = browser.playbackState
            }
            if (playingId == targetQid && state == Player.STATE_READY) break
        }
        android.util.Log.i("HELIX_TEST", "browser play: target=$targetQid playing=$playingId state=$state")
        assertEquals(targetQid, playingId)
        assertEquals(Player.STATE_READY, state)
        assertEquals(targetQid, PlayerStateStore.state.value?.now?.queueItemId)
        instrumentation.runOnMainSync { browser.pause() }
    }
}
