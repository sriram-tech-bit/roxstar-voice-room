#include "include/AudioEngine.h"

#include <android/log.h>
#include <cstring>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RoxstarAudio", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RoxstarAudio", __VA_ARGS__)

namespace {
constexpr int kByteRateHeaderOffset = 28;
constexpr int kDataSizeOffset = 40;
constexpr int kWavHeaderSize = 44;

void writeLe32(uint8_t *p, uint32_t v) {
    p[0] = v & 0xff;
    p[1] = (v >> 8) & 0xff;
    p[2] = (v >> 16) & 0xff;
    p[3] = (v >> 24) & 0xff;
}

void writeLe16(uint8_t *p, uint16_t v) {
    p[0] = v & 0xff;
    p[1] = (v >> 8) & 0xff;
}

int16_t floatToI16(float s) {
    float c = std::fmax(-1.f, std::fmin(1.f, s));
    return static_cast<int16_t>(c * 32767.f);
}
}  // namespace

bool AudioEngine::startRecording(const std::string &path, int effectMode) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (recording_) return false;
    stopPlayback();
    path_ = path;
    effectMode_ = effectMode;
    recordedBytes_ = 0;
    cancel_ = false;
    sampleRate_ = 48000;
    channels_ = 1;
    delayBuf_.assign(static_cast<size_t>(sampleRate_ * 0.25f), 0.f);
    delayIndex_ = 0;

    file_ = fopen(path_.c_str(), "wb");
    if (!file_) {
        LOGE("Failed to open %s", path_.c_str());
        return false;
    }
    uint8_t header[kWavHeaderSize] = {0};
    std::memcpy(header, "RIFF", 4);
    writeLe32(header + 4, 36);
    std::memcpy(header + 8, "WAVEfmt ", 8);
    writeLe32(header + 16, 16);
    writeLe16(header + 20, 1);
    writeLe16(header + 22, 1);
    writeLe32(header + 24, sampleRate_);
    writeLe32(header + 28, sampleRate_ * 2);
    writeLe16(header + 32, 2);
    writeLe16(header + 34, 16);
    std::memcpy(header + 36, "data", 4);
    writeLe32(header + 40, 0);
    fwrite(header, 1, kWavHeaderSize, file_);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(channels_)
            ->setSampleRate(sampleRate_)
            ->setInputPreset(oboe::InputPreset::VoiceCommunication)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    oboe::Result result = builder.openStream(inputStream_);
    if (result != oboe::Result::OK) {
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(inputStream_);
    }
    if (result != oboe::Result::OK) {
        LOGE("open input failed %s", oboe::convertToText(result));
        fclose(file_);
        file_ = nullptr;
        return false;
    }
    sampleRate_ = inputStream_->getSampleRate();
    result = inputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("start input failed %s", oboe::convertToText(result));
        inputStream_->close();
        inputStream_.reset();
        fclose(file_);
        file_ = nullptr;
        return false;
    }
    recording_ = true;
    return true;
}

void AudioEngine::stopRecording() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!recording_) return;
    recording_ = false;
    if (inputStream_) {
        inputStream_->requestStop();
        inputStream_->close();
        inputStream_.reset();
    }
    finalizeWav();
}

void AudioEngine::cancelRecording() {
    cancel_ = true;
    stopRecording();
    if (!path_.empty()) {
        std::remove(path_.c_str());
        path_.clear();
    }
}

bool AudioEngine::startPlayback(const std::string &path) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (playing_ || recording_) return false;
    FILE *in = fopen(path.c_str(), "rb");
    if (!in) return false;
    fseek(in, 0, SEEK_END);
    long size = ftell(in);
    fseek(in, 0, SEEK_SET);
    if (size <= kWavHeaderSize) {
        fclose(in);
        return false;
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    fread(bytes.data(), 1, bytes.size(), in);
    fclose(in);
    sampleRate_ = bytes[24] | (bytes[25] << 8) | (bytes[26] << 16) | (bytes[27] << 24);
    const uint8_t *data = bytes.data() + kWavHeaderSize;
    size_t pcmBytes = bytes.size() - kWavHeaderSize;
    playbackPcm_.resize(pcmBytes / 2);
    std::memcpy(playbackPcm_.data(), data, pcmBytes);
    playbackIndex_ = 0;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(1)
            ->setSampleRate(sampleRate_)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    auto result = builder.openStream(outputStream_);
    if (result != oboe::Result::OK) return false;
    playing_ = true;
    return outputStream_->requestStart() == oboe::Result::OK;
}

