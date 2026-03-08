# StreamVibe Mobile — Alerts & TTS

A TikTok live streaming companion app for the StreamVibe / drvn-empire project.

## Features

| Feature | Details |
|---|---|
| **TikTok Auth** | Login Kit OAuth 2.0 — no passwords stored, uses EncryptedSharedPreferences |
| **Live Events** | WebSocket connection to TikTok Live — gifts, follows, chat, likes, shares, viewer count |
| **TTS Engine** | Android built-in + ElevenLabs AI voices |
| **Claude Filter** | Pre-speak sanitizer: removes spam, normalizes emojis, skips gibberish |
| **TTS Queue** | Skip, pause, clear, max queue size, priority levels (Legendary > Epic > Rare > Common) |
| **Per-User Voices** | Assign specific ElevenLabs voice to any username |
| **Word Blocklist** | Block any word/phrase from being spoken |
| **Goal Bars** | Likes, Follows, Diamonds, Shares, Viewers, Comments — configurable targets |
| **Overlay: Floating** | Widgets over any app via `SYSTEM_ALERT_WINDOW` — drag to reposition |
| **Overlay: Camera** | Widgets composited over CameraX preview in-app |
| **Overlay: Game** | MediaProjection API captures game screen + composites widgets |
| **Background Mode** | Foreground service keeps TTS/alerts running while TikTok is frontmost |
| **Notification Controls** | Skip TTS / Clear / Disconnect from notification |

---

## Setup

### 1. TikTok Developer Account

1. Go to https://developers.tiktok.com
2. Create an app → Products → **Login Kit** + **Live** (request access)
3. Add `streamvibe://tiktok/callback` as a redirect URI
4. Copy your **Client Key** and **Client Secret**

### 2. ElevenLabs (optional, for AI voices)

1. Sign up at https://elevenlabs.io
2. Get your API key from https://elevenlabs.io/profile
3. Voices are fetched automatically when the app launches

### 3. Anthropic API (for Claude TTS filter)

1. Sign up at https://console.anthropic.com
2. Create an API key
3. Uses `claude-haiku-4-5-20251001` (fast + cheap — only used for text sanitization)

### 4. Configure build keys

In `app/build.gradle.kts`, replace the placeholders:

```kotlin
buildConfigField("String", "TIKTOK_CLIENT_KEY",    "\"your_key_here\"")
buildConfigField("String", "TIKTOK_CLIENT_SECRET",  "\"your_secret_here\"")
buildConfigField("String", "ELEVENLABS_API_KEY",    "\"your_key_here\"")
buildConfigField("String", "CLAUDE_API_KEY",        "\"your_key_here\"")
```

Or better — use local.properties / environment variables via:

```kotlin
// In local.properties:
// tiktok.client.key=your_key
// tiktok.client.secret=your_secret
// elevenlabs.api.key=your_key
// anthropic.api.key=your_key

val localProps = java.util.Properties()
localProps.load(rootProject.file("local.properties").inputStream())
buildConfigField("String", "TIKTOK_CLIENT_KEY", "\"${localProps["tiktok.client.key"]}\"")
```

---

## How It Works

### TikTok Auth Flow

```
User taps "Connect TikTok"
    → Opens TikTok OAuth in Chrome Custom Tab
    → TikTok redirects to streamvibe://tiktok/callback?code=XXX
    → MainActivity receives deep link
    → Broadcasts auth code to ViewModel
    → ViewModel calls /v2/oauth/token/ to exchange code for access token
    → Token stored in EncryptedSharedPreferences
```

### Live Events Flow

```
ViewModel.connectToLive(roomId)
    → TikTokLiveRepository opens WebSocket to TikTok Webcast endpoint
    → Events arrive as protobuf binary (or JSON fallback)
    → Parsed into LiveEvent sealed class
    → Emitted on SharedFlow
    → MainViewModel collects → updates session/goals/chat/alerts
    → TtsEventMapper maps event → TtsQueueItem
    → TtsEngine processes queue
```

### TTS Pipeline

