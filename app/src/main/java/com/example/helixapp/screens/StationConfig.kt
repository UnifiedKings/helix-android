package com.example.helixapp

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject


// Station config logic shared by the create and tune dialogs: grouping a provider's options,
// reading seeds out of a config, building the config payload, and display formatting.

internal data class StationConfigSectionUi(
    val id: String,
    val label: String,
    val order: Int,
    val options: List<StationConfigOptionUi>,
)

internal data class StationArtistSeedUi(
    val name: String,
    val browseId: String,
    val artUrl: String,
    val thumbnailUrl: String,
)

internal data class StationTrackSeedUi(
    val title: String,
    val artist: String,
    val album: String,
    val videoId: String,
    val artUrl: String,
    val thumbnailUrl: String,
)

internal const val LEGACY_SONG_RADIO_SEED_KEY = "__song_radio_seed"
internal const val LEGACY_SIMILAR_ARTIST_SEED_KEY = "__similar_artist_seed"

internal fun StationProviderUi.hasSearchOption(type: String): Boolean =
    configOptions.any { it.type == type }

internal fun StationProviderUi.androidConfigOptions(): List<StationConfigOptionUi> {
    if (stationType != "artist_collection" || hasSearchOption("artist_search")) return configOptions
    val fallback = StationConfigOptionUi(
        key = "seed_artists",
        label = "Seed artists",
        type = "artist_search",
        description = "Search YouTube Music and choose the artists this station is allowed to play.",
        required = true,
        defaultValue = JSONArray(),
        min = null,
        max = null,
        step = null,
        choices = emptyList(),
        minItems = 1,
        maxItems = 100,
        category = "seeds",
        categoryLabel = "Seeds",
        categoryOrder = 10,
        order = 0,
    )
    return listOf(fallback) + configOptions.filterNot { it.key == "seed_artists" }
}


internal fun legacySongRadioSeedOption() = StationConfigOptionUi(
    key = LEGACY_SONG_RADIO_SEED_KEY,
    label = "Seed song",
    type = "track_search",
    description = "Search YouTube Music and choose the exact song this radio should be built around.",
    required = true,
    defaultValue = JSONArray(),
    min = null,
    max = null,
    step = null,
    choices = emptyList(),
    minItems = 1,
    maxItems = 1,
    category = "seeds",
    categoryLabel = "Seeds",
    categoryOrder = 10,
    order = 0,
)

internal fun legacySimilarArtistSeedOption() = StationConfigOptionUi(
    key = LEGACY_SIMILAR_ARTIST_SEED_KEY,
    label = "Seed artist",
    type = "artist_search",
    description = "Search YouTube Music and choose the exact artist this radio should be built around.",
    required = true,
    defaultValue = JSONArray(),
    min = null,
    max = null,
    step = null,
    choices = emptyList(),
    minItems = 1,
    maxItems = 1,
    category = "seeds",
    categoryLabel = "Seeds",
    categoryOrder = 10,
    order = 0,
)

internal fun applyLegacySearchSeeds(
    provider: StationProviderUi?,
    config: JSONObject,
    artistValues: Map<String, List<StationArtistSeedUi>>,
    trackValues: Map<String, List<StationTrackSeedUi>>,
) {
    if (provider?.stationType == "song_radio" && provider.hasSearchOption("track_search").not()) {
        trackValues[LEGACY_SONG_RADIO_SEED_KEY].orEmpty().firstOrNull()?.let { track ->            config.put("seed_type", "track")
            config.put("seed_title", track.title)
            config.put("seed_artist", track.artist)
            config.put("seed_video_id", track.videoId)
            config.put("seed_album", track.album)
        }
    }
    if (provider?.stationType == "similar_artist" && provider.hasSearchOption("artist_search").not()) {
        artistValues[LEGACY_SIMILAR_ARTIST_SEED_KEY].orEmpty().firstOrNull()?.let { artist ->
            config.put("seed_type", "artist")
            config.put("seed_artist", artist.name)
            config.put("seed_artist_id", artist.browseId)
        }
    }
}

internal fun groupStationOptions(options: List<StationConfigOptionUi>): List<StationConfigSectionUi> {
    if (options.isEmpty()) return emptyList()
    return options
        .groupBy { it.category.ifBlank { "options" } }
        .map { (categoryId, categoryOptions) ->
            val first = categoryOptions.minWithOrNull(
                compareBy<StationConfigOptionUi>({ it.categoryOrder }, { it.order }, { it.label.lowercase() })
            ) ?: categoryOptions.first()
            StationConfigSectionUi(
                id = categoryId,
                label = first.categoryLabel.ifBlank { humanizeCategory(categoryId) },
                order = first.categoryOrder,
                options = categoryOptions.sortedWith(compareBy({ it.order }, { it.label.lowercase() })),
            )
        }
        .sortedWith(compareBy({ it.order }, { it.label.lowercase() }))
}

