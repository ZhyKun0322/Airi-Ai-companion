# Airi AI Companion — speech fix

This version updates the text-to-speech pipeline to use the current Gemini 3.8 Flash TTS API, with Gemini 3.8 Flash-Lite TTS as an automatic fallback. It does not rely on the phone's installed Text-to-Speech engine.

## What changed

- Migrated the speech request from the older Gemini TTS preview model to `gemini-3.8-flash-tts`.
- Added `gemini-3.8-flash-lite-tts` as a fallback if the primary voice request fails before audio is played.
- Updated the request format to use `speech_metadata`, the current voice configuration, and raw PCM streaming output.
- Reduced the playback pre-buffer and AudioTrack allocation to reduce startup delay and improve device compatibility.
- Show chat text immediately while speech is being generated, instead of leaving the chat bubble waiting for audio.
- Replaced the generic voice warning with specific network, timeout, API-key, permission, model, billing, quota, and service errors.
- Only retries the Lite TTS model when the requested model is not found; it no longer wastes another request on API-key or quota failures.
- Keeps the chat reply visible even if speech fails.

## Requirements

Gemini cloud speech requires an internet connection and a Gemini API key with access to the selected TTS models. If voice still fails, the app will show a warning; Android Studio Logcat will also contain a `AiriGeminiVoice` entry with the API error.

## Troubleshooting voice errors

The app now displays an actionable reason instead of the generic "Gemini voice unavailable" message:

- **HTTP 401:** the key was rejected; verify or replace it in Settings.
- **HTTP 403:** Gemini/API/model access was denied; check the key restrictions, project eligibility, and billing in Google AI Studio.
- **HTTP 404:** the configured TTS model or endpoint was not found.
- **HTTP 429:** rate limit or quota was reached; wait for the limit to reset.
- **HTTP 402:** billing or prepaid credit needs attention.
- **Network/timeout:** check Wi-Fi or mobile data and retry.

The app cannot inspect your Google account, current quota, or API key from this project alone. Never share the API key in screenshots or chat. Google has updated its API-key security policy, so an older unrestricted standard key may need to be replaced or restricted for the Gemini API. Check Google's current API-key guidance if access is denied.

## Build

Open this folder in Android Studio and allow Gradle to sync. Then run the `app` configuration on an Android device. The source was updated here, but a Debug APK could not be built in this environment because the Gradle distribution could not be downloaded.
