package com.example.helixapp

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun utcMs(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0, s: Int = 0, ms: Int = 0): Long =
        Calendar.getInstance(utc, Locale.US).apply {
            clear(); set(y, mo - 1, d, h, mi, s); set(Calendar.MILLISECOND, ms)
        }.timeInMillis

    @Test
    fun parsesServerTimestampsWithMicrosecondsAndZ() {
        assertEquals(utcMs(2026, 10, 5, 6, 28, 54, 382), parseServerTimestamp("2026-10-05T06:28:54.382477Z"))
    }

    @Test
    fun parsesTimestampsWithoutFractionOrWithOffset() {
        assertEquals(utcMs(2026, 10, 5, 6, 28, 54), parseServerTimestamp("2026-10-05T06:28:54"))
        assertEquals(utcMs(2026, 10, 5, 6, 28, 54, 500), parseServerTimestamp("2026-10-05T06:28:54.5+00:00"))
    }

    @Test
    fun unreadableTimestampsAreZero() {
        assertEquals(0L, parseServerTimestamp(""))
        assertEquals(0L, parseServerTimestamp("yesterday"))
    }

    @Test
    fun parsesAPageOfHistory() {
        val page = parseHistoryPage(
            """{"has_more": true, "items": [
                {"id": "h1", "title": "Forever", "artist": "Jessie Murph", "event": "skipped",
                 "created_at": "2026-10-05T06:28:54Z", "yt_video_id": "abc", "duration_ms": 180000}
            ]}"""
        )
        assertTrue(page.hasMore)
        val item = page.items.single()
        assertEquals("Forever", item.title)
        assertTrue(item.skipped)
        assertEquals("abc", item.ytVideoId)
        assertEquals(180000L, item.durationMs)
        assertEquals(utcMs(2026, 10, 5, 6, 28, 54), item.playedAtMs)
    }

    private fun item(id: String, playedAt: Long) = HistoryItemUi(
        id = id, title = id, artist = "", album = "", event = "completed", playedAtMs = playedAt,
        artUrl = "", durationMs = 0, source = "", ytVideoId = "", ytBrowseId = "",
        subsonicSongId = "", mbRecordingId = "", mbArtistId = "",
    )

    @Test
    fun groupsByTodayYesterdayAndDate() {
        val now = utcMs(2026, 10, 5, 18)
        val sections = historySections(
            listOf(
                item("a", utcMs(2026, 10, 5, 9)),
                item("b", utcMs(2026, 10, 5, 1)),
                item("c", utcMs(2026, 10, 4, 23)),
                item("d", utcMs(2026, 10, 3, 8)),
                item("e", utcMs(2025, 12, 31, 8)),
            ),
            nowMs = now, timeZone = utc, locale = Locale.US,
        )
        assertEquals(
            listOf("Today", "Yesterday", "Sat, Oct 3", "Wed, Dec 31, 2025"),
            sections.map { it.first },
        )
        assertEquals(listOf("a", "b"), sections[0].second.map { it.id })
    }

    @Test
    fun completedSongsAreNotSkipped() {
        assertFalse(item("x", 1).skipped)
    }
}
