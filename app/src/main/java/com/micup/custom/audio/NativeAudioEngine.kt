package com.micup.custom.audio

/**
 * native 音频核心 JNI 桥（Oboe 采集 + C++ 原地 DSP）。
 *
 * 职责边界：
 * - 麦克风模式（useOboeInput=true）：Oboe 输入流接管采集，回调线程内原地 DSP（零内存分配），
 *   仅把降采样后的波形/频谱帧经 [Listener] 抛回，Kotlin 不参与音频流处理；
 * - 系统内录模式（useOboeInput=false）：投屏捕获只能由 Java 层 AudioRecord
 *   （AudioPlaybackCaptureConfiguration）承载，Oboe 无法读取投屏音频，
 *   Kotlin 读循环仅负责读取，DSP/监听/可视化经 [nativeFeed] 全部下沉到 C++；
 * - native 库加载或初始化失败时由 [create] 返回 null，AudioEngine 回退原纯 Kotlin 路径（Dsp.kt）。
 *
 * 同进程至多一个活跃 native 引擎实例：nativeStart 会停掉并重建旧实例。
 */
class NativeAudioEngine private constructor() {

    /** native 音频/处理线程抛出的可视化帧与致命错误 */
    interface Listener {
        /** 每帧新建数组（160 波形点 + 48 频谱柱 + 48 峰值），快照语义同 Kotlin bars.copyOf() */
        fun onNativeFrame(wave: FloatArray, bars: FloatArray, peaks: FloatArray, rmsDb: Float)

        /** native 运行期致命错误（如 Oboe 输入流断开且重连失败），触发引擎收尾退出 */
        fun onNativeError(message: String)
    }

    /** 注册/清除回调（传 null 清除）。native 侧持全局引用，替换时释放旧引用 */
    external fun nativeSetListener(listener: Listener?)

    /**
     * 启动 native 核心。
     * @param sampleRate 采样率（AudioEngine.SAMPLE_RATE = 48000）
     * @param channels 声道数（单声道 = 1）
     * @param bufferMultiplier 缓冲倍数（语义同 Kotlin max(minBuf, CHUNK*4) * multiplier，
     *        仅在启动时生效，改缓冲需重启，同"重启后生效"约定）
     * @param useOboeInput true=Oboe 输入流接管麦克风采集；false=内录 feed 模式
     * @return 启动成功 true；失败 false（调用方回退 Kotlin 路径）
     */
    external fun nativeStart(sampleRate: Int, channels: Int, bufferMultiplier: Int, useOboeInput: Boolean): Boolean

    /** 停止并关闭 Oboe 流（幂等；引擎对象由 native 侧保留至下次 nativeStart 再销毁） */
    external fun nativeStop()

    /** 三段 EQ 最终 dB（已含预设偏移并 clamp ±24，同 DspChain.configure 语义）；仅 dB 变化时重算系数 */
    external fun nativeSetEq(lowDb: Float, midDb: Float, highDb: Float)

    /** 增益 dB（gainLinear = 10^(dB/20)），热更新 */
    external fun nativeSetGain(gainDb: Float)

    /** 监听输出路由开关（native 侧 USAGE_ASSISTANCE_SONIFICATION 输出流，避开内录捕获范围防自激） */
    external fun nativeSetMonitor(enabled: Boolean)

    /** 清零双二阶滤波器状态（防爆音） */
    external fun nativeReset()

    /** Kotlin 读循环路径：对 buffer[0..count) 原地 DSP + 监听路由 + 可视化帧回调（JVM 线程调用） */
    external fun nativeFeed(buffer: FloatArray, count: Int)

    companion object {
        init {
            System.loadLibrary("micup_audio")
        }

        /** native 库不可用（ABI 缺失/极端 ROM）时返回 null，调用方回退纯 Kotlin 路径 */
        fun create(): NativeAudioEngine? = try {
            NativeAudioEngine()
        } catch (_: Throwable) {
            null
        }
    }
}
