// MicUp-Custom 音频核心实现（C++ / Oboe / JNI）。
// DSP/可视化语义逐项对应 Kotlin 降级路径（Dsp.kt 的 BiquadFilter/DspChain/Fft 与
// AudioEngine.kt 的 pushVisual），公式、常量、节流节奏保持一致。

#include "AudioEngine.h"

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <thread>

#define LOG_TAG "MicUpAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// ===== JNI 桥全局状态 =====
namespace {

constexpr const char* kFrameName = "onNativeFrame";
constexpr const char* kFrameSig = "([F[F[FF)V";
constexpr const char* kErrorName = "onNativeError";
constexpr const char* kErrorSig = "(Ljava/lang/String;)V";
constexpr const char* kCallbackThreadName = "MicUp-NativeAudio";

JavaVM* g_vm = nullptr;
std::mutex g_mutex;
std::unique_ptr<micup::NativeEngine> g_engine;
// 回调线程只读；仅在 nativeSetListener（引擎停止后重建前）替换，替换前旧回调线程已被 join
std::atomic<jobject> g_listener{nullptr};
std::atomic<jmethodID> g_frameMid{nullptr};
std::atomic<jmethodID> g_errorMid{nullptr};

/**
 * 回调线程 JNI 附着守卫：构造时若尚未附着则 AttachCurrentThread，析构时 DetachCurrentThread。
 * JVM 线程（nativeFeed 路径）GetEnv 直接成功，不发生附着/脱离。
 */
class ScopedJniThread {
public:
    ScopedJniThread() {
        if (g_vm == nullptr) return;
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) == JNI_OK) return;
        JavaVMAttachArgs args{};
        args.version = JNI_VERSION_1_6;
        args.name = kCallbackThreadName;
        if (g_vm->AttachCurrentThread(&env_, &args) == JNI_OK) attached_ = true;
    }
    ~ScopedJniThread() {
        if (attached_) g_vm->DetachCurrentThread();
    }
    JNIEnv* env() const { return env_; }

private:
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

void lookupListenerMethods(JNIEnv* env, jobject listener) {
    jclass cls = env->GetObjectClass(listener);
    if (cls == nullptr) return;
    g_frameMid.store(env->GetMethodID(cls, kFrameName, kFrameSig));
    g_errorMid.store(env->GetMethodID(cls, kErrorName, kErrorSig));
    env->DeleteLocalRef(cls);
}

}  // namespace

void deliverVisualFrame(const float* wave, const float* bars, const float* peaks, float rmsDb) {
    ScopedJniThread jt;
    JNIEnv* env = jt.env();
    if (env == nullptr) return;
    jobject listener = g_listener.load();
    jmethodID mid = g_frameMid.load();
    if (listener == nullptr || mid == nullptr) return;
    // 每帧新建小数组（160+48+48 floats）：EngineFrame 直接持有交给 UI，快照语义同 Kotlin bars.copyOf()
    jfloatArray jw = env->NewFloatArray(micup::kWavePoints);
    jfloatArray jb = env->NewFloatArray(micup::kSpecBars);
    jfloatArray jp = env->NewFloatArray(micup::kSpecBars);
    if (jw == nullptr || jb == nullptr || jp == nullptr) {
        if (jw != nullptr) env->DeleteLocalRef(jw);
        if (jb != nullptr) env->DeleteLocalRef(jb);
        if (jp != nullptr) env->DeleteLocalRef(jp);
        return;
    }
    env->SetFloatArrayRegion(jw, 0, micup::kWavePoints, wave);
    env->SetFloatArrayRegion(jb, 0, micup::kSpecBars, bars);
    env->SetFloatArrayRegion(jp, 0, micup::kSpecBars, peaks);
    env->CallVoidMethod(listener, mid, jw, jb, jp, rmsDb);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(jw);
    env->DeleteLocalRef(jb);
    env->DeleteLocalRef(jp);
}

