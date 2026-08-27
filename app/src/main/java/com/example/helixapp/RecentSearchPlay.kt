package com.example.helixapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Frontend-only "Recents" for the Search screen.
 *
 * We intentionally keep this separate from Compose + playback so it can be reused later (Browse,
 * Library, etc.) or swapped to backend persistence if you ever want cross-device sync.
 */
object RecentSearchPlay {

    private const val PREFS = "helix_prefs"
    private const val KEY = "recent_search_play_v1"
    private const val MAX = 20

    enum class Kind { SONG, ALBUM }

    data class Item(
        val kind: Kind,
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val year: String,
        val thumbnailUrl: String,
        val source: String = "ytmusic",
        val subsonicSongId: String = "",
        val ts: Long,
    )

    fun get(ctx: Context): List<Item> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = when (o.optString("kind", "")) {
                        "ALBUM" -> Kind.ALBUM
                        else -> Kind.SONG
                    }
                    val id = o.optString("id", "").trim()
                    if (id.isBlank()) continue
                    add(
                        Item(
                            kind = kind,
                            id = id,
                            title = o.optString("title", ""),
                            artist = o.optString("artist", ""),
                            album = o.optString("album", ""),
                            year = o.optString("year", ""),
                            thumbnailUrl = o.optString("thumbnail_url", ""),
                            source = o.optString("source", "ytmusic"),
                            subsonicSongId = o.optString("subsonic_song_id", ""),
                            ts = o.optLong("ts", 0L),
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun addSong(ctx: Context, song: SearchSong) {
        val stableId = when {
            song.isFromSubsonic && song.subsonicSongId.isNotBlank() -> song.subsonicSongId
            song.videoId.isNotBlank() -> song.videoId
            else -> return
        }
        val item = Item(
            kind = Kind.SONG,
            id = stableId,
            title = song.title,
            artist = song.artist,
            album = song.album,
            year = "",
            thumbnailUrl = song.thumbnailUrl,
            source = if (song.isFromSubsonic) "subsonic" else "ytmusic",
            subsonicSongId = song.subsonicSongId,
            ts = System.currentTimeMillis(),
        )
        upsert(ctx, item)
    }

    fun addAlbum(ctx: Context, album: SearchAlbum) {
        if (album.browseId.isBlank()) return
        val item = Item(
            kind = Kind.ALBUM,
            id = album.browseId,
            title = album.title,
            artist = album.artist,
            album = "",
            year = album.year,
            thumbnailUrl = album.thumbnailUrl,
            source = if (album.isFromSubsonic) "subsonic" else "ytmusic",
            ts = System.currentTimeMillis(),
        )
        upsert(ctx, item)
    }

    private fun upsert(ctx: Context, item: Item) {
        val current = get(ctx)
        val deduped = current.filterNot { it.kind == item.kind && it.id == item.id }
        val next = (listOf(item) + deduped).take(MAX)

        val arr = JSONArray()
        for (i in next) {
            arr.put(
                JSONObject().apply {
                    put("kind", i.kind.name)
                    put("id", i.id)
                    put("title", i.title)
                    put("artist", i.artist)
                    put("album", i.album)
                    put("year", i.year)
                    put("thumbnail_url", i.thumbnailUrl)
                    put("source", i.source)
                    if (i.subsonicSongId.isNotBlank()) put("subsonic_song_id", i.subsonicSongId)
                    put("ts", i.ts)
                }
            )
        }

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
