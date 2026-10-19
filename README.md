# telerouter for Android

Your phone as a [telerouter](https://github.com/revocx35/telerouter) speaker, and a replacement for Google
Assistant. Say **"Hey Jarvis"**, press the assistant button or swipe up from a bottom corner, and your own
agent (Claude Code running as "Jarvis" behind telerouter) answers, with the conversation shown as text.
Calls you ask for ("call my dad") run through the phone too.

- **On-device wake word**: microWakeWord's "Hey Jarvis" model runs on the phone, the same engine and
  approach as Home Assistant's companion app. It runs inside the default assistant's service, which
  Android keeps alive cheaply. Nothing is sent anywhere until the wake word fires.
- **Connects only for the conversation**, through telerouter's internet address (a reverse proxy with a public
  certificate), at home too. The LAN address with telerouter's own CA, pinned, is the backup. The words said right after "Hey Jarvis"
  are recorded while it connects and sent right away, so you don't have to wait for a beep.
- **No Google, no analytics**: the app talks only to your telerouter. The device token is encrypted with the
  Android Keystore. User-installed CAs are never trusted.

## Set up

1. Install the APK from [Releases](../../releases) (Android 8.0+).
2. In telerouter: **Speakers → Add device**, name it (e.g. "Pixel"), then on the phone tap **Open in the
   telerouter app**, or copy the link into the app (**Paste** → **Set up**). This works from anywhere when
   telerouter has an internet address (`TELEROUTER_PUBLIC_URL`).
3. **Replace Google Assistant**: *Open default apps* → *Digital assistant app* → telerouter.
4. Turn on **"Hey Jarvis"** if you want the wake word. Android shows a small "Listening" notification while
   it is on.

The app uses the server's `TELEROUTER_PUBLIC_URL` (see telerouter's README) first, wherever you are. Without
one, it only works on the home network or through your VPN.

## Build and test

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug     # JDK 21, SDK 37, NDK 27.2, CMake 3.22.1
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true                      # screenshots -> app/build/screenshots/
./gradlew :app:connectedDebugAndroidTest                                          # wake word engine on a device/emulator
TR_IT_LINK='telerouter://setup?...' TR_IT_WAV=speech.wav ./gradlew :core:test --tests '*LiveServerTest*'   # against a real telerouter
```

The live test enrolls with a fresh setup link (Speakers → Add device). It then does what the phone does after
"Hey Jarvis": `talk` followed directly by the speech, and expects the transcript, the answer as text and as
audio, and the speaker going back to idle. It needs a 16 kHz mono WAV of someone speaking.

## Licenses

MIT (see LICENSE). The wake word engine in `microwakeword/` and the "Hey Jarvis" model are from the
Home Assistant Companion for Android and microWakeWord, Apache-2.0: see `microwakeword/NOTICE` and
`microwakeword/LICENSE`.