void notifyNativeError(const char* message) {
    ScopedJniThread jt;
    JNIEnv* env = jt.env();
    if (env == nullptr) return;
    jobject listener = g_listener.load();
    jmethodID mid = g_errorMid.load();
    if (listener == nullptr || mid == nullptr) return;
    jstring jmsg = env->NewStringUTF(message);
    if (jmsg == nullptr) return;
    env->CallVoidMethod(listener, mid, jmsg);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(jmsg);
}

namespace micup {

// ===== Biquad（语义同 Dsp.kt BiquadFilter） =====

void Biquad::reset() {
    x1_ = 0.0f;
    x2_ = 0.0f;
    y1_ = 0.0f;
    y2_ = 0.0f;
}

float Biquad::process(float x) {
    float y = b0_ * x + b1_ * x1_ + b2_ * x2_ - a1_ * y1_ - a2_ * y2_;
    x2_ = x1_;
    x1_ = x;
    y2_ = y1_;
    y1_ = y;
    return y;
}

void Biquad::process(float* buffer, int count) {
    for (int i = 0; i < count; i++) buffer[i] = process(buffer[i]);
}

void Biquad::set(double b0, double b1, double b2, double a0, double a1, double a2) {
    b0_ = static_cast<float>(b0 / a0);
    b1_ = static_cast<float>(b1 / a0);
    b2_ = static_cast<float>(b2 / a0);
    a1_ = static_cast<float>(a1 / a0);
    a2_ = static_cast<float>(a2 / a0);
}

void Biquad::setLowShelf(double f0, double gainDb, double fs) {
    double a = std::pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * kPi * f0 / fs;
    double cw = std::cos(w0);
    double sw = std::sin(w0);
    double alpha = sw / 2.0 * std::sqrt(2.0);  // S = 1
    double k = 2.0 * std::sqrt(a) * alpha;
    set(
        a * ((a + 1.0) - (a - 1.0) * cw + k),
        2.0 * a * ((a - 1.0) - (a + 1.0) * cw),
        a * ((a + 1.0) - (a - 1.0) * cw - k),
        (a + 1.0) + (a - 1.0) * cw + k,
        -2.0 * ((a - 1.0) + (a + 1.0) * cw),
        (a + 1.0) + (a - 1.0) * cw - k
    );
}

void Biquad::setHighShelf(double f0, double gainDb, double fs) {
    double a = std::pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * kPi * f0 / fs;
    double cw = std::cos(w0);
    double sw = std::sin(w0);
    double alpha = sw / 2.0 * std::sqrt(2.0);  // S = 1
    double k = 2.0 * std::sqrt(a) * alpha;
    set(
        a * ((a + 1.0) + (a - 1.0) * cw + k),
        -2.0 * a * ((a - 1.0) + (a + 1.0) * cw),
        a * ((a + 1.0) + (a - 1.0) * cw - k),
        (a + 1.0) - (a - 1.0) * cw + k,
        2.0 * ((a - 1.0) - (a + 1.0) * cw),
        (a + 1.0) - (a - 1.0) * cw - k
    );
}

void Biquad::setPeaking(double f0, double gainDb, double fs, double q) {
    double a = std::pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * kPi * f0 / fs;
    double cw = std::cos(w0);
    double sw = std::sin(w0);
    double alpha = sw / (2.0 * q);
    set(
        1.0 + alpha * a,
        -2.0 * cw,
        1.0 - alpha * a,
        1.0 + alpha / a,
        -2.0 * cw,
        1.0 - alpha / a
    );
}

// ===== DspChain（语义同 Dsp.kt DspChain） =====

DspChain::DspChain(int sampleRate) : sampleRate_(sampleRate) {}

void DspChain::configure(float lowDb, float midDb, float highDb, float gainDb) {
    lowDb = std::min(std::max(lowDb, -24.0f), 24.0f);
    midDb = std::min(std::max(midDb, -24.0f), 24.0f);
    highDb = std::min(std::max(highDb, -24.0f), 24.0f);
    if (lowDb != lowShelfDb_) {
        lowShelf_.setLowShelf(120.0, lowDb, sampleRate_);
        lowShelfDb_ = lowDb;
    }
    if (midDb != midPeakDb_) {
        midPeak_.setPeaking(1200.0, midDb, sampleRate_, 1.1);
        midPeakDb_ = midDb;
    }
    if (highDb != highShelfDb_) {
        highShelf_.setHighShelf(6000.0, highDb, sampleRate_);
        highShelfDb_ = highDb;
    }
    gainLinear_ = std::pow(10.0f, gainDb / 20.0f);
    gainDb_ = gainDb;
}

void DspChain::reset() {
    lowShelf_.reset();
    midPeak_.reset();
    highShelf_.reset();
}

void DspChain::process(float* buffer, int count) {
    lowShelf_.process(buffer, count);
    midPeak_.process(buffer, count);
    highShelf_.process(buffer, count);
    for (int i = 0; i < count; i++) {
        buffer[i] = softLimit(buffer[i] * gainLinear_);
    }
}

/** |v| <= 0.5 线性直通，超出部分 tanh 压缩（同 Dsp.kt softLimit） */
float DspChain::softLimit(float v) {
    float a = std::abs(v);
    if (a <= 0.5f) return v;
    float sign = v > 0.0f ? 1.0f : -1.0f;
    return sign * (0.5f + 0.5f * std::tanh((a - 0.5f) * 2.0f));
}

// ===== Fft（语义同 Dsp.kt Fft） =====

Fft::Fft(int size) : size_(size) {
    int bits = 0;
    while ((1 << bits) < size) bits++;
    rev_ = std::make_unique<int[]>(size);
    for (int i = 0; i < size; i++) {
        int r = 0;
        for (int b = 0; b < bits; b++) {
            if ((i & (1 << b)) != 0) r |= 1 << (bits - 1 - b);
        }
        rev_[i] = r;
    }
    cosTable_ = std::make_unique<double[]>(size / 2);
    sinTable_ = std::make_unique<double[]>(size / 2);
    for (int i = 0; i < size / 2; i++) {
        double ang = -2.0 * kPi * i / size;
        cosTable_[i] = std::cos(ang);
        sinTable_[i] = std::sin(ang);
    }
    re_ = std::make_unique<double[]>(size);
    im_ = std::make_unique<double[]>(size);
}

void Fft::magnitude(const float* input, float* out) {
    std::memset(im_.get(), 0, static_cast<size_t>(size_) * sizeof(double));
    for (int i = 0; i < size_; i++) re_[rev_[i]] = input[i];
    int len = 2;
    while (len <= size_) {
        int half = len / 2;
        int step = size_ / len;
        for (int base = 0; base < size_; base += len) {
            for (int j = 0; j < half; j++) {
                int k = j * step;
                double wr = cosTable_[k];
                double wi = sinTable_[k];
                double xr = re_[base + j + half];
                double xi = im_[base + j + half];
                double tr = xr * wr - xi * wi;
                double ti = xr * wi + xi * wr;
                re_[base + j + half] = re_[base + j] - tr;
                im_[base + j + half] = im_[base + j] - ti;
                re_[base + j] += tr;
                im_[base + j] += ti;
            }
        }
        len <<= 1;
    }
    for (int i = 0; i < size_ / 2; i++) {
        out[i] = static_cast<float>(std::sqrt(re_[i] * re_[i] + im_[i] * im_[i]));
    }
}

// ===== NativeEngine =====

NativeEngine::NativeEngine(int sampleRate, int channels)
    : sampleRate_(sampleRate), channels_(channels), chain_(sampleRate), fft_(kFftSize) {
    hann_ = std::make_unique<float[]>(kFftSize);
    fftIn_ = std::make_unique<float[]>(kFftSize);
    specRaw_ = std::make_unique<float[]>(kFftSize / 2);
    rolling_ = std::make_unique<float[]>(kFftSize);
    bars_ = std::make_unique<float[]>(kSpecBars);
    peaks_ = std::make_unique<float[]>(kSpecBars);
    wave_ = std::make_unique<float[]>(kWavePoints);
    stage_ = std::make_unique<float[]>(kChunk);
    std::memset(rolling_.get(), 0, kFftSize * sizeof(float));
    std::memset(peaks_.get(), 0, kSpecBars * sizeof(float));
    for (int i = 0; i < kFftSize; i++) {
        hann_[i] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * kPi * i / kFftSize));
    }
    // 48 根频谱柱对数分桶（40Hz ~ 20kHz）：逐条对应 Kotlin BAR_EDGES 的三段处理
    for (int b = 0; b <= kSpecBars; b++) {
        double f = 40.0 * std::pow(20000.0 / 40.0, static_cast<double>(b) / kSpecBars);
        long e = std::lround(f * kFftSize / kSampleRate);
        barEdges_[b] = static_cast<int>(std::min<long>(std::max<long>(e, 0), kFftSize / 2 - 1));
    }
    for (int b = 1; b <= kSpecBars; b++) {
        if (barEdges_[b] <= barEdges_[b - 1]) barEdges_[b] = barEdges_[b - 1] + 1;
    }
    for (int b = 0; b <= kSpecBars; b++) {
        barEdges_[b] = std::min(barEdges_[b], kFftSize / 2 - 1);
    }
}

