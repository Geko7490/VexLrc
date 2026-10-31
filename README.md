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

## Local AI status

- **Whisper.cpp native runtime:** Android NDK/CMake integration, offline inference bridge, PCM decoding and model-file loading.
- **Whisper Model Center:** downloads multilingual Tiny, Base and Small GGML models into app-private storage; supports progress, selection and deletion.
- **Transcription:** Auto / Turkish / English language selection; results are inserted as editable lyric segments.
- **Token timestamps:** Whisper token-level timestamps are enabled experimentally and can populate word timing where the tokenizer exposes usable boundaries. Review results before exporting.
- **Dedicated forced alignment:** ONNX Runtime CTC/Viterbi alignment for Turkish and English. Downloads the matching Parseh ONNX model and vocabulary; aligns the current transcript to the selected audio. Results should be reviewed, especially with noisy music or inaccurate lyrics.
- **Demucs vocal separation:** ONNX Runtime HTDemucs FT vocals model (166 MB), chunked overlap-add, WAV output and optional use as Whisper transcription input. Current audio decoder downmixes source audio to mono before duplicating it to stereo for inference; output is intended for transcription rather than music playback.
- **Audio safety:** local PCM decoder limits source audio to 10 minutes. Demucs is memory-intensive and is limited to five minutes per run on mobile; test shorter tracks first.
- **ONNX Runtime:** used for forced alignment and Demucs inference. Models are downloaded separately and stored in app-private storage.

The native Whisper build fetches the pinned upstream `ggml-org/whisper.cpp` v1.9.5 source during CMake configuration. Android builds require NDK 27.0.12077973 and CMake 3.22.1. The first build therefore needs network access; inference itself runs locally after a model has been downloaded.

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

Audio is decoded and processed on-device. Whisper model downloads require internet access, but selected audio and transcription requests are not uploaded to a remote inference service. Model files are stored in app-private storage.

## License

A project license has not yet been selected. Third-party libraries, native engines and model weights will retain their own licenses.
