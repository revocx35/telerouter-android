# CLAUDE.md: notes for Claude sessions (same as AGENTS.md)

telerouter for Android: the phone as a telerouter speaker and digital assistant ("Hey Jarvis" on the device).
Read [ARCHITECTURE.md](ARCHITECTURE.md) first, and telerouter's ARCHITECTURE.md §3 for the speaker protocol
(github.com/revocx35/telerouter). [CLAUDE.md](CLAUDE.md) holds the same rules for Claude sessions.

## Commands

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug     # JDK 21, SDK 37, NDK 27.2.12479018, CMake 3.22.1
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true                      # screenshots -> app/build/screenshots/
./gradlew :app:connectedDebugAndroidTest                                          # wake word engine on an emulator (x86_64 lib is built)
TR_IT_LINK=... TR_IT_WAV=... ./gradlew :core:test --tests '*LiveServerTest*'      # against a real telerouter (see README)
```
Releases are signed by hand (key outside the repo, never in CI): bump `versionCode`/`versionName`, build
`:app:assembleRelease` with `keystore.properties`, then `gh release create vX.Y.Z <apk>`.

## Conventions

- Protocol changes go into `core/Protocol.kt` together with telerouter's `voice/client_conn.py` docstring.
  Unknown server messages must be ignored (`Protocol.parse` returns null).
- Conversation logic lives in `Conversation` (no Android types), so it stays JVM-testable with fakes. Android
  glue goes in `Assistant`.
- New screen: a Robolectric screenshot test (light/dark, phone width).
- Update README, ARCHITECTURE.md and this file with every feature. Commits end with the `Co-Authored-By`
  trailer. Run gitleaks before pushing (no LAN addresses, home paths or keys in the repo).

## Never

- Send audio before a trigger, or log tokens or transcripts.
- Trust user-installed CAs, turn off hostname verification, or accept a CA that doesn't match the link.
- Commit keystores or `keystore.properties`. Add analytics or Google SDKs.

## Hard-won facts

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