NativeEngine::~NativeEngine() {
    stop();
}

bool NativeEngine::start(bool useOboeInput, int bufferMultiplier) {
    useOboeInput_.store(useOboeInput, std::memory_order_relaxed);
    monitorTimeoutNs_ = useOboeInput ? 0 : 20000000LL;  // feed 路径 20ms，近似 WRITE_BLOCKING
    chunkCount_ = 0;
    stageFill_ = 0;
    chain_.reset();
    if (!useOboeInput) {
        // 系统内录模式：采集由 Kotlin AudioRecord 承载并经 feed() 送入，native 只做 DSP/监听/可视化
        running_.store(true, std::memory_order_relaxed);
        return true;
    }
    if (!openInput(bufferMultiplier)) return false;
    running_.store(true, std::memory_order_relaxed);
    return true;
}

void NativeEngine::stop() {
    running_.store(false, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lk(monitorMutex_);
        closeMonitorLocked();
    }
    {
        std::lock_guard<std::mutex> lk(streamMutex_);
        if (stream_) {
            stream_->close();  // close 同步 join 回调线程，之后才能安全销毁引擎
            stream_.reset();
        }
    }
}

bool NativeEngine::openInput(int bufferMultiplier) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           // 对应 MediaRecorder.AudioSource.VOICE_COMMUNICATION（回声消除链路，语义同 Kotlin buildMicRecord）
           ->setInputPreset(oboe::InputPreset::VoiceCommunication)
           ->setSampleRate(sampleRate_)
           ->setChannelCount(channels_)
           ->setFormat(oboe::AudioFormat::Float)
           // 对应 Kotlin max(minBuf, CHUNK*4 字节) * bufferMultiplier 的缓冲意图（帧数 = CHUNK * 倍数）
           ->setBufferCapacityInFrames(kChunk * std::max(bufferMultiplier, 1))
           ->setCallback(this);
    oboe::Result r = builder.openStream(stream_);
    if (r != oboe::Result::OK || !stream_) {
        LOGW("Oboe 输入流创建失败: %s", oboe::convertToText(r));
        stream_.reset();
        return false;
    }
    r = stream_->requestStart();
    if (r != oboe::Result::OK) {
        LOGW("Oboe 输入流启动失败: %s", oboe::convertToText(r));
        stream_->close();
        stream_.reset();
        return false;
    }
    LOGI("Oboe 输入流已启动: sr=%d ch=%d capacity=%d frames",
         sampleRate_, channels_, stream_->getBufferCapacityInFrames());
    return true;
}

