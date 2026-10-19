# Architecture

## Components

| Module | What |
|---|---|
| `core/` (plain Kotlin/JVM) | `SetupLink` (the `telerouter://setup` link), `Protocol` (speaker protocol messages), `Trust` (pin telerouter's CA from the link's fingerprint; pinned and public OkHttp clients), `Enroll.setup` (code → `trc_` token, internet address first), `Connection` (the WebSocket: internet first, then LAN) |
| `microwakeword/` (C++/JNI) | microWakeWord inference: TFLite Micro + its audio frontend, from Home Assistant's companion app (see NOTICE) |
| `app/.../assist/` | `AssistService` (VoiceInteractionService: default assistant, wake word foreground service), `AssistSession`, `RecognitionStub`, `AssistTile`, `AssistActivity` (the sheet), `Assistant` (app-wide: starts conversations, audio focus), `Conversation` (one conversation's logic, JVM-tested) |
| `app/.../audio/` | `Mic` (one shared AudioRecord, 10 ms chunks), `Player` (AudioTrack for telerouter's audio), `Chime` |
| `app/.../wake/` | `WakeWord` (the model on the shared mic, 2 s cool-down) |
| `app/.../data/` | `Store` (server config in SharedPreferences, token sealed with an AES-GCM Keystore key) |
| `app/.../ui/` | `MainActivity` (setup, assistant role, wake word, status), `AssistSheet`, theme |

## Data flow

```
"Hey Jarvis" ─▶ Mic ─▶ WakeWord (on the phone) ──fires──▶ Assistant.begin()
                  │                                          │ chime, Conversation.start()
                  └──────────────▶ Conversation (buffers audio while connecting)
                                       │ Connection.connect(): wss://public/ws/client (public cert), else wss://LAN (pinned CA)
                                       │ hello{transcript:true} · talk{cue:false} · buffered audio · live audio
                                       ▼
                                  telerouter: Whisper → Jarvis → Piper
                                       │ state · transcript(user/assistant) · PCM audio
                                       ▼
                         AssistActivity sheet (text) · Player (voice)
```

- The mic is sent only while telerouter listens, or during a call. When telerouter is idle after an answer,
  the app hangs up after 8 s; a tap on the mic within that time asks again on the same connection.
- The assistant gesture/button goes through the system: `AssistService` → `AssistSession.onShow` →
  `Assistant.begin` + `AssistActivity`. `ACTION_ASSIST` / `VOICE_COMMAND` and the tile open `AssistActivity`
  directly.
- The wake word runs only while the app is the digital assistant and the switch is on: the
  VoiceInteractionService starts a `microphone` foreground service (allowed for the active assistant).
  Like ESPHome, the engine ignores the first 3 s after it starts or resets (100 slices), so it is deaf for
  about 3 s after each conversation.

## Protocol

telerouter's speaker protocol (see telerouter's ARCHITECTURE.md §3.1 and §3.4), plus two extensions the phone uses:
`hello.transcript = true` (the server sends what it heard and what it answers) and `talk` with `cue: false`
(the phone played its own start sound, and the server listens at once without losing the buffered audio).

## Security

- Setup link (telerouter ≥ 0.9.1): `public` (the internet address), `server` (the page's address), `lan`
  (telerouter's own address), the SHA-256 of telerouter's CA, and the one-time code. `Enroll.setup` enrolls
  through `public` (or `server`), trusting the system CAs like a browser, else through `lan` with the pinned CA.
- LAN backup: the app connects to `lan` without trust, takes the certificate in the presented chain whose
  hash matches the link, and from then on trusts only that CA there (hostname checks stay on). If the LAN
  address isn't reachable during setup, the pinning happens on the first connection that falls back to it.
  A mismatch is never pinned.
- Internet address: system CAs only (`network_security_config`: no user CAs, no cleartext).
- The device token (`trc_…`) is sealed with an AES-256-GCM key in the Android Keystore. Backups and device
  transfer exclude the app's data. Revoke a lost phone by removing its device in telerouter.
- Audio: nothing leaves the phone until the wake word, a button or a tap starts a conversation. During one,
  only the listening parts (and calls) are sent.
- No third-party SDKs and no analytics. Logs never contain tokens or transcripts.
