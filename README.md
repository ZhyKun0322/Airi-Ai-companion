# Airi AI Companion — speech fix

This version updates the text-to-speech pipeline to use the current Gemini 3.8 Flash TTS API, with Gemini 3.8 Flash-Lite TTS as an automatic fallback. It does not rely on the phone's installed Text-to-Speech engine.

## What changed

- Migrated the speech request from the older Gemini TTS preview model to `gemini-3.8-flash-tts`.
- Added `gemini-3.8-flash-lite-tts` as a fallback if the primary voice request fails before audio is played.
- Updated the request format to use `speech_metadata`, the current voice configuration, and raw PCM streaming output.
- Reduced the playback pre-buffer and AudioTrack allocation to reduce startup delay and improve device compatibility.
- Show chat text immediately while speech is being generated, instead of leaving the chat bubble waiting for audio.
- Added a visible warning if Gemini voice fails or a Gemini API key is missing.

## Requirements

Gemini cloud speech requires an internet connection and a Gemini API key with access to the selected TTS models. If voice still fails, the app will show a warning; Android Studio Logcat will also contain a `AiriGeminiVoice` entry with the API error.

## Build

Open this folder in Android Studio and allow Gradle to sync. Then run the `app` configuration on an Android device. The source was updated here, but a Debug APK could not be built in this environment because the Gradle distribution could not be downloaded.