void NativeEngine::reopenInput() {
    for (int attempt = 1; attempt <= 3; attempt++) {
        if (!running_.load(std::memory_order_relaxed)) return;
        if (attempt > 1) std::this_thread::sleep_for(std::chrono::milliseconds(100));
        std::shared_ptr<oboe::AudioStream> s;
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setInputPreset(oboe::InputPreset::VoiceCommunication)
               ->setSampleRate(sampleRate_)
               ->setChannelCount(channels_)
               ->setFormat(oboe::AudioFormat::Float)
               ->setCallback(this);
        oboe::Result r = builder.openStream(s);
        if (r == oboe::Result::OK && s) {
            r = s->requestStart();
            if (r == oboe::Result::OK) {
                if (!running_.load(std::memory_order_relaxed)) {
                    s->close();
                    return;
                }
                std::lock_guard<std::mutex> lk(streamMutex_);
                stream_ = s;
                LOGI("Oboe 输入流重连成功（第 %d 次尝试，滤波状态保留）", attempt);
                return;
            }
            s->close();
        }
        LOGW("Oboe 输入流重连失败（第 %d 次）: %s", attempt, oboe::convertToText(r));
    }
    running_.store(false, std::memory_order_relaxed);
    notifyNativeError("Oboe 输入流断开且重连失败，采集终止");
}