internal fun seedOptionState(
    option: StationConfigOptionUi,
    config: JSONObject,
    values: MutableMap<String, String>,
    boolValues: MutableMap<String, Boolean>,
    multiValues: MutableMap<String, Set<String>>,
    artistValues: MutableMap<String, List<StationArtistSeedUi>>,
    trackValues: MutableMap<String, List<StationTrackSeedUi>>,
) {
    when (option.type) {
        "boolean" -> boolValues[option.key] = config.optBoolean(option.key, option.defaultAsBoolean())
        "multiselect" -> {
            val selected = mutableSetOf<String>()
            val arr = config.optJSONArray(option.key)
            if (arr != null) {
                for (i in 0 until arr.length()) selected.add(arr.optString(i))
            } else {
                option.defaultValue?.toString()
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.let(selected::addAll)
            }
            multiValues[option.key] = selected
        }
        "artist_search" -> {
            artistValues[option.key] = parseArtistSeedSelections(config, option)
        }
        "track_search" -> {
            trackValues[option.key] = parseTrackSeedSelections(config, option)
        }
        else -> {
            values[option.key] = if (config.has(option.key) && !config.isNull(option.key)) {
                config.opt(option.key).toString()
            } else {
                option.defaultAsString()
            }
        }
    }
}

internal fun buildConfigPayload(
    options: List<StationConfigOptionUi>,
    values: Map<String, String>,
    boolValues: Map<String, Boolean>,
    multiValues: Map<String, Set<String>>,
    artistValues: Map<String, List<StationArtistSeedUi>>,
    trackValues: Map<String, List<StationTrackSeedUi>>,
): JSONObject {
    val config = JSONObject()
    options.forEach { option ->
        when (option.type) {
            "boolean" -> config.put(option.key, boolValues[option.key] ?: option.defaultAsBoolean())
            "integer" -> config.put(option.key, values[option.key]?.toIntOrNull() ?: option.defaultAsInt())
            "number" -> config.put(option.key, values[option.key]?.toDoubleOrNull() ?: option.defaultAsDouble())
            "multiselect" -> {
                val arr = JSONArray()
                multiValues[option.key].orEmpty().forEach { arr.put(it) }
                config.put(option.key, arr)
            }
            "artist_search" -> {
                val arr = JSONArray()
                artistValues[option.key].orEmpty().forEach { artist ->
                    arr.put(
                        JSONObject()
                            .put("name", artist.name)
                            .put("browse_id", artist.browseId)
                            .put("art_url", artist.artUrl)
                            .put("thumbnail_url", artist.thumbnailUrl)
                    )
                }
                config.put(option.key, arr)
            }
            "track_search" -> {
                val arr = JSONArray()
                trackValues[option.key].orEmpty().forEach { track ->
                    arr.put(
                        JSONObject()
                            .put("title", track.title)
                            .put("artist", track.artist)
                            .put("album", track.album)
                            .put("video_id", track.videoId)
                            .put("art_url", track.artUrl)
                            .put("thumbnail_url", track.thumbnailUrl)
                    )
                }
                config.put(option.key, arr)
            }
            else -> config.put(option.key, values[option.key].orEmpty())
        }
    }
    return config
}

internal fun hasRequiredOptions(
    options: List<StationConfigOptionUi>,
    config: JSONObject,
): Boolean {
    return options.all { option ->
        when (option.type) {
            "boolean" -> true
            "multiselect" -> {
                val count = config.optJSONArray(option.key)?.length() ?: 0
                if (option.required && count == 0) false else count >= (option.minItems ?: if (option.required) 1 else 0)
            }
            "artist_search", "track_search" -> {
                val count = config.optJSONArray(option.key)?.length() ?: 0
                val minRequired = option.minItems ?: if (option.required) 1 else 0
                count >= minRequired
            }
            else -> {
                if (!option.required) true else config.optString(option.key, "").trim().isNotBlank()
            }
        }
    }
}

internal fun addLegacyStationMirrors(payload: JSONObject, config: JSONObject) {
    val seedArtist = seedArtistFromConfig(config)
    if (seedArtist.isNotBlank()) payload.put("seed_artist", seedArtist)

    val seedTitle = seedTitleFromConfig(config)
    if (seedTitle.isNotBlank()) payload.put("seed_title", seedTitle)

    payload.put("seed_type", deriveSeedType(config))

    if (config.has("discovery")) payload.put("discovery", config.optDouble("discovery", 0.35))
    if (config.has("seed_influence")) payload.put("seed_influence", config.optDouble("seed_influence", 0.75))
    if (config.has("popular_track_pool_size")) payload.put("popular_track_pool_size", config.optInt("popular_track_pool_size", 10))
    if (config.has("artist_blacklist")) payload.put("artist_blacklist", config.optString("artist_blacklist", ""))
}

