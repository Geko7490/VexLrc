# VEX LRC Generator

Native Android application for creating synchronized LRC and Enhanced LRC lyrics from locally processed audio.

## Project goals

- Fully on-device audio processing; no mandatory cloud API.
- Standard line-synchronized LRC and word-synchronized Enhanced LRC.
- Editable lyric timeline with audio playback and waveform.
- Downloadable, verifiable local AI models.
- Offline-first project storage and resumable processing.
- Real Android APK built with GitHub Actions.

## Technology

- Kotlin, Jetpack Compose, Material 3
- MVVM + Clean Architecture
- Room, DataStore, WorkManager
- AndroidX Media3
- Native AI adapters planned for whisper.cpp and a compatible forced-alignment engine

## Current implementation status

This repository is being initialized. AI inference, model packaging, alignment and vocal separation must be integrated and validated incrementally; they are not considered implemented merely because their interfaces exist.

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35 installed, then run:

    ./gradlew assembleDebug

The debug APK is generated at app/build/outputs/apk/debug/app-debug.apk.

## Privacy

Audio files and generated lyrics are processed locally. The app does not upload user audio to a server.

## License

To be selected before public distribution. Third-party model weights and native dependencies retain their own licenses.