oboe::DataCallbackResult NativeEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData, int32_t numFrames) {
    if (!running_.load(std::memory_order_relaxed)) return oboe::DataCallbackResult::Stop;
    const float* in = static_cast<const float*>(audioData);
    int32_t remaining = numFrames;
    // 累积到 kChunk（1024）再处理：与 Kotlin 每块 1024 样本的 DSP/可视化节奏一致；循环内零分配
    while (remaining > 0 && running_.load(std::memory_order_relaxed)) {
        int32_t take = std::min(remaining, kChunk - stageFill_);
        std::memcpy(stage_.get() + stageFill_, in, static_cast<size_t>(take) * sizeof(float));
        stageFill_ += take;
        in += take;
        remaining -= take;
        if (stageFill_ == kChunk) {
            processChunk(stage_.get(), kChunk);
            stageFill_ = 0;
        }
    }
    return oboe::DataCallbackResult::Continue;
}

void NativeEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    if (!running_.load(std::memory_order_relaxed)) return;
    LOGW("Oboe 输入流错误: %s，尝试重连（对应 Kotlin 路径 DEAD_OBJECT 重建）", oboe::convertToText(error));
    {
        // Oboe 已自动关闭出错的流；清除成员引用后重建
        std::lock_guard<std::mutex> lk(streamMutex_);
        stream_.reset();
    }
    reopenInput();
}

void NativeEngine::feed(float* data, int count) {
    if (!running_.load(std::memory_order_relaxed) || count <= 0) return;
    processChunk(data, count);
}

void NativeEngine::processChunk(float* chunk, int n) {
    chain_.process(chunk, n);
    if (monitorEnabled_.load(std::memory_order_relaxed)) {
        std::shared_ptr<oboe::AudioStream> mon;
        {
            std::lock_guard<std::mutex> lk(monitorMutex_);
            mon = monitor_;
        }
        if (mon && mon->getState() == oboe::StreamState::Open) {
            // 监听写失败不中断采集（语义同 Kotlin 路径的 try-catch 忽略）
            (void)mon->write(chunk, n, monitorTimeoutNs_);
        }
    }
    pushVisual(chunk, n);
}

void NativeEngine::setEq(float lowDb, float midDb, float highDb) {
    chain_.configure(lowDb, midDb, highDb, chain_.gainDb());
}

