package com.micup.custom.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.Process
import com.micup.custom.data.CaptureSourceMode
import com.micup.custom.data.EffectPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 引擎实时参数（UI 热更新，逐块生效） */
data class EngineParams(
    val gainDb: Float = 0f,
    val preset: EffectPreset = EffectPreset.NATURAL,
    val eqLowDb: Float = 0f,
    val eqMidDb: Float = 0f,
    val eqHighDb: Float = 0f,
    val monitorEnabled: Boolean = true,
    val bufferMultiplier: Int = 4
)

/** 引擎状态机 */
sealed class EngineState {
    data object Idle : EngineState()
    data object Starting : EngineState()
    data class Running(val source: CaptureSourceMode, val sampleRate: Int) : EngineState()
    data class Error(val message: String) : EngineState()
}

/** 单帧可视化数据（约 23fps 推送） */
class EngineFrame(
    val wave: FloatArray,
    val spectrum: FloatArray,
    val peaks: FloatArray,
    val rmsDb: Float
)

/** UI ↔ 引擎 全局总线 */
object EngineBus {
    val params = MutableStateFlow(EngineParams())
    val state = MutableStateFlow<EngineState>(EngineState.Idle)
    val frame = MutableStateFlow<EngineFrame?>(null)
}

/**
 * 采集 → DSP → 路由 一体化实时引擎。
 * 独立高优先级线程跑实时环；监听输出走系统提示音通道（USAGE_ASSISTANCE_SONIFICATION），
 * 不在内录捕获范围（MEDIA/GAME/UNKNOWN）内，避免自激回授啸叫。
 */
