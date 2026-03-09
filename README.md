# StreamVibe Mobile — `nosdk` Branch

> **Standalone APK** — no TikTok SDK, no StreamVibe backend required.
> Works as a fully self-contained Android app.

---

## What is this branch?

The `main` branch uses the TikTok Open SDK (Maven dependency from ByteDance).
This `nosdk` branch removes that dependency entirely and replaces it with:

- **Direct OAuth2 PKCE auth** via the user's browser + deep link callback
- **Direct WebSocket** connection to `webcast.tiktok.com`
- **Pure OkHttp** — no third-party TikTok library needed

This means the APK builds without needing TikTok developer approval, a Play Store
listing, or any SDK registration. You still need a TikTok developer app client key
to authenticate users.

---

## Features

| Feature | Status |
|---|---|
| TikTok OAuth2 login (no SDK) | ✅ |
| Live WebSocket event stream | ✅ |
| Chat messages | ✅ |
| Gift alerts with tier badges | ✅ |
| Follow / Share / Like events | ✅ |
| Viewer count tracking | ✅ |
| TTS with Android built-in voice | ✅ |
| TTS with ElevenLabs AI voices | ✅ (API key required) |
| Claude AI TTS filter | ✅ (API key required) |
| TTS queue (skip / pause / clear) | ✅ |
| Per-user voice assignment | ✅ |
| Stream goal bars | ✅ |
| Floating overlay widgets | ✅ |
| Background foreground service | ✅ |
| Notification controls (Skip/Clear) | ✅ |
| Session stats dashboard | ✅ |

---

## Requirements

- Android 8.0+ (API 26)
- A TikTok developer account with an app registered at [developers.tiktok.com](https://developers.tiktok.com)
- *(Optional)* ElevenLabs API key for AI voices
- *(Optional)* Anthropic API key for Claude TTS filter

---

## Setup

### 1. Clone and switch to this branch

```bash
git clone https://github.com/augesrob/StreamVibeMobile.git
cd StreamVibeMobile
git checkout nosdk
```

### 2. Add your API keys to `local.properties`

Create `local.properties` in the project root (it's gitignored):

```properties
tiktok.client.key=YOUR_TIKTOK_CLIENT_KEY
tiktok.client.secret=YOUR_TIKTOK_CLIENT_SECRET
elevenlabs.api.key=YOUR_ELEVENLABS_API_KEY
anthropic.api.key=YOUR_ANTHROPIC_API_KEY
```

ElevenLabs and Anthropic keys are optional — the app falls back to Android's built-in
TTS and skips the Claude filter if those keys are blank.

### 3. Register your redirect URI on TikTok Developer Portal

In your TikTok app settings, add this as a redirect URI:

```
streamvibe://tiktok/callback
```

### 4. Build

```bash
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

---

## How auth works (no SDK)

```
User taps "Connect with TikTok"
  ↓
App builds PKCE OAuth2 URL and opens it in the user's browser
  ↓
User logs in on TikTok's website
  ↓
TikTok redirects to: streamvibe://tiktok/callback?code=XXX
  ↓
Android routes the deep link back to MainActivity
  ↓
App exchanges the code for an access token via POST to TikTok API
  ↓
Token stored in EncryptedSharedPreferences
  ↓
WebSocket opens to webcast.tiktok.com — live events start flowing
```

No SDK, no Play Store listing required to test. The OAuth flow works in any browser.

---

## GitHub Actions — Auto APK build

Every push to this branch triggers `.github/workflows/build.yml` which:

1. Builds a **debug APK** (every push)
2. Builds a **signed release APK** (pushes to `nosdk` only — requires secrets)
3. Uploads both as **downloadable artifacts** in the Actions tab

To enable signed release builds, add these secrets in
**GitHub → Settings → Secrets → Actions**:

| Secret | Description |
|---|---|
| `TIKTOK_CLIENT_KEY` | From TikTok Developer Portal |
| `TIKTOK_CLIENT_SECRET` | From TikTok Developer Portal |
| `ELEVENLABS_API_KEY` | From elevenlabs.io (optional) |
| `ANTHROPIC_API_KEY` | From console.anthropic.com (optional) |
| `KEYSTORE_BASE64` | Base64-encoded `streamvibe.keystore` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias (`streamvibe`) |
| `KEY_PASSWORD` | Key password |

---

## Project structure

```
app/src/main/java/com/streamvibe/mobile/
├── MainActivity.kt              # Entry point, deep link handler
├── StreamVibeApp.kt             # Hilt application + OkHttp provider
├── data/
│   ├── tiktok/
│   │   ├── TikTokAuthRepository.kt  # OAuth2 PKCE — no SDK
│   │   └── TikTokLiveRepository.kt  # WebSocket events — no SDK
│   └── tts/
│       └── TtsEngine.kt         # Android TTS + ElevenLabs + Claude filter
├── domain/model/
│   └── Models.kt                # LiveEvent, TikTokUser, Goals, etc.
├── service/
│   ├── StreamService.kt         # Foreground service (background TTS)
│   └── OverlayService.kt        # Floating overlay widgets
└── ui/
    ├── MainViewModel.kt         # All state management
    └── screens/
        └── Screens.kt           # All 7 Compose UI screens
```

---

## Differences from `main` branch

| | `main` | `nosdk` |
|---|---|---|
| TikTok Open SDK | ✅ Required | ❌ Removed |
| ByteDance Maven repo | ✅ Required | ❌ Removed |
| Play Store listing needed | Yes (for SDK) | No |
| Auth method | SDK `TikTokOpenApiActivity` | Browser + deep link |
| WebSocket | SDK wrapper | Direct OkHttp |
| APK size | Larger (SDK bundled) | Smaller |

---

## License

MIT — see [LICENSE](LICENSE) if present.
