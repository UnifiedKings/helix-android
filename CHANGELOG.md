# Changelog

## 0.2.0

A large update: new features, many playback fixes, and a rebuilt app structure with tests.

**Installing:** this release is signed with a new key. If you have an earlier Helix build installed (an
upstream release or a self-built debug build), uninstall it first, then install `helix-0.2.0.apk`.
You'll need to sign in again.

### New

- **Android Auto**: browse your Queue, Stations, Playlists and Recent history in the car and start
  anything from there; search from Auto's search button.
- **Voice search**: "Hey Google, play … on Helix" plays songs, artists (their popular songs),
  albums, playlists ("my Gym playlist") and stations ("Jessie Murph radio"), and works when Helix
  isn't open. Whether your phone's assistant passes requests to Helix depends on the assistant.
- **Play on this device** setting: turn it off to use the phone as a remote for your other
  devices. The lock screen and notification still show and control what's playing elsewhere.
- **Keep playing after closing the app** (on by default), with controls in the notification and
  on the lock screen.
- **Sleep timer**: 5–60 minutes or end of track, with a fade-out.
- **History** tab in the Library: what you've played and skipped, by day, with play/queue actions.
- **Queue controls**: play next, remove, clear (keeps the current song), drag to reorder.
- **Sign-in screen** when the app opens without a saved session, and a clear prompt when the
  session expires.
- Delete playlists from the playlist list; error messages now say what went wrong (including the
  server's reason where it gives one).

### Fixed

- Autoplay not moving to the next song while the screen was locked.
- Lock-screen Next/Previous doing nothing or only restarting the song; lock-screen artwork missing
  for covers that need your login; stale lock-screen song info.
- Opening the app interrupting or doubling up audio playing on another device, and a paused phone
  starting again on its own when another device moved the queue on.
- Starting a station showing "can't reach the Helix server" just before it started playing.
- Drag-to-reorder in playlist edit mode not saving when a song reached the top or bottom.
- Likes, dislikes, station and playlist changes that the server rejected showing as if they had
  worked; several failures that were silently ignored now show a message.
- Playing a recent Subsonic song from Search sending the wrong id.
- Crashes and hangs around swiping the app away, dropped server connections and expired sessions.
- Older search results replacing newer ones when typing quickly.

### Under the hood

- Battery and data: the live connection runs only while the app is visible or playing, reconnects
  back off, the likes list is cached, and Subsonic import checks stop after two minutes.
- Every screen now has a ViewModel over a shared data layer; the two 2,000+ line screens are split
  into smaller files. 152 unit tests and 10 on-device tests.
- Versioned builds (Settings → App shows the version), one dependency catalog, Java 17.

### Known issues (server side)

These need fixes in the Helix server and are with its maintainer:

- The web player doesn't follow play/pause/track changes made from other devices.
- Removing a queue item can report an error even though it worked (the app handles this).
- A station that fails to start clears your queue.
