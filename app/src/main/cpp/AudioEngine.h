// MicUp-Custom 音频核心（C++ / Oboe / NDK）。
//
// 职责：
//   1. 麦克风模式：Oboe 输入流（自动选 AAudio/OpenSL，minSdk 26 兼容）接管原 AudioRecord 读取；
//   2. 原地 DSP：三段 EQ 双二阶 + 增益 + 软限幅（语义逐项对应 Kotlin 降级路径 Dsp.kt）；
//   3. 监听输出路由：USAGE_ASSISTANCE_SONIFICATION 输出流，避开内录捕获范围防自激（同 Kotlin AudioTrack）；
//   4. 可视化管线：滚动窗口 + 汉宁窗 + radix-2 FFT + 40Hz~20kHz 对数分桶（同 Kotlin pushVisual），
//      仅把降采样后的波形/频谱帧经 JNI 抛给 Kotlin（不回传音频流）。
//
// 回调约束：onAudioReady 内零内存分配（缓冲全部预分配）、锁最小化（仅监听流指针短临界区）。
// 同进程至多一个活跃引擎实例（JNI 层 global 单例）。

#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cmath>
#include <limits>
#include <memory>
#include <mutex>

// ===== JNI 回调入口（实现在 AudioEngine.cpp）=====
// 仅抛 UI 可视化帧与致命错误，不回传音频流。由 Kotlin NativeAudioEngine.Listener 承接。
void deliverVisualFrame(const float* wave, const float* bars, const float* peaks, float rmsDb);
void notifyNativeError(const char* message);

namespace micup {

// 与 Kotlin AudioEngine companion 常量一致（AudioEngine.kt）
constexpr int kSampleRate = 48000;
constexpr int kChunk = 1024;
constexpr int kFftSize = 1024;
constexpr int kSpecBars = 48;
constexpr int kWavePoints = 160;

constexpr double kPi = 3.14159265358979323846;

/** RBJ Audio EQ Cookbook 双二阶滤波器（float DF1，语义同 Dsp.kt BiquadFilter：热更新系数保留滤波状态） */
class Biquad {
public:
    void reset();
    float process(float x);
    void process(float* buffer, int count);
    void setLowShelf(double f0, double gainDb, double fs);
    void setHighShelf(double f0, double gainDb, double fs);
    void setPeaking(double f0, double gainDb, double fs, double q);

private:
    void set(double b0, double b1, double b2, double a0, double a1, double a2);

    float b0_ = 1.0f;
    float b1_ = 0.0f;
    float b2_ = 0.0f;
    float a1_ = 0.0f;
    float a2_ = 0.0f;
    float x1_ = 0.0f;
    float x2_ = 0.0f;
    float y1_ = 0.0f;
    float y2_ = 0.0f;
};

/** 处理链：三段 EQ → 增益 → 软限幅（语义同 Dsp.kt DspChain：仅 dB 变化时重算双二阶系数）。
 *  预设偏移与 ±24dB clamp 由 Kotlin 侧完成后传入最终 dB。 */
class DspChain {
public:
    explicit DspChain(int sampleRate);

    void configure(float lowDb, float midDb, float highDb, float gainDb);
    void reset();
    void process(float* buffer, int count);

    float lowDb() const { return lowShelfDb_; }
    float midDb() const { return midPeakDb_; }
    float highDb() const { return highShelfDb_; }
    float gainDb() const { return gainDb_; }

private:
    static float softLimit(float v);

    int sampleRate_;
    Biquad lowShelf_;
    Biquad midPeak_;
    Biquad highShelf_;
    float lowShelfDb_ = std::numeric_limits<float>::quiet_NaN();
    float midPeakDb_ = std::numeric_limits<float>::quiet_NaN();
    float highShelfDb_ = std::numeric_limits<float>::quiet_NaN();
    float gainLinear_ = 1.0f;
    float gainDb_ = 0.0f;
};

/** radix-2 FFT（语义同 Dsp.kt Fft：double 精度，倒位序预生成） */
class Fft {
public:
    explicit Fft(int size);
    void magnitude(const float* input, float* out);

private:
    int size_;
    std::unique_ptr<int[]> rev_;
    std::unique_ptr<double[]> cosTable_;
    std::unique_ptr<double[]> sinTable_;
    std::unique_ptr<double[]> re_;
    std::unique_ptr<double[]> im_;
};

/**
 * native 音频引擎。
 * - useOboeInput=true（麦克风模式）：Oboe 输入流回调驱动，累积到 kChunk 后原地 DSP；
 * - useOboeInput=false（系统内录模式）：投屏捕获只能由 Java 层 AudioRecord 承载，
 *   Kotlin 读循环经 feed() 送入，DSP/监听/可视化全部在 C++ 完成。
 */
class NativeEngine : public oboe::AudioStreamCallback {
public:
    NativeEngine(int sampleRate, int channels);
    ~NativeEngine() override;

    bool start(bool useOboeInput, int bufferMultiplier);
    void stop();
    bool running() const { return running_.load(std::memory_order_relaxed); }

    void setEq(float lowDb, float midDb, float highDb);
    void setGain(float gainDb);
    void setMonitor(bool enabled);
    void reset();

    /** Kotlin 读循环路径：对 data[0..count) 原地 DSP + 监听路由 + 可视化（JVM 线程调用） */
    void feed(float* data, int count);

    // oboe::AudioStreamCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    void processChunk(float* chunk, int n);
    void pushVisual(const float* buf, int n);
    bool openInput(int bufferMultiplier);
    void reopenInput();
    void closeMonitorLocked();

    int sampleRate_;
    int channels_;

    std::atomic<bool> running_{false};
    std::atomic<bool> monitorEnabled_{false};
    std::atomic<bool> useOboeInput_{false};
    // Oboe 回调路径用非阻塞写（0 超时，防输入回调阻塞）；feed 路径用 20ms 超时近似 WRITE_BLOCKING
    int64_t monitorTimeoutNs_ = 0;

    DspChain chain_;
    Fft fft_;
    std::unique_ptr<float[]> hann_;
    std::unique_ptr<float[]> fftIn_;
    std::unique_ptr<float[]> specRaw_;
    std::unique_ptr<float[]> rolling_;
    std::unique_ptr<float[]> bars_;
    std::unique_ptr<float[]> peaks_;
    std::unique_ptr<float[]> wave_;
    std::unique_ptr<float[]> stage_;   // 输入回调的分块累积缓冲（kChunk）
    int stageFill_ = 0;
    int chunkCount_ = 0;
    int barEdges_[kSpecBars + 1];

    std::mutex monitorMutex_;
    std::shared_ptr<oboe::AudioStream> monitor_;

    std::mutex streamMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
};

}  // namespace micup
