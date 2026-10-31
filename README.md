# VEX LRC Generator

A native Android app for creating and editing synchronized LRC and Enhanced LRC lyrics from audio.

## Implemented foundation

- Native Kotlin + Jetpack Compose Android application.
- VEX dark/purple interface with Home, Studio, Model Center and Settings sections.
- Audio file selection through Android Storage Access Framework.
- Local audio playback in the Studio.
- Editable timestamped lyric text and LRC file export.
- Kotlin formatter for standard LRC and word-timestamped Enhanced LRC.
- Unit tests for LRC formatting and timestamp handling.
- GitHub Actions workflow for debug APK builds.

## Planned / not yet implemented

- whisper.cpp native transcription and downloadable model manager.
- Forced alignment for accurate word-level timestamps.
- Demucs vocal separation.
- Room-backed project persistence and resumable AI jobs.
- Waveform rendering and visual drag-to-adjust timing.
- Device compatibility, memory and thermal management for local models.

These features are explicitly marked as pending until implemented and tested on real Android devices.

## Technology

- Kotlin, Jetpack Compose, Material 3
- Android Gradle Plugin 8.7.3, Kotlin 2.0.21
- JDK 17, compile/target SDK 35, min SDK 26
- AndroidX Media3
- JUnit 4

## Build locally

Open the repository in Android Studio with JDK 17 and Android SDK 35 installed, or install Gradle 8.9 and run:

    gradle assembleDebug

The debug APK is generated at:

    app/build/outputs/apk/debug/app-debug.apk

GitHub Actions builds the debug APK on pushes and pull requests to main/master and uploads it as an artifact.

## Privacy

The initial app does not upload audio. Future AI inference is designed to run locally. Model downloads, when added, will require network access only to retrieve model files.

## License

A project license has not yet been selected. Third-party libraries, native engines and model weights will retain their own licenses.
