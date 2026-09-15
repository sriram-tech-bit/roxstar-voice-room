package com.roxstar.voiceroom.audio

object NativeAudio {
    init {
        System.loadLibrary("roxstar-audio")
        nativeInit()
    }

    private external fun nativeInit()
    private external fun nativeStartRecording(path: String, effect: Int): Boolean
    private external fun nativeStopRecording()
    private external fun nativeCancelRecording()
    private external fun nativeStartPlayback(path: String): Boolean
    private external fun nativeStopPlayback()

    fun startRecording(path: String, echo: Boolean = true): Boolean =
        nativeStartRecording(path, if (echo) 1 else 0)

    fun stopRecording() = nativeStopRecording()
    fun cancelRecording() = nativeCancelRecording()
    fun startPlayback(path: String): Boolean = nativeStartPlayback(path)
    fun stopPlayback() = nativeStopPlayback()
}