class AudioEngine(
    private val projection: MediaProjection?, // 内录模式需要；麦克风模式传 null
    private val sourceMode: CaptureSourceMode,
    private val onExit: (() -> Unit)? = null
) {

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private val fft = Fft(FFT_SIZE)
    private val hann = FloatArray(FFT_SIZE) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / FFT_SIZE)).toFloat() }
    private val fftIn = FloatArray(FFT_SIZE)
    private val specRaw = FloatArray(FFT_SIZE / 2)
    private val rolling = FloatArray(FFT_SIZE)
    private val bars = FloatArray(SPEC_BARS)
    private val peaks = FloatArray(SPEC_BARS)
    private val chain = DspChain(SAMPLE_RATE)
    private var chunkCount = 0

    fun start(): Boolean {
        if (sourceMode != CaptureSourceMode.MICROPHONE && Build.VERSION.SDK_INT < 29) {
            EngineBus.state.value = EngineState.Error("系统内录需要 Android 10 及以上")
            return false
        }
        EngineBus.frame.value = null
        EngineBus.state.value = EngineState.Starting
        val ar = try {
            buildRecord()
        } catch (t: Throwable) {
            EngineBus.state.value = EngineState.Error("采集初始化失败：${t.message ?: t.javaClass.simpleName}")
            return false
        }
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            try { ar?.release() } catch (_: Throwable) {}
            EngineBus.state.value = EngineState.Error("AudioRecord 初始化失败（权限或设备被占用）")
            return false
        }
        record = ar
        chain.reset()
        running = true
        EngineBus.state.value = EngineState.Running(sourceMode, SAMPLE_RATE)
        thread = Thread({ loop() }, "MicUp-DSP").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        try { record?.stop() } catch (_: Throwable) {}
        try { track?.pause() } catch (_: Throwable) {}
        try { t?.join(1500) } catch (_: Throwable) {}
    }

    // ————— 采集源 —————

    private fun buildRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) {
            throw IllegalStateException("音频设备不支持当前采样配置")
        }
        val bufBytes = max(minBuf, CHUNK * 4) * EngineBus.params.value.bufferMultiplier
        return when (sourceMode) {
            CaptureSourceMode.MICROPHONE -> AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                bufBytes
            )
            else -> {
                val mp = projection ?: throw IllegalStateException("未取得系统投屏授权")
                val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setAudioPlaybackCaptureConfiguration(config)
                    .setBufferSizeInBytes(bufBytes)
                    .build()
            }
        }
    }

    // ————— 监听路由（懒创建，随开关热插拔） —————

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) {
            return null
        }
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf, CHUNK * 4) * 2)
            .build()
        if (at.state != AudioTrack.STATE_INITIALIZED) {
            try { at.release() } catch (_: Throwable) {}
            return null
        }
        at.play()
        track = at
        return at
    }

    private fun releaseTrack() {
        try { track?.pause() } catch (_: Throwable) {}
        try { track?.flush() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }

    // ————— 实时环 —————

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(CHUNK)
        var lastParams = EngineBus.params.value
        var consecutiveErrors = 0
        var exitError: String? = null
        while (running) {
            val p = EngineBus.params.value
            if (p != lastParams) {
                if (!p.monitorEnabled) releaseTrack()
                lastParams = p
            }
            val rec = record ?: break
            val n = try {
                rec.read(buf, 0, CHUNK, AudioRecord.READ_BLOCKING)
            } catch (t: Throwable) {
                exitError = "采集异常：${t.message ?: t.javaClass.simpleName}"
                break
            }
            if (n > 0) {
                consecutiveErrors = 0
                chain.configure(p.preset, p.eqLowDb, p.eqMidDb, p.eqHighDb, p.gainDb)
                chain.process(buf, n)
                if (p.monitorEnabled) {
                    val at = ensureTrack()
                    if (at != null) {
                        try { at.write(buf, 0, n, AudioTrack.WRITE_BLOCKING) } catch (_: Throwable) {}
                    }
                }
                pushVisual(buf, n)
            } else if (n == 0) {
                continue
            } else {
                if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                    try { rec.release() } catch (_: Throwable) {}
                    record = null
                    val newRec = try {
                        buildRecord()
                    } catch (t: Throwable) {
                        exitError = "音频设备重建失败：${t.message ?: t.javaClass.simpleName}"
                        break
                    }
                    if (newRec == null || newRec.state != AudioRecord.STATE_INITIALIZED) {
                        exitError = "音频设备重建失败：状态异常或初始化失败"
                        break
                    }
                    record = newRec
                    consecutiveErrors = 0
                    continue
                }
                consecutiveErrors++
                if (consecutiveErrors >= 5) {
                    exitError = "连续采集失败超过 5 次"
                    break
                }
            }
        }
        releaseAll()
        Handler(Looper.getMainLooper()).post {
            try { onExit?.invoke() } catch (_: Throwable) {}
        }
        val s = EngineBus.state.value
        if (s is EngineState.Running || s is EngineState.Starting) {
            EngineBus.state.value = if (exitError != null) EngineState.Error(exitError!!) else EngineState.Idle
        }
    }

    private fun pushVisual(buf: FloatArray, n: Int) {
        // 滚动窗口（供 FFT 与波形）
        if (n >= FFT_SIZE) {
            System.arraycopy(buf, n - FFT_SIZE, rolling, 0, FFT_SIZE)
        } else {
            System.arraycopy(rolling, n, rolling, 0, FFT_SIZE - n)
            System.arraycopy(buf, 0, rolling, FFT_SIZE - n, n)
        }
        // RMS 电平
        var acc = 0f
        for (i in 0 until n) acc += buf[i] * buf[i]
        val rms = sqrt(acc / n)
        chunkCount++
        if (chunkCount % 2 != 0) return // ~23fps，足够顺滑且省电
        // 频谱（加汉宁窗 + 对数分桶）
        for (i in 0 until FFT_SIZE) fftIn[i] = rolling[i] * hann[i]
        fft.magnitude(fftIn, specRaw)
        var maxBar = 1e-4f
        for (b in 0 until SPEC_BARS) {
            val lo = BAR_EDGES[b]
            val hi = max(BAR_EDGES[b + 1], lo + 1)
            var s = 0f
            for (k in lo until hi) s += specRaw[k]
            val avg = s / (hi - lo)
            bars[b] = avg
            if (avg > maxBar) maxBar = avg
        }
        for (b in 0 until SPEC_BARS) {
            val v = ((bars[b] / maxBar).pow(0.5f)).coerceIn(0f, 1f)
            bars[b] = v
            peaks[b] = max(v, peaks[b] * 0.90f)
        }
        // 波形（峰值归一化）
        val wave = FloatArray(WAVE_POINTS)
        var mx = 0.03f
        for (i in 0 until WAVE_POINTS) {
            val v = rolling[i * FFT_SIZE / WAVE_POINTS]
            wave[i] = v
            val a = abs(v)
            if (a > mx) mx = a
        }
        for (i in 0 until WAVE_POINTS) wave[i] = (wave[i] / mx).coerceIn(-1f, 1f)
        EngineBus.frame.value = EngineFrame(wave, bars.copyOf(), peaks.copyOf(), rmsToDb(rms))
    }

    private fun releaseAll() {
        releaseTrack()
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        record = null
        EngineBus.frame.value = null
        try { projection?.stop() } catch (_: Throwable) {}
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHUNK = 1024
        const val FFT_SIZE = 1024
        const val SPEC_BARS = 48
        const val WAVE_POINTS = 160

        /** 48 根频谱柱的对数频率分桶（40Hz ~ 20kHz） */
        val BAR_EDGES = IntArray(SPEC_BARS + 1).also { edges ->
            for (b in 0..SPEC_BARS) {
                val f = 40.0 * (20000.0 / 40.0).pow(b.toDouble() / SPEC_BARS)
                edges[b] = (f * FFT_SIZE / SAMPLE_RATE).roundToInt().coerceIn(0, FFT_SIZE / 2 - 1)
            }
            for (b in 1..SPEC_BARS) {
                if (edges[b] <= edges[b - 1]) edges[b] = edges[b - 1] + 1
            }
            for (b in 0..SPEC_BARS) {
                edges[b] = edges[b].coerceAtMost(FFT_SIZE / 2 - 1)
            }
        }
    }
}