void NativeEngine::setGain(float gainDb) {
    // 复用当前 EQ dB 重算链（configure 内部仅在 dB 变化时重算双二阶系数）
    chain_.configure(chain_.lowDb(), chain_.midDb(), chain_.highDb(), gainDb);
}

void NativeEngine::setMonitor(bool enabled) {
    monitorEnabled_.store(enabled, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lk(monitorMutex_);
    if (!enabled) {
        if (monitor_ && monitor_->getState() == oboe::StreamState::Open) {
            (void)monitor_->requestStop();
        }
        return;
    }
    if (!monitor_ || monitor_->getState() != oboe::StreamState::Open) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               // 语义同 USAGE_ASSISTANCE_SONIFICATION：避开内录捕获范围（MEDIA/GAME/UNKNOWN）防自激
               ->setUsage(oboe::Usage::AssistanceSonification)
               ->setContentType(oboe::ContentType::Sonification)
               ->setSampleRate(sampleRate_)
               ->setChannelCount(1)
               ->setFormat(oboe::AudioFormat::Float)
               // 对应 Kotlin max(minBuf, CHUNK*4 字节)*2 字节 = CHUNK*2 帧
               ->setBufferCapacityInFrames(kChunk * 2);
        oboe::Result r = builder.openStream(monitor_);
        if (r != oboe::Result::OK || !monitor_) {
            LOGW("监听输出流创建失败: %s（监听关闭，采集继续）", oboe::convertToText(r));
            monitor_.reset();
            monitorEnabled_.store(false, std::memory_order_relaxed);
            return;
        }
    }
    (void)monitor_->requestStart();
}

void NativeEngine::reset() {
    chain_.reset();
}

void NativeEngine::closeMonitorLocked() {
    if (monitor_) {
        monitor_->close();
        monitor_.reset();
    }
}

/** 语义同 AudioEngine.kt pushVisual：滚动窗口 → 汉宁窗 FFT → 对数分桶 → 波形/频谱帧 */
void NativeEngine::pushVisual(const float* buf, int n) {
    // 滚动窗口（供 FFT 与波形）
    if (n >= kFftSize) {
        std::memcpy(rolling_.get(), buf + (n - kFftSize), kFftSize * sizeof(float));
    } else {
        std::memmove(rolling_.get(), rolling_.get() + n,
                     static_cast<size_t>(kFftSize - n) * sizeof(float));
        std::memcpy(rolling_.get() + (kFftSize - n), buf, static_cast<size_t>(n) * sizeof(float));
    }
    // RMS 电平
    float acc = 0.0f;
    for (int i = 0; i < n; i++) acc += buf[i] * buf[i];
    float rms = std::sqrt(acc / n);
    chunkCount_++;
    if ((chunkCount_ % 2) != 0) return;  // ~23fps 节流，同 Kotlin chunkCount % 2
    // 频谱（加汉宁窗 + 对数分桶）
    for (int i = 0; i < kFftSize; i++) fftIn_[i] = rolling_[i] * hann_[i];
    fft_.magnitude(fftIn_.get(), specRaw_.get());
    float maxBar = 1e-4f;
    for (int b = 0; b < kSpecBars; b++) {
        int lo = barEdges_[b];
        int hi = std::max(barEdges_[b + 1], lo + 1);
        float s = 0.0f;
        for (int k = lo; k < hi; k++) s += specRaw_[k];
        float avg = s / (hi - lo);
        bars_[b] = avg;
        if (avg > maxBar) maxBar = avg;
    }
    for (int b = 0; b < kSpecBars; b++) {
        float v = std::pow(bars_[b] / maxBar, 0.5f);
        v = std::min(std::max(v, 0.0f), 1.0f);
        bars_[b] = v;
        peaks_[b] = std::max(v, peaks_[b] * 0.90f);
    }
    // 波形（峰值归一化，下限 0.03 防噪声放大）
    float mx = 0.03f;
    for (int i = 0; i < kWavePoints; i++) {
        float v = rolling_[i * kFftSize / kWavePoints];
        wave_[i] = v;
        float a = std::abs(v);
        if (a > mx) mx = a;
    }
    for (int i = 0; i < kWavePoints; i++) {
        wave_[i] = std::min(std::max(wave_[i] / mx, -1.0f), 1.0f);
    }
    float rmsDb = rms > 1e-6f ? 20.0f * std::log10(rms) : -120.0f;  // 同 Dsp.kt rmsToDb
    deliverVisualFrame(wave_.get(), bars_.get(), peaks_.get(), rmsDb);
}

}  // namespace micup

