package com.example.helixapp

import com.example.helixapp.data.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeAccount(var role: String = "admin") : AccountRepository {
        val saved = mutableListOf<Pair<String, Any>>()
        var failSave = false
        var failCreate: Exception? = null
        val users = mutableListOf(user("2", "zoe"), user("1", "jake"))

        override suspend fun me() = AccountInfo("jake", role)
        override suspend fun logout() = Unit
        override suspend fun playbackSettings() = PlaybackSettings("next", 5, 8, 0.6f)
        override suspend fun updateSetting(key: String, value: Any) {
            if (failSave) throw HelixHttpException(500)
            saved += key to value
        }
        override suspend fun users() = users.toList()
        override suspend fun updateUser(userId: String, patch: JSONObject): AdminUser {
            val u = users.first { it.id == userId }
            return u.copy(role = patch.optString("role", u.role), isActive = patch.optBoolean("is_active", u.isActive))
        }
        override suspend fun createUser(username: String, password: String, role: String): AdminUser {
            failCreate?.let { throw it }
            return user("3", username).copy(role = role)
        }

        companion object {
            fun user(id: String, name: String) = AdminUser(id, name, "user", isActive = true, subsonicImportOverride = false, canImportSubsonic = false)
        }
    }

    @Test
    fun playbackSettingsLoadAndSave() = runTest(dispatcher) {
        val account = FakeAccount()
        val vm = PlaybackSettingsViewModel(account, isSignedIn = { true })
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals("next", vm.state.value.settings.queueAddPosition)

        vm.setQueueAddPosition("append")
        vm.setStationQueueAhead(99) // clamped to the server's max
        vm.saveStationQueueAhead()
        vm.setDefaultVolume(0.25f)
        vm.saveDefaultVolume()
        advanceUntilIdle()
        assertEquals(
            listOf<Pair<String, Any>>("queue_add_position" to "append", "station_queue_ahead" to 8, "playback_default_volume" to 0.25),
            account.saved,
        )
        assertEquals("Saved", vm.state.value.status)

        account.failSave = true
        vm.saveDefaultVolume()
        advanceUntilIdle()
        assertEquals("Save failed (HTTP 500)", vm.state.value.status)
    }

    @Test
    fun playbackSettingsNeedASession() = runTest(dispatcher) {
        val vm = PlaybackSettingsViewModel(FakeAccount(), isSignedIn = { false })
        advanceUntilIdle()
        assertEquals("Connect to Helix first", vm.state.value.status)
    }

    @Test
    fun adminUsersRequireTheAdminRole() = runTest(dispatcher) {
        val vm = AdminUsersViewModel(FakeAccount(role = "user"))
        advanceUntilIdle()
        assertEquals("Administrator access required", vm.state.value.status)
        assertTrue(vm.state.value.users.isEmpty())
    }

    @Test
    fun adminUsersUpdateAndCreate() = runTest(dispatcher) {
        val account = FakeAccount()
        val vm = AdminUsersViewModel(account)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.users.size)

        vm.updateUser(vm.state.value.users.first { it.username == "zoe" }, JSONObject().put("role", "admin"))
        advanceUntilIdle()
        assertEquals("admin", vm.state.value.users.first { it.username == "zoe" }.role)
        assertEquals("Saved", vm.state.value.status)

        var cleared = false
        vm.createUser("ab", "longpassword", "user") { cleared = true } // name too short: ignored
        vm.createUser("  amy ", "longpassword", "user") { cleared = true }
        advanceUntilIdle()
        assertTrue(cleared)
        assertEquals(listOf("amy", "jake", "zoe"), vm.state.value.users.map { it.username }.sorted())
        assertEquals("Created amy", vm.state.value.status)
        assertFalse(vm.state.value.saving)

        account.failCreate = HelixHttpException(400, "Username already exists")
        cleared = false
        vm.createUser("amy", "longpassword", "user") { cleared = true }
        advanceUntilIdle()
        assertFalse(cleared)
        assertEquals("Create user failed (HTTP 400): Username already exists", vm.state.value.status)
    }

    @Test
    fun accountParsers() {
        assertTrue(parseAccountInfo("""{"username": "jake", "role": "admin"}""").isAdmin)
        val s = parsePlaybackSettings(
            """{"settings": {"queue_add_position": "next", "station_queue_ahead": 50, "playback_default_volume": 1.7},
                "limits": {"station_queue_ahead_max": 12}}"""
        )
        assertEquals(PlaybackSettings("next", 12, 12, 1f), s)
        assertEquals(PlaybackSettings(), parsePlaybackSettings("{}"))
        val users = parseAdminUsers("""[{"id": "1", "username": "jake", "role": "admin", "can_import_subsonic": true}]""")
        assertEquals("admin", users.single().role)
        assertTrue(users.single().canImportSubsonic)
        assertTrue(users.single().isActive)
    }
}
