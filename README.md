# Helix for Android

Native Android client for [Helix](https://github.com/UnifiedKings/helix), the self-hosted music discovery, playback, and fulfillment platform.

Helix for Android is built with Kotlin and Jetpack Compose and connects to an existing Helix server for music discovery, playback, queue management, stations, playlists, and Subsonic integration.

> **Status:** Work in progress. The Android app is under active development and features may change.

## Features

- Native Android playback with Media3 / ExoPlayer
- Android media session, notification, and lock-screen controls
- Playback synchronized with the Helix server
- Swipe-up queue drawer from Now Playing
- Queue management and reordering
- Song, album, and artist search
- Album and artist browsing
- Helix station browsing, playback, creation, and editing
- Playlist browsing and playback
- Liked and disliked songs
- Subsonic availability detection on Now Playing
- Add missing tracks to Subsonic directly from the app
- Native Android appearance customization
  - Accent color
  - Background color
  - Surface color
  - Presets
  - Visual color picker
- Configurable playback behavior
- Support for playback started or changed by other Helix clients

## Screenshots

Screenshots coming soon.

<!--
Example:

<p align="center">
  <img src="docs/screenshots/player.png" width="250" alt="Now Playing">
  <img src="docs/screenshots/search.png" width="250" alt="Search">
  <img src="docs/screenshots/library.png" width="250" alt="Library">
</p>
-->

## Requirements

Helix for Android is a client and requires a running Helix server.

You will need:

- Android Studio
- An Android device or emulator
- A running Helix instance
- Network access from the Android device to the Helix server

Helix itself handles the shared queue, stations, playlists, music discovery, Subsonic integration, and track fulfillment.

## Building

Clone the repository:

```bash
git clone https://github.com/UnifiedKings/helix-android.git
cd helix-android
```

Open the project in Android Studio.

Allow Gradle to sync, select an Android device or emulator, and press **Run**.

## Connecting to Helix

After launching the app:

1. Open **Settings**
2. Open **Account**
3. Enter your Helix server URL
4. Enter your Helix username and password
5. Connect

Example remote URL:

```text
https://helix.example.com
```

Example local-network URL:

```text
http://192.168.1.100:8000
```

Your Android device must be able to reach the address you enter.

HTTPS is strongly recommended when accessing Helix outside a trusted local network.

## Playback Architecture

Helix remains the source of truth for playback and queue state.

The Android app uses Media3 / ExoPlayer for local playback and Android media-session integration. Transport actions such as Previous and Next are captured by the app and sent to Helix, which decides what should actually play.

This keeps the Android client synchronized with playback changes made from other Helix clients, including the web frontend.

Natural track completion is also reported back to Helix so queue and station behavior remain server-controlled.

## Queue

The queue is intentionally hidden on the main Now Playing screen.

Swipe upward on the player to open the queue as a bottom drawer. From there, queue items can be viewed and managed without permanently taking space away from the player.

## Subsonic Integration

The Android app uses Helix's existing Subsonic integration rather than managing the music library directly.

From Now Playing, the app can:

- Detect whether the current track already exists in Subsonic
- Display **In Subsonic** when it is available
- Request that Helix add a missing track to Subsonic
- Keep the Add action disabled while the import is pending
- Update automatically once Helix detects the track in Subsonic

## Appearance

Helix for Android has its own native appearance settings independent of the Helix web frontend.

Currently configurable:

- Accent color
- Background color
- Surface color

Users can choose from presets or use the visual color picker for custom colors.

These settings are stored locally on the Android device and do not modify the appearance of the Helix web interface.

## Technology

- Kotlin
- Jetpack Compose
- Android Media3 / ExoPlayer
- Retrofit
- OkHttp
- Coil

## Related Projects

- [Helix](https://github.com/UnifiedKings/helix) — Main Helix server and web application
- [HelixBot](https://github.com/UnifiedKings/helixbot) — Discord playback client for Helix

## Contributing

Helix for Android is still under active development.

Bug reports, testing, and contributions are welcome through GitHub issues and pull requests.

## License

Helix for Android is licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**.

See [LICENSE](LICENSE) for the full license text.
