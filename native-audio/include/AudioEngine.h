#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    bool startRecording(const std::string &path, int effectMode);
    void stopRecording();
    void cancelRecording();
    bool startPlayback(const std::string &path);
    void stopPlayback();
    bool isRecording() const { return recording_.load(); }
    bool isPlaying() const { return playing_.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    void writeWavHeader(FILE *file, int32_t dataBytes) const;
    void finalizeWav();
    void applyEcho(float *samples, int32_t frames);

    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::shared_ptr<oboe::AudioStream> outputStream_;
    std::mutex mutex_;
    std::atomic<bool> recording_{false};
    std::atomic<bool> playing_{false};
    std::atomic<bool> cancel_{false};
    FILE *file_ = nullptr;
    std::string path_;
    int32_t sampleRate_ = 48000;
    int32_t channels_ = 1;
    int32_t recordedBytes_ = 0;
    int effectMode_ = 0;
    std::vector<float> delayBuf_;
    size_t delayIndex_ = 0;
    std::vector<int16_t> playbackPcm_;
    size_t playbackIndex_ = 0;
};