// ===== JNI 入口：com.micup.custom.audio.NativeAudioEngine =====

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_micup_custom_audio_NativeAudioEngine_nativeSetListener(JNIEnv* env, jobject /*thiz*/, jobject listener) {
    std::lock_guard<std::mutex> lk(g_mutex);
    jobject old = g_listener.load();
    if (listener != nullptr) {
        lookupListenerMethods(env, listener);
        g_listener.store(env->NewGlobalRef(listener));
        if (g_listener.load() == nullptr) {
            g_frameMid.store(nullptr);
            g_errorMid.store(nullptr);
            return JNI_FALSE;
        }
    } else {
        g_listener.store(nullptr);
        g_frameMid.store(nullptr);
        g_errorMid.store(nullptr);
    }
    if (old != nullptr) env->DeleteGlobalRef(old);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_micup_custom_audio_NativeAudioEngine_nativeStart(JNIEnv* env, jobject /*thiz*/,
                                                          jint sampleRate, jint channels,
                                                          jint bufferMultiplier, jboolean useOboeInput) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_vm == nullptr) {
        env->GetJavaVM(&g_vm);
    }
    // 重建引擎：旧引擎 stop() 会同步 join 回调线程，之后才安全销毁
    if (g_engine) {
        g_engine->stop();
        g_engine.reset();
    }
    g_engine = std::make_unique<micup::NativeEngine>(sampleRate, channels);
    return g_engine->start(useOboeInput == JNI_TRUE, bufferMultiplier) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_micup_custom_audio_NativeAudioEngine_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_engine) {
        g_engine->stop();
    }
    // 引擎对象保留至下次 nativeStart 再销毁：Oboe 错误回调线程可能仍在收尾，避免悬垂
}

JNIEXPORT void JNICALL
Java_com_micup_custom_audio_NativeAudioEngine_nativeSetEq(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jfloat lowDb, jfloat midDb, jfloat highDb) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_engine) g_engine->setEq(lowDb, midDb, highDb);
}

JNIEXPORT void JNICALL
Java_com_micup_custom_audio_NativeAudioEngine_nativeSetGain(JNIEnv* /*env*/, jobject /*thiz*/, jfloat gainDb) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_engine) g_engine->setGain(gainDb);
}

JNIEXPORT void JNICALL
Java_com_micup_custom_audio_NativeAudioEngine_nativeSetMonitor(JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_engine) g_engine->setMonitor(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_micup_custom_audio_NativeAudioEngine_nativeReset(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_engine) g_engine->reset();
}

JNIEXPORT void JNICALL
Java_com_micup_custom_audio_NativeAudioEngine_nativeFeed(JNIEnv* env, jobject /*thiz*/,
                                                         jfloatArray buffer, jint count) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_engine || !g_engine->running() || count <= 0) return;
    jsize len = env->GetArrayLength(buffer);
    if (count > len) count = len;
    jfloat* data = env->GetFloatArrayElements(buffer, nullptr);
    if (data == nullptr) return;
    g_engine->feed(data, count);
    env->ReleaseFloatArrayElements(buffer, data, 0);
}

}  // extern "C"
