package com.example.helixapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simple in-memory "refresh bus" used to notify screens that cached state should be refetched.
 * StateFlow is used (instead of SharedFlow) so screens that are not currently composed will
 * still observe the latest tick value when they come back.
 */
object RefreshSignals {
    private val _playlists = MutableStateFlow(0)
    val playlists = _playlists.asStateFlow()
    fun bumpPlaylists() {
        _playlists.value = _playlists.value + 1
    }

    private val _player = MutableStateFlow(0)
    val player = _player.asStateFlow()
    fun bumpPlayer() {
        _player.value = _player.value + 1
    }
}