internal fun deriveSeedType(config: JSONObject): String {
    return if (firstTrackFromConfig(config) != null) "track" else "artist"
}

internal fun seedArtistFromConfig(config: JSONObject): String {
    firstTrackFromConfig(config)?.artist?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    firstArtistFromConfig(config)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    val direct = config.optString("seed_artist", "").trim()
    if (direct.isNotBlank()) return direct
    val seedArtists = config.optString("seed_artists", "").trim()
    if (seedArtists.isBlank()) return ""
    return seedArtists.split(',', '\n').firstOrNull { it.trim().isNotBlank() }?.trim().orEmpty()
}

internal fun seedTitleFromConfig(config: JSONObject): String {
    firstTrackFromConfig(config)?.title?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    return config.optString("seed_title", "").trim()
}

internal fun firstArtistFromConfig(config: JSONObject): String? {
    listOf("seed_artists", "seed_artist", "artists").forEach { key ->
        val arr = config.optJSONArray(key)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                val name = item?.optString("name", item.optString("artist", ""))?.trim().orEmpty()
                if (name.isNotBlank()) return name
                val raw = arr.optString(i).trim()
                if (raw.isNotBlank()) return raw
            }
        }
    }
    return null
}
internal fun firstTrackFromConfig(config: JSONObject): StationTrackSeedUi? {
    listOf("seed_tracks", "tracks").forEach { key ->
        val arr = config.optJSONArray(key)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title", "").trim()
                val artist = item.optString("artist", item.optString("name", "")).trim()
                if (title.isNotBlank() || artist.isNotBlank()) {
                    return StationTrackSeedUi(
                        title = title,
                        artist = artist,
                        album = item.optString("album", ""),
                        videoId = item.optString("video_id", item.optString("videoId", "")),
                        artUrl = item.optString("art_url", item.optString("thumbnail_url", "")),
                        thumbnailUrl = item.optString("thumbnail_url", item.optString("art_url", "")),
                    )
                }
            }
        }
    }
    val title = config.optString("seed_title", "").trim()
    val artist = config.optString("seed_artist", "").trim()
    if (title.isBlank() && artist.isBlank()) return null
    return StationTrackSeedUi(
        title = title,
        artist = artist,
        album = "",
        videoId = "",
        artUrl = "",
        thumbnailUrl = "",
    )
}

internal fun stationSeedSummary(station: StationUi): String {
    val config = station.config
    val artistSelections = parseArtistSeedSelections(config, null)
    if (artistSelections.isNotEmpty()) {
        val names = artistSelections.mapNotNull { nameItem -> nameItem.name.takeIf { it.isNotBlank() } }
        if (names.isNotEmpty()) return names.take(2).joinToString(", ") + if (names.size > 2) " +${names.size - 2}" else ""
    }
    val trackSelections = parseTrackSeedSelections(config, null)
    if (trackSelections.isNotEmpty()) {
        val first = trackSelections.first()
        return listOf(first.title, first.artist).filter { it.isNotBlank() }.joinToString(" — ")
    }
    return when (station.seedType) {
        "artist" -> station.seedArtist.ifBlank { station.seedTitle }
        else -> listOf(station.seedTitle, station.seedArtist).filter { it.isNotBlank() }.joinToString(" — ")
    }
}

internal fun parseArtistSeedSelections(
    config: JSONObject,
    option: StationConfigOptionUi?,
): List<StationArtistSeedUi> {
    val out = mutableListOf<StationArtistSeedUi>()
    val candidates = buildList {
        option?.key?.takeIf { it.isNotBlank() }?.let(::add)
        add("seed_artists")
        add("seed_artist")
    }.distinct()

    candidates.forEach { key ->
        val arr = config.optJSONArray(key)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val itemObj = arr.optJSONObject(i)
                if (itemObj != null) {
                    val name = itemObj.optString("name", itemObj.optString("artist", "")).trim()
                    if (name.isBlank()) continue
                    out.add(
                        StationArtistSeedUi(
                            name = name,
                            browseId = itemObj.optString("browse_id", itemObj.optString("browseId", "")),
                            artUrl = itemObj.optString("art_url", itemObj.optString("thumbnail_url", "")),
                            thumbnailUrl = itemObj.optString("thumbnail_url", itemObj.optString("art_url", "")),
                        )
                    )
                } else {
                    val raw = arr.optString(i).trim()
                    if (raw.isNotBlank()) {
                        out.add(StationArtistSeedUi(raw, "", "", ""))
                    }
                }
            }
        }
    }

    if (out.isEmpty()) {
        val direct = config.optString("seed_artist", "").trim()
        if (direct.isNotBlank()) {
            out.add(
                StationArtistSeedUi(
                    name = direct,
                    browseId = config.optString("seed_artist_id", ""),
                    artUrl = config.optString("seed_artist_art_url", ""),
                    thumbnailUrl = config.optString("seed_artist_thumbnail_url", config.optString("seed_artist_art_url", "")),
                )
            )
        }
        val listString = config.optString("seed_artists", "").trim()
        if (listString.isNotBlank()) {
            listString.split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { out.add(StationArtistSeedUi(it, "", "", "")) }
        }
    }

    return out.distinctBy { artistSeedKey(it) }
}

