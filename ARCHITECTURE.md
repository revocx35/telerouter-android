# Architecture

## Components

| Module | What |
|---|---|
| `core/` (plain Kotlin/JVM) | `ServerUrl` (the sign-in address), `Protocol` (speaker protocol messages), `Trust` (OkHttp clients: system CAs, optional cookie jar and redirect following), `CookieStore`/`StoreCookieJar` (cookies shared with the WebView), `Api` (session check, adding the device, sign-out, renewing the proxy's sign-in), `Connection` (the WebSocket) |
| `microwakeword/` (C++/JNI) | microWakeWord inference: TFLite Micro + its audio frontend, from Home Assistant's companion app (see NOTICE) |
| `app/.../assist/` | `AssistService` (VoiceInteractionService: default assistant, wake word foreground service, screen wake), `AssistSession`, `RecognitionStub`, `AssistTile`, `AssistActivity` (the sheet, over the lock screen), `Assistant` (app-wide: starts conversations, audio focus), `Conversation` (one conversation's logic, JVM-tested) |
| `app/.../audio/` | `Mic` (one shared AudioRecord, 10 ms chunks), `Player` (AudioTrack for telerouter's audio, media usage, 20 ms start threshold), `Chime` |
| `app/.../wake/` | `WakeWord` (the model on the shared mic, 2 s cool-down) |
| `app/.../data/` | `Store` (address, device token sealed with an AES-GCM Keystore key, settings), `WebCookies` (CookieManager), `Net`, `DeviceLink` (adds the phone once signed in) |
| `app/.../ui/` | `MainActivity` (address → dashboard, "This phone" over it), `Dashboard` (the WebView and its navigation rules), `Screens` (ConnectScreen, PhoneScreen, DashboardError), `AssistSheet`, theme |

## Sign-in and the dashboard

```
address ─▶ WebView https://<address>/ ─▶ proxy login (e.g. Authentik) ─▶ telerouter login / "Sign in with Authentik"
                                                                      │ the web UI (dashboard)
DeviceLink (every 3 s while not added, and on page loads): GET /api/session with the WebView's cookies
   └─ logged in ─▶ POST /api/clients/enroll (CSRF) ─▶ POST /api/client/enroll ─▶ trc_ token (Keystore) ─▶ "This phone"
```

- The dashboard is telerouter's own web UI. It recognises the app by the user agent `telerouter-android/<version>`
  and adds `telerouter-app://talk` and `telerouter-app://assistant` links (honoured only on telerouter's origin).
- WebView rules: https only, certificate errors are never skipped, no JavaScript bridge, no file access. Pages of
  telerouter's origin stay in the app; so do redirects and non-tap navigations (sign-in flows) and anything while a
  sign-in page is open. A tap from telerouter's page to another site opens the browser.
- Folding/rotating doesn't recreate the activity (`configChanges`), so the page and its state stay.

## Voice data flow

```
"Hey Jarvis" ─▶ Mic ─▶ WakeWord (on the phone) ──fires──▶ Assistant.begin()  (+ screen on: wake lock, sheet over the lock screen)
                  │                                          │ chime, Conversation.start()
                  └──────────────▶ Conversation (buffers audio while connecting)
                                       │ Connection.connect(): wss://<address>/ws/client, X-Telerouter-Token + the WebView's cookies
                                       │   proxy redirect/401 ─▶ Api.renewSignIn() (follow /api/health's redirects) ─▶ retry once
                                       │ hello{transcript:true, speech} · talk{cue:false} · buffered audio · live audio
                                       ▼
                                  telerouter: Whisper → Jarvis → Piper (skipped when speech:false)
                                       │ state · transcript(user/assistant) · PCM audio · config{client_id}
                                       ▼
                         AssistActivity sheet (text) · Player (voice)
```

- The mic is sent only while telerouter listens, or during a call. When telerouter is idle after an answer,
  the app hangs up after 8 s; a tap on the mic within that time asks again on the same connection.
- The assistant gesture/button goes through the system: `AssistService` → `AssistSession.onShow` →
  `Assistant.begin` + `AssistActivity`. `ACTION_ASSIST` / `VOICE_COMMAND`, the tile and the dashboard's *Talk*
  open `AssistActivity` directly. A new conversation is ENDED for a moment before it starts: the sheet closes only
  on an end after it was active.
- The wake word runs only while the app is the digital assistant, the phone is added and the switch is on: the
  VoiceInteractionService starts a `microphone` foreground service (allowed for the active assistant).
  The model keeps running during a conversation. Its detections count while the sheet waits after an answer or
  an error (ask again on the same connection), and while Jarvis speaks (interrupt: `cancel`, then `talk` once the
  server is idle). They are dropped while it connects, listens, thinks or is in a call (in a call, telerouter's
  wake word hangs up). Like ESPHome, the engine ignores the first 3 s after it starts or after a detection
  (100 slices).
- Errors that need the app (the proxy wants a login; telerouter refused the token, which also drops it so the
  phone is added again at the next sign-in) show *Open telerouter* in the sheet.

## Protocol

telerouter's speaker protocol (see telerouter's ARCHITECTURE.md §3.1 and §3.4), plus extensions the phone uses:
`hello.transcript = true` (the server sends what it heard and what it answers), `hello.speech = false` (text only),
`talk` with `cue: false` (the phone played its own start sound, and the server listens at once without losing the
buffered audio), the token in `X-Telerouter-Token`, and `config.client_id` (which device this is). The server's
`flush` at the start of a phone's turn is ignored until telerouter has sent audio, so it doesn't cut the chime.

## Security

- One address with a publicly trusted certificate: system CAs only (`network_security_config`: no user CAs, no
  cleartext; debug builds alone also trust user CAs, for emulator tests). Hostname checks stay on.
- The sign-in happens on the proxy's and telerouter's own pages in the WebView; the app never sees passwords.
  It only reads the resulting cookies (CookieManager) to check the session, add the device and open the voice
  connection, and it follows the proxy's redirects only https→https.
- The device token (`trc_…`) is created with the admin session, sealed with an AES-256-GCM key in the Android
  Keystore, and sent in `X-Telerouter-Token`. Backups and device transfer exclude the app's data. *Sign out*
  deletes the device in telerouter (token revoked), logs the session out and clears the WebView's cookies and storage.
- WebView: no JavaScript bridge, no file/content access, no mixed content, certificate errors cancelled, other
  sites opened by a tap go to the browser, `telerouter-app://` links only from telerouter's origin.
- "Turn the screen on" shows the sheet over the lock screen without unlocking; the dashboard needs the phone
  unlocked.
- Audio: nothing leaves the phone until the wake word, a button or a tap starts a conversation. During one,
  only the listening parts (and calls) are sent.
- No third-party SDKs and no analytics. Logs never contain tokens or transcripts.
