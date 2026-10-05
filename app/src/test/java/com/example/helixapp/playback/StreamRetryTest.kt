package com.example.helixapp.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRetryTest {

    private val stream = "https://helix.example/api/stream/9f1c2a7e-0b3d-4c1e-a2f4-6d8e0c1b2a3f"

    @Test
    fun temporaryServerAndProxyErrorsAreRetried() {
        listOf(404, 502, 503, 504).forEach { assertTrue("HTTP $it", PlaybackService.isRetryableStreamError(stream, it)) }
    }

    @Test
    fun otherErrorsAreNotRetried() {
        listOf(400, 401, 403, 500).forEach { assertFalse("HTTP $it", PlaybackService.isRetryableStreamError(stream, it)) }
        assertFalse(PlaybackService.isRetryableStreamError(stream, null))
    }

    @Test
    fun digitsInTheQueueIdDontMatter() {
        // Text matching used to treat any message containing "404"/"503" (e.g. in the URL) as retryable.
        val idWith404 = "https://helix.example/api/stream/a404b503-0000-4000-8000-000000000000"
        assertFalse(PlaybackService.isRetryableStreamError(idWith404, 500))
    }

    @Test
    fun onlyHelixStreamsAreRetried() {
        assertFalse(PlaybackService.isRetryableStreamError("https://helix.example/api/art/x", 503))
    }
}