internal fun parseTrackSeedSelections(
    config: JSONObject,
    option: StationConfigOptionUi?,
): List<StationTrackSeedUi> {
    val out = mutableListOf<StationTrackSeedUi>()
    val candidates = buildList {
        option?.key?.takeIf { it.isNotBlank() }?.let(::add)
        add("seed_tracks")
    }.distinct()

    candidates.forEach { key ->
        val arr = config.optJSONArray(key)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title", "").trim()
                val artist = item.optString("artist", item.optString("name", "")).trim()
                if (title.isBlank() && artist.isBlank()) continue
                out.add(
                    StationTrackSeedUi(
                        title = title,
                        artist = artist,
                        album = item.optString("album", ""),
                        videoId = item.optString("video_id", item.optString("videoId", "")),
                        artUrl = item.optString("art_url", item.optString("thumbnail_url", "")),
                        thumbnailUrl = item.optString("thumbnail_url", item.optString("art_url", "")),
                    )
                )
            }
        }
    }

    if (out.isEmpty()) {
        val title = config.optString("seed_title", "").trim()
        val artist = config.optString("seed_artist", "").trim()
        if (title.isNotBlank() || artist.isNotBlank()) {
            out.add(
                StationTrackSeedUi(
                    title = title,
                    artist = artist,
                    album = config.optString("seed_album", ""),
                    videoId = config.optString("seed_video_id", config.optString("yt_video_id", "")),
                    artUrl = config.optString("seed_art_url", config.optString("seed_thumbnail_url", "")),
                    thumbnailUrl = config.optString("seed_thumbnail_url", config.optString("seed_art_url", "")),
                )
            )
        }
    }

    return out.distinctBy { trackSeedKey(it) }
}

internal fun artistSeedKey(artist: StationArtistSeedUi): String {
    return artist.browseId.ifBlank { artist.name.trim().lowercase() }
}

internal fun artistSeedKey(artist: SearchArtist): String {
    return artist.browseId.ifBlank { artist.name.trim().lowercase() }
}

internal fun trackSeedKey(track: StationTrackSeedUi): String {
    return track.videoId.ifBlank {
        listOf(track.title.trim().lowercase(), track.artist.trim().lowercase()).joinToString("|")
    }
}

internal fun trackSeedKey(song: SearchSong): String {
    return song.videoId.ifBlank {
        listOf(song.title.trim().lowercase(), song.artist.trim().lowercase()).joinToString("|")
    }
}

internal fun StationConfigOptionUi.defaultAsString(): String {
    if (defaultValue == null || defaultValue == JSONObject.NULL) return ""
    return defaultValue.toString()
}

internal fun StationConfigOptionUi.defaultAsBoolean(): Boolean {
    return when (defaultValue) {
        is Boolean -> defaultValue
        is Number -> defaultValue.toInt() != 0
        else -> defaultValue?.toString()?.equals("true", ignoreCase = true) == true
    }
}

internal fun StationConfigOptionUi.defaultAsInt(): Int {
    return when (defaultValue) {
        is Number -> defaultValue.toInt()
        else -> defaultValue?.toString()?.toIntOrNull() ?: 0
    }
}

internal fun StationConfigOptionUi.defaultAsDouble(): Double {
    return when (defaultValue) {
        is Number -> defaultValue.toDouble()
        else -> defaultValue?.toString()?.toDoubleOrNull() ?: 0.0
    }
}

internal fun trimFloatString(value: Float): String {
    val rounded = kotlin.math.round(value * 100f) / 100f
    return rounded.toString().trimEnd('0').trimEnd('.')
}

internal fun formatNumberForDisplay(value: Double, type: String): String {
    return if (type == "integer") value.toInt().toString() else {
        val rounded = kotlin.math.round(value * 100.0) / 100.0
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}

internal fun humanizeCategory(category: String): String {
    return category
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
        .ifBlank { "Options" }
}
