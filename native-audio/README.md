# Native audio (Oboe)

- `audio_engine.cpp` — Oboe input/output streams, Echo delay line, WAV writer
- `jni_bridge.cpp` — JNI for `com.roxstar.voiceroom.audio.NativeAudio`
- CMake FetchContent pulls [Oboe 1.9.3](https://github.com/google/oboe)

This directory is the Android `externalNativeBuild` root.