```
LiveEvent (Gift/Follow/Chat)
    → TtsEventMapper.map() — check enabled, min diamonds, etc.
    → TtsEngine.enqueue()
        1. Blocklist check — skip if blocked word found
        2. Dedupe — skip if same text spoken within dedupeWindowMs
        3. Claude filter — sanitize via claude-haiku API call
        4. Add to priority queue (max maxQueueSize)
    → Queue processor picks next item
        → If ElevenLabs voiceId → call ElevenLabs API → play MP3
        → If Android TTS → speak via TextToSpeech engine
        → gap between items
```

### Overlay System

#### Floating Window (over TikTok)
- Requires `Settings.canDrawOverlays()` permission
- `OverlayService` creates `ComposeView` instances added via `WindowManager`
- Each widget is independently draggable
- Alert banner, goal bar, chat overlay, viewer count, coin counter, gifters

#### In-App Camera Overlay
- CameraX `PreviewView` in a `Box` with Compose overlays on top
- No special permission needed beyond `CAMERA`

#### Game Stream Capture
- `MediaProjection` API creates `VirtualDisplay`
- Record screen → encode with `MediaCodec` → RTMP push to TikTok stream key
- Widgets composited before encode

---

## Project Structure

```
app/src/main/java/com/streamvibe/mobile/
├── MainActivity.kt              — Entry point, nav, TikTok OAuth deep link
├── StreamVibeApp.kt             — Hilt application + DI module
├── data/
│   ├── tiktok/
│   │   └── TikTokRepository.kt  — Auth + Live WebSocket
│   └── tts/
│       └── TtsEngine.kt         — TTS engine, Claude filter, ElevenLabs, queue
├── domain/
│   └── model/
│       └── Models.kt            — All data models
├── service/
│   ├── StreamService.kt         — Background foreground service
│   └── OverlayService.kt        — Floating window overlay service
└── ui/
    ├── MainViewModel.kt         — Central state management
    └── screens/
        └── Screens.kt           — All 7 screens (Login, Dashboard, Chat, Alerts, Goals, TTS, Overlay, Settings)
```

---

## Permissions Required

| Permission | Why |
|---|---|
| `INTERNET` | TikTok WebSocket + TTS APIs |
| `SYSTEM_ALERT_WINDOW` | Floating overlay widgets |
| `CAMERA` | In-app camera stream with overlay |
| `RECORD_AUDIO` | Screen/game capture with audio |
| `FOREGROUND_SERVICE` | Background stream service |
| `WAKE_LOCK` | Keep stream alive during long sessions |
| `POST_NOTIFICATIONS` | Stream status notification |

---

## TikTok API Scopes Requested

- `user.info.basic` — display name, avatar
- `live.room.info` — room ID lookup
- `live.room.message` — WebSocket events (gifts, follows, chat)

Note: `live.room.message` requires TikTok approval. Apply via developer portal.

---

## TTS Filter — Claude vs TikFinity

| Feature | TikFinity | StreamVibe |
|---|---|---|
| Spam filter | Basic length check | Claude AI — detects repeated chars, gibberish |
| Emoji handling | Reads emoji codes verbatim | Converts to natural words |
| URL handling | Reads full URL | Removes URLs completely |
| Username normalization | Reads literally | Normalizes numbers (xX99Xx → "ninety nine") |
| Blocklist | Word list | Word list + Claude catches variations |
| Queue | FIFO | Priority queue (gift tier > follow > chat) |
| Skip/Clear | App only | App + notification controls |
| Per-user voices | ❌ | ✅ ElevenLabs voice per username |
| AI voices | ❌ | ✅ ElevenLabs |
| Dedupe | Basic | Time-window based (configurable ms) |

---

## Building

```bash
./gradlew assembleDebug
```

For signed release (CI/CD):
```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=$KEYSTORE_PATH \
  -Pandroid.injected.signing.store.password=$KEYSTORE_PASSWORD \
  -Pandroid.injected.signing.key.alias=$KEY_ALIAS \
  -Pandroid.injected.signing.key.password=$KEY_PASSWORD
```
