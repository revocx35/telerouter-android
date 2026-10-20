# telerouter for Android

[telerouter](https://github.com/revocx35/telerouter) on your phone: the **whole dashboard** (every page and
setting of telerouter's web UI, laid out for a phone) and **Jarvis as your assistant** instead of Google
Assistant. Say **"Hey Jarvis"**, press the assistant button or swipe up from a bottom corner, and your own agent
(Claude Code running as "Jarvis" behind telerouter) answers, spoken and as text. Calls you ask for ("call my
dad") run through the phone too.

- **One address, one sign-in**: the app uses the address you open telerouter with in the browser, and you sign in
  there as usual: your reverse proxy's login (e.g. **Authentik** forward auth), then telerouter's login page or
  **Sign in with Authentik** (telerouter's single sign-on). The phone then adds itself as a telerouter speaker; no
  setup code. Voice uses the same address and sign-in; an expired proxy session is renewed in the background.
- **On-device wake word**: microWakeWord's "Hey Jarvis" model runs on the phone, the same engine and approach as
  Home Assistant's companion app, inside the default assistant's service, which Android keeps alive cheaply.
  Nothing is sent anywhere until the wake word fires; the phone connects only for the conversation.
- **Assistant settings** ("This phone"): spoken answers (telerouter's voice at the media volume) or text only;
  turn the screen on for "Hey Jarvis" and show the conversation over the lock screen (it stays locked).
- **No Google, no analytics**: the app talks only to your telerouter (and your sign-in pages). The device token is
  encrypted with the Android Keystore. User-installed CAs are never trusted.

## Set up

1. Install the APK from [Releases](../../releases) (Android 8.0+). Needs telerouter 0.10 or newer at an address
   with a **publicly trusted certificate** (e.g. telerouter behind a reverse proxy with Let's Encrypt). Turn on
   the proxy's WebSocket support for `/ws/client`.
2. Enter that address (e.g. `telerouter.example.com`) and sign in on the pages that follow.
3. The phone adds itself (named as in the phone's settings) and opens **This phone**: *Open default apps* →
   *Digital assistant app* → telerouter, then turn on **"Hey Jarvis"** if you want the wake word. Android shows a
   small "Listening" notification while it is on.

The dashboard's *Talk* button and *This phone* (in its menu) open the phone's own screens. *Sign out* removes the
phone from telerouter's speakers. Upgrading from 0.1.x: enter the address once; the phone stays the same speaker.

## Build and test

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug     # JDK 21, SDK 37, NDK 27.2, CMake 3.22.1
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true                      # screenshots -> app/build/screenshots/
./gradlew :app:connectedDebugAndroidTest                                          # wake word engine on a device/emulator
TR_IT_URL=https://host:8443 TR_IT_USER=admin TR_IT_PASSWORD=... TR_IT_WAV=speech.wav [TR_IT_CA=ca.pem] \
  ./gradlew :core:test --tests '*LiveServerTest*'                                  # against a real telerouter
```

The live test logs in with the password API, adds a device with that session as the app does, then does what the
phone does after "Hey Jarvis": `talk` followed directly by the speech, and expects the transcript, the answer as
text and as audio, and the speaker going back to idle. It removes the device again. It needs a 16 kHz mono WAV of
someone speaking; `TR_IT_CA` trusts telerouter's own CA for a direct (LAN) address.

Debug builds also trust user-installed CAs (`src/debug/res/xml/network_security_config.xml`), for testing on an
emulator against a local telerouter behind a test proxy; release builds never do.

## Licenses

MIT (see LICENSE). The wake word engine in `microwakeword/` and the "Hey Jarvis" model are from the
Home Assistant Companion for Android and microWakeWord, Apache-2.0: see `microwakeword/NOTICE` and
`microwakeword/LICENSE`.
