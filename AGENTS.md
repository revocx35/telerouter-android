# AGENTS.md: notes for AI coding agents

telerouter for Android: telerouter's web UI as the phone's dashboard (WebView) and the phone as a telerouter speaker
and digital assistant ("Hey Jarvis" on the device).
Read [ARCHITECTURE.md](ARCHITECTURE.md) first, and telerouter's ARCHITECTURE.md §3 for the speaker protocol
(github.com/revocx35/telerouter). [CLAUDE.md](CLAUDE.md) holds the same rules for Claude sessions.

## Commands

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug     # JDK 21, SDK 37, NDK 27.2.12479018, CMake 3.22.1
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true                      # screenshots -> app/build/screenshots/
./gradlew :app:connectedDebugAndroidTest                                          # wake word engine on an emulator (x86_64 lib is built)
TR_IT_URL=... TR_IT_USER=... TR_IT_PASSWORD=... TR_IT_WAV=... ./gradlew :core:test --tests '*LiveServerTest*'   # real telerouter (README)
```
Releases are signed by hand (key outside the repo, never in CI): bump `versionCode`/`versionName`, build
`:app:assembleRelease` with `keystore.properties`, then `gh release create vX.Y.Z <apk>`.

## Conventions

- Protocol changes go into `core/Protocol.kt` together with telerouter's `voice/client_conn.py` docstring.
  Unknown server messages must be ignored (`Protocol.parse` returns null).
- Conversation logic lives in `Conversation` (no Android types), so it stays JVM-testable with fakes. Android
  glue goes in `Assistant`.
- New screen: a Robolectric screenshot test (light/dark, phone width).
- Dashboard features belong in telerouter's web UI, not here: the app shows it. Only what a page can't do is
  native (assistant, wake word, sheet, audio/screen settings). Links from the UI into the app: `telerouter-app://`.
- Network calls never on the main thread (`DeviceLink.ensure` runs on IO): StrictMode crashes the app.
- Update README, ARCHITECTURE.md and this file with every feature. Commits end with the `Co-Authored-By`
  trailer. Run gitleaks before pushing (no LAN addresses, home paths or keys in the repo).

## Never

- Send audio before a trigger, or log tokens or transcripts.
- Trust user-installed CAs in release builds, turn off hostname verification, or skip WebView certificate errors.
- Add a JavaScript bridge to the WebView, or honour `telerouter-app://` links from other origins.
- Commit keystores or `keystore.properties`. Add analytics or Google SDKs.

## Hard-won facts

- **"Hey Jarvis" again while the sheet was open did nothing** (0.1.1): the wake word was paused for the whole
  conversation and reset when it ended (reset = 3 s deaf). Since 0.1.2 the model always runs, `WakeWord.paused`
  only drops detections (connecting/listening/thinking/call), and `Conversation.wake()` asks again or interrupts.
  An interruption must wait for the server's `idle` before `talk`: the server drops a `talk` while the old
  turn's task is still being cancelled.

- **Authentik's login was an empty page (0.2.0, only its background colour)**: the WebView had Compose
  AndroidView's default wrap_content layout params, and with those the WebView's viewport height is 0, so `100vh` /
  `100dvh` are 0 px. Authentik sizes its page with `min-height: 100dvh`; telerouter's own UI didn't care, so tests
  passed. `Dashboard` sets MATCH_PARENT (DashboardTest). Debug it with DevTools: `adb forward tcp:9222
  localabstract:webview_devtools_remote_<pid>` (debug builds) and `Runtime.evaluate` over the page's WebSocket.
- **Short answers were never heard** (0.1.x): a streaming AudioTrack starts only once its start threshold is
  buffered, by default the whole buffer (1 s), and telerouter sends speech only ~250 ms ahead. The chime (0.16 s)
  never played and short answers were released unplayed (seen in `dumpsys media.audio_flinger`: frames ready,
  position 0). Since 0.2.0: 20 ms start threshold (API 31+), else a 200 ms buffer and chimes padded past it.
- **The sheet closed at once when opened by a button** (0.1.x): a new Conversation reports ENDED before `start()`.
  It closes only on an end after an active phase now.
- **Sign-in happens inside the SPA**: telerouter's login is a fetch, not a page load, so `DeviceLink` also polls
  every 3 s while the dashboard is open and the phone isn't added.
- **telerouter flushes a phone's playback when its turn starts** (meant for server audio): it cut the phone's chime.
  `Conversation` ignores a flush until telerouter has sent audio in that turn.
- Emulator end-to-end against a local telerouter behind a sign-in proxy: Caddy `forward_auth` + a small fake
  Authentik outpost/IdP, a test CA as a user CA (`adb root`, `/data/misc/user/0/cacerts-added/<subject_hash_old>.0`),
  `adb reverse tcp:9443 tcp:9443`, address `localhost:9443`. `dumpsys media.audio_flinger` shows whether audio played.
- **Setup from a page opened through a reverse proxy** (0.1.x, gone in 0.2): the old setup link pinned
  telerouter's CA; a proxy's Let's Encrypt certificate failed that pin.

- **microWakeWord ignores the first 100 slices (3 s) after it is created or reset** (`MIN_SLICES_BEFORE_DETECTION`,
  as in ESPHome). Test audio needs ≥3 s of lead-in, or nothing fires. The engine itself was fine. The
  reference Python `pymicro-wakeword` (`pip install pymicro-wakeword`) is a quick way to check a recording.
- Piper's `en_US-lessac-medium` saying "Hey Jarvis." is detected by the model (cut-off 0.97, window 5) on device
  and by pymicro-wakeword. A normal sentence ("Call my mom please.") is not.
- HA's CMakeLists has a HWASan block for debug builds that needs their `wrap.sh`. Without it the debug .so fails to
  load, so it is removed here.
- JNI methods are registered by class name in `MicroWakeWord_jni.cpp` (`FindClass`). Renaming the Kotlin
  package means changing that string too.
- The shared emulator (`npm_test`, API 37): its Pixel Launcher shows "isn't responding" under software
  rendering. Dismiss it, it is not this app. `adb reverse tcp:<port> tcp:<port>` lets the emulator reach a
  telerouter on the host's 127.0.0.1 with hostname checks intact. `input keyevent KEYCODE_ASSIST` triggers the
  assistant the way the button does. `cmd voiceinteraction show` is not allowed from the shell.
- An Android 17 VoiceInteractionService can start the microphone foreground service (types=0x80 seen in
  `dumpsys activity services`).