void AudioEngine::stopPlayback() {
    playing_ = false;
    if (outputStream_) {
        outputStream_->requestStop();
        outputStream_->close();
        outputStream_.reset();
    }
    playbackPcm_.clear();
    playbackIndex_ = 0;
}

void AudioEngine::writeWavHeader(FILE *file, int32_t dataBytes) const {
    uint8_t h[kWavHeaderSize] = {0};
    std::memcpy(h, "RIFF", 4);
    writeLe32(h + 4, 36 + dataBytes);
    std::memcpy(h + 8, "WAVEfmt ", 8);
    writeLe32(h + 16, 16);
    writeLe16(h + 20, 1);
    writeLe16(h + 22, static_cast<uint16_t>(channels_));
    writeLe32(h + 24, sampleRate_);
    writeLe32(h + 28, sampleRate_ * channels_ * 2);
    writeLe16(h + 32, static_cast<uint16_t>(channels_ * 2));
    writeLe16(h + 34, 16);
    std::memcpy(h + 36, "data", 4);
    writeLe32(h + 40, dataBytes);
    if (file == reinterpret_cast<FILE *>(h)) {
        return;
    }
}

void AudioEngine::finalizeWav() {
    if (!file_) return;
    if (cancel_) {
        fclose(file_);
        file_ = nullptr;
        return;
    }
    fseek(file_, 4, SEEK_SET);
    uint32_t riff = 36 + recordedBytes_;
    uint8_t b[4];
    writeLe32(b, riff);
    fwrite(b, 1, 4, file_);
    fseek(file_, kDataSizeOffset, SEEK_SET);
    writeLe32(b, recordedBytes_);
    fwrite(b, 1, 4, file_);
    fclose(file_);
    file_ = nullptr;
}

void AudioEngine::applyEcho(float *samples, int32_t frames) {
    if (effectMode_ == 0 || delayBuf_.empty()) return;
    const float decay = 0.45f;
    for (int32_t i = 0; i < frames; ++i) {
        float delayed = delayBuf_[delayIndex_];
        float mixed = samples[i] + delayed * decay;
        mixed = std::fmax(-1.f, std::fmin(1.f, mixed));
        delayBuf_[delayIndex_] = samples[i];
        delayIndex_ = (delayIndex_ + 1) % delayBuf_.size();
        samples[i] = mixed;
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) {
    auto *samples = static_cast<float *>(audioData);
    if (stream->getDirection() == oboe::Direction::Input && recording_) {
        applyEcho(samples, numFrames * stream->getChannelCount());
        std::vector<int16_t> pcm(numFrames * stream->getChannelCount());
        for (size_t i = 0; i < pcm.size(); ++i) {
            pcm[i] = floatToI16(samples[i]);
        }
        std::lock_guard<std::mutex> lock(mutex_);
        if (file_) {
            fwrite(pcm.data(), sizeof(int16_t), pcm.size(), file_);
            recordedBytes_ += static_cast<int32_t>(pcm.size() * sizeof(int16_t));
        }
    } else if (stream->getDirection() == oboe::Direction::Output && playing_) {
        for (int32_t i = 0; i < numFrames; ++i) {
            if (playbackIndex_ >= playbackPcm_.size()) {
                samples[i] = 0.f;
                playing_ = false;
            } else {
                samples[i] = playbackPcm_[playbackIndex_++] / 32768.f;
            }
        }
        if (!playing_) return oboe::DataCallbackResult::Stop;
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *, oboe::Result error) {
    LOGE("stream error %s", oboe::convertToText(error));
    recording_ = false;
    playing_ = false;
}
