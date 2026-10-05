package com.example.helixapp

import com.example.helixapp.data.TimedCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TimedCacheTest {
    private var now = 0L
    private var loads = 0
    private val cache = TimedCache<Int>(ttlMs = 1_000, clock = { now })

    private suspend fun load(): Int {
        loads++
        return loads
    }

    @Test
    fun reusesTheValueUntilItGoesStale() = runTest {
        assertEquals(1, cache.get(::load))
        now = 999
        assertEquals(1, cache.get(::load))
        now = 1_000
        assertEquals(2, cache.get(::load))
        assertEquals(2, loads)
    }

    @Test
    fun invalidateForcesAReload() = runTest {
        cache.get(::load)
        cache.invalidate()
        assertEquals(2, cache.get(::load))
    }

    @Test
    fun failedLoadsAreNotCached() = runTest {
        runCatching { cache.get { error("offline") } }
        assertEquals(1, cache.get(::load))
    }

    @Test
    fun concurrentCallersShareOneLoad() = runTest {
        val results = (1..5).map {
            async { cache.get { delay(100); load() } }
        }.awaitAll()
        assertEquals(listOf(1, 1, 1, 1, 1), results)
        assertEquals(1, loads)
    }
}
