package com.example.helixapp

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.helixapp.data.AccountRepository
import com.example.helixapp.data.HelixAccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class PlaybackSettingsUiState(
    val loading: Boolean = true,
    val settings: PlaybackSettings = PlaybackSettings(),
    /** "Saved", or why loading/saving failed; "" for nothing. */
    val status: String = "",
)

/** The account-wide part of Playback & queue: queue position, station buffer, default volume. */
class PlaybackSettingsViewModel(
    private val account: AccountRepository,
    private val isSignedIn: () -> Boolean,
) : ViewModel() {

    constructor(app: Application) : this(HelixAccountRepository(app), { !HelixPrefs.getSessionToken(app).isNullOrBlank() })

    private val _state = MutableStateFlow(PlaybackSettingsUiState())
    val state: StateFlow<PlaybackSettingsUiState> = _state.asStateFlow()

    init {
        if (!isSignedIn()) {
            _state.update { it.copy(loading = false, status = "Connect to Helix first") }
        } else {
            viewModelScope.launch {
                try {
                    val settings = account.playbackSettings()
                    _state.update { it.copy(loading = false, settings = settings) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, status = e.toUserMessage("Loading settings")) }
                }
            }
        }
    }

    fun setQueueAddPosition(position: String) {
        _state.update { it.copy(settings = it.settings.copy(queueAddPosition = position)) }
        save("queue_add_position", position)
    }

    /** While the slider moves; [saveStationQueueAhead] when it's released. */
    fun setStationQueueAhead(value: Int) = _state.update {
        it.copy(settings = it.settings.copy(stationQueueAhead = value.coerceIn(1, it.settings.stationQueueAheadMax)))
    }

    fun saveStationQueueAhead() = save("station_queue_ahead", _state.value.settings.stationQueueAhead)

    fun setDefaultVolume(value: Float) = _state.update { it.copy(settings = it.settings.copy(defaultVolume = value.coerceIn(0f, 1f))) }

    fun saveDefaultVolume() = save("playback_default_volume", _state.value.settings.defaultVolume.toDouble())

    private fun save(key: String, value: Any) {
        viewModelScope.launch {
            val status = try {
                account.updateSetting(key, value)
                "Saved"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.toUserMessage("Save")
            }
            _state.update { it.copy(status = status) }
        }
    }
}

data class AdminUsersUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val users: List<AdminUser> = emptyList(),
    /** "Saved", "Created …", or what went wrong; "" for nothing. */
    val status: String = "",
)

/** Settings → Users (administrators only). */
class AdminUsersViewModel(private val account: AccountRepository) : ViewModel() {

    constructor(app: Application) : this(HelixAccountRepository(app))

    private val _state = MutableStateFlow(AdminUsersUiState())
    val state: StateFlow<AdminUsersUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, status = "") }
        viewModelScope.launch {
            try {
                if (!account.me().isAdmin) {
                    _state.update { it.copy(loading = false, status = "Administrator access required") }
                    return@launch
                }
                val users = account.users()
                _state.update { it.copy(loading = false, users = users) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, status = e.toUserMessage("Loading users")) }
            }
        }
    }

    fun updateUser(user: AdminUser, patch: JSONObject) = saving("Update ${user.username}") {
        val updated = account.updateUser(user.id, patch)
        _state.update { s -> s.copy(users = s.users.map { if (it.id == updated.id) updated else it }, status = "Saved") }
    }

    /** [onCreated] clears the form once the user exists. */
    fun createUser(username: String, password: String, role: String, onCreated: () -> Unit) {
        val name = username.trim()
        if (name.length < 3 || password.length < 8) return
        saving("Create user") {
            val created = account.createUser(name, password, role)
            _state.update { s -> s.copy(users = (s.users + created).sortedBy { it.username.lowercase() }, status = "Created ${created.username}") }
            onCreated()
        }
    }

    private fun saving(action: String, block: suspend () -> Unit) {
        _state.update { it.copy(saving = true, status = "") }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = e.toUserMessage(action)) }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }
}
