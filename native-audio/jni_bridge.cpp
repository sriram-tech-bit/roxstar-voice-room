#include "include/AudioEngine.h"

#include <jni.h>
#include <memory>
#include <string>

namespace {
std::unique_ptr<AudioEngine> gEngine;

std::string jstringToString(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    env->ReleaseStringUTFChars(value, chars);
    return out;
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_roxstar_voiceroom_audio_NativeAudio_nativeInit(JNIEnv *, jobject) {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_roxstar_voiceroom_audio_NativeAudio_nativeStartRecording(JNIEnv *env, jobject, jstring path, jint effect) {
    if (!gEngine) return JNI_FALSE;
    return gEngine->startRecording(jstringToString(env, path), effect) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_roxstar_voiceroom_audio_NativeAudio_nativeStopRecording(JNIEnv *, jobject) {
    if (gEngine) gEngine->stopRecording();
}

extern "C" JNIEXPORT void JNICALL
Java_com_roxstar_voiceroom_audio_NativeAudio_nativeCancelRecording(JNIEnv *, jobject) {
    if (gEngine) gEngine->cancelRecording();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_roxstar_voiceroom_audio_NativeAudio_nativeStartPlayback(JNIEnv *env, jobject, jstring path) {
    if (!gEngine) return JNI_FALSE;
    return gEngine->startPlayback(jstringToString(env, path)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_roxstar_voiceroom_audio_NativeAudio_nativeStopPlayback(JNIEnv *, jobject) {
    if (gEngine) gEngine->stopPlayback();
}
