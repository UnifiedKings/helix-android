package com.example.helixapp

import com.example.helixapp.data.StationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StationsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun station(id: String, name: String = id, type: String = "artist_radio", config: JSONObject = JSONObject()) =
        StationUi(id, name, type, config, seedType = "artist", seedTitle = "", seedArtist = "", discovery = 0.35f, seedInfluence = 0.75f, thumbnailUrl = "")

    private fun option(
        key: String,
        type: String = "string",
        required: Boolean = false,
        minItems: Int? = null,
        category: String = "options",
        categoryLabel: String = "",
        categoryOrder: Int = 999,
        order: Int = 999,
        default: Any? = null,
    ) = StationConfigOptionUi(key, key, type, "", required, default, null, null, null, emptyList(), minItems, null, category, categoryLabel, categoryOrder, order)

    private class FakeStations : StationRepository {
        val stations = mutableListOf<StationUi>()
        val calls = mutableListOf<String>()
        var failProviders = false
        var failChange = false
        val payloads = mutableListOf<JSONObject>()

        override suspend fun list() = stations.toList()
        override suspend fun createArtistStation(artist: String) = ""
        override suspend fun providers(): List<StationProviderUi> {
            if (failProviders) throw HelixHttpException(500)
            return listOf(StationProviderUi("artist_radio", "Artist radio", "", emptyList()))
        }
        override suspend fun create(payload: JSONObject) {
            if (failChange) throw HelixHttpException(500)
            calls += "create"; payloads += payload
        }
        override suspend fun update(stationId: String, payload: JSONObject) {
            if (failChange) throw HelixHttpException(500)
            calls += "update $stationId"; payloads += payload
        }
        override suspend fun delete(stationId: String) {
            if (failChange) throw HelixHttpException(500)
            calls += "delete $stationId"; stations.removeAll { it.id == stationId }
        }
    }

    // ---- ViewModel ----------------------------------------------------------------------------

    @Test
    fun loadsStationsAndProviders() = runTest(dispatcher) {
        val repo = FakeStations().apply { stations += station("s1") }
        val vm = StationsViewModel(repo, isSignedIn = { true })
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("s1"), s.stations.map { it.id })
        assertEquals("Artist radio", s.providerFor(s.stations.single())?.displayName)
        assertNull(s.error)
    }

    @Test
    fun theListShowsEvenIfStationTypesFail() = runTest(dispatcher) {
        val repo = FakeStations().apply { stations += station("s1"); failProviders = true }
        val vm = StationsViewModel(repo, isSignedIn = { true })
        advanceUntilIdle()
        assertEquals(1, vm.state.value.stations.size)
        assertTrue(vm.state.value.providers.isEmpty())
    }

    @Test
    fun signedOut() = runTest(dispatcher) {
        val vm = StationsViewModel(FakeStations(), isSignedIn = { false })
        advanceUntilIdle()
        assertFalse(vm.state.value.signedIn)
    }

    @Test
    fun saveSendsTheConfigWithLegacyMirrors() = runTest(dispatcher) {
        val repo = FakeStations().apply { stations += station("s1") }
        val vm = StationsViewModel(repo, isSignedIn = { true })
        advanceUntilIdle()
        val config = JSONObject()
            .put("seed_artists", JSONArray().put(JSONObject().put("name", "Lil Wayne")))
            .put("discovery", 0.5)
        vm.save(station("s1", name = "Weezy"), config)
        advanceUntilIdle()
        val payload = repo.payloads.single()
        assertEquals("update s1", repo.calls.single())
        assertEquals("Weezy", payload.getString("name"))
        assertEquals("Lil Wayne", payload.getString("seed_artist"))
        assertEquals("artist", payload.getString("seed_type"))
        assertEquals(0.5, payload.getDouble("discovery"), 0.0)
    }

    @Test
    fun deleteRemovesItAndFailuresReloadTheList() = runTest(dispatcher) {
        val repo = FakeStations().apply { stations += listOf(station("s1"), station("s2")) }
        val vm = StationsViewModel(repo, isSignedIn = { true })
        advanceUntilIdle()
        vm.delete(vm.state.value.stations.first())
        advanceUntilIdle()
        assertEquals(listOf("s2"), vm.state.value.stations.map { it.id })

        repo.failChange = true
        vm.delete(vm.state.value.stations.first())
        assertTrue(vm.state.value.stations.isEmpty()) // optimistic…
        advanceUntilIdle()
        assertEquals(listOf("s2"), vm.state.value.stations.map { it.id }) // …then reloaded
    }

    // ---- Config logic ---------------------------------------------------------------------------

    @Test
    fun optionsGroupIntoOrderedSections() {
        val sections = groupStationOptions(
            listOf(
                option("discovery", category = "tuning", categoryOrder = 20, order = 2),
                option("seed_artists", category = "seeds", categoryLabel = "Seeds", categoryOrder = 10, order = 0),
                option("seed_influence", category = "tuning", categoryOrder = 20, order = 1),
            )
        )
        assertEquals(listOf("seeds", "tuning"), sections.map { it.id })
        assertEquals("Seeds", sections[0].label)
        assertEquals(listOf("seed_influence", "discovery"), sections[1].options.map { it.key })
        assertTrue(groupStationOptions(emptyList()).isEmpty())
    }

    @Test
    fun configPayloadUsesValuesOrDefaults() {
        val config = buildConfigPayload(
            options = listOf(
                option("repeat", type = "boolean", default = true),
                option("pool", type = "integer", default = 10),
                option("discovery", type = "number", default = 0.35),
                option("seed_artists", type = "artist_search"),
                option("note"),
            ),
            values = mapOf("pool" to "25", "note" to "hi"),
            boolValues = emptyMap(),
            multiValues = emptyMap(),
            artistValues = mapOf("seed_artists" to listOf(StationArtistSeedUi("Drake", "UC1", "", ""))),
            trackValues = emptyMap(),
        )
        assertTrue(config.getBoolean("repeat"))
        assertEquals(25, config.getInt("pool"))
        assertEquals(0.35, config.getDouble("discovery"), 0.0)
        assertEquals("Drake", config.getJSONArray("seed_artists").getJSONObject(0).getString("name"))
        assertEquals("hi", config.getString("note"))
    }

    @Test
    fun requiredOptionsAndMinimumSelections() {
        val options = listOf(option("seed_artists", type = "artist_search", required = true), option("name", required = true))
        val empty = JSONObject().put("seed_artists", JSONArray()).put("name", "x")
        val ok = JSONObject().put("seed_artists", JSONArray().put(JSONObject().put("name", "Drake"))).put("name", "x")
        assertFalse(hasRequiredOptions(options, empty))
        assertTrue(hasRequiredOptions(options, ok))
        assertFalse(hasRequiredOptions(options, JSONObject(ok.toString()).put("name", " ")))
        val needsTwo = listOf(option("seed_artists", type = "artist_search", minItems = 2))
        assertFalse(hasRequiredOptions(needsTwo, ok))
    }

    @Test
    fun trackSeedsMakeATrackStation() {
        val config = JSONObject().put(
            "seed_tracks",
            JSONArray().put(JSONObject().put("title", "Dirty").put("artist", "Jessie Murph").put("video_id", "v1")),
        )
        val payload = JSONObject()
        addLegacyStationMirrors(payload, config)
        assertEquals("track", payload.getString("seed_type"))
        assertEquals("Dirty", payload.getString("seed_title"))
        assertEquals("Jessie Murph", payload.getString("seed_artist"))
    }

    @Test
    fun seedSummaries() {
        val artists = JSONObject().put(
            "seed_artists",
            JSONArray().put(JSONObject().put("name", "A")).put(JSONObject().put("name", "B")).put(JSONObject().put("name", "C")),
        )
        assertEquals("A, B +1", stationSeedSummary(station("s", config = artists)))
        val track = JSONObject().put("seed_tracks", JSONArray().put(JSONObject().put("title", "Dirty").put("artist", "Jessie Murph")))
        assertEquals("Dirty — Jessie Murph", stationSeedSummary(station("s", config = track)))
        // Old stations keep their seed at the top level.
        assertEquals("Lil Wayne", stationSeedSummary(station("s").copy(seedArtist = "Lil Wayne")))
        // Comma-separated legacy artist lists.
        assertEquals("X, Y", stationSeedSummary(station("s", config = JSONObject().put("seed_artists", "X, Y"))))
    }
}
