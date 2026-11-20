package com.micup.custom.audio

import com.micup.custom.data.EffectPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * RBJ Audio EQ Cookbook 双二阶滤波器。
 * 单声道逐样本处理；运行中热更新系数时保留滤波状态，预设切换平滑无爆音。
 */
class BiquadFilter {

    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun process(buffer: FloatArray, count: Int) {
        for (i in 0 until count) buffer[i] = process(buffer[i])
    }

    private fun set(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        this.b0 = (b0 / a0).toFloat()
        this.b1 = (b1 / a0).toFloat()
        this.b2 = (b2 / a0).toFloat()
        this.a1 = (a1 / a0).toFloat()
        this.a2 = (a2 / a0).toFloat()
    }

    /** 低频搁架：f0 以下提升/衰减 gainDb */
    fun setLowShelf(f0: Double, gainDb: Double, fs: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / 2.0 * sqrt(2.0) // S = 1
        val k = 2.0 * sqrt(a) * alpha
        set(
            a * ((a + 1.0) - (a - 1.0) * cw + k),
            2.0 * a * ((a - 1.0) - (a + 1.0) * cw),
            a * ((a + 1.0) - (a - 1.0) * cw - k),
            (a + 1.0) + (a - 1.0) * cw + k,
            -2.0 * ((a - 1.0) + (a + 1.0) * cw),
            (a + 1.0) + (a - 1.0) * cw - k
        )
    }

    /** 高频搁架：f0 以上提升/衰减 gainDb */
    fun setHighShelf(f0: Double, gainDb: Double, fs: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / 2.0 * sqrt(2.0) // S = 1
        val k = 2.0 * sqrt(a) * alpha
        set(
            a * ((a + 1.0) + (a - 1.0) * cw + k),
            -2.0 * a * ((a - 1.0) + (a + 1.0) * cw),
            a * ((a + 1.0) + (a - 1.0) * cw - k),
            (a + 1.0) - (a - 1.0) * cw + k,
            2.0 * ((a - 1.0) - (a + 1.0) * cw),
            (a + 1.0) - (a - 1.0) * cw - k
        )
    }

    /** 峰值均衡：f0 处提升/衰减 gainDb，Q 控制带宽 */
    fun setPeaking(f0: Double, gainDb: Double, fs: Double, q: Double = 1.1) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / (2.0 * q)
        set(
            1.0 + alpha * a,
            -2.0 * cw,
            1.0 - alpha * a,
            1.0 + alpha / a,
            -2.0 * cw,
            1.0 - alpha / a
        )
    }
}

/** 处理链：增益 → 三段 EQ → 软限幅。参数可实时热更新。 */
class DspChain(private val sampleRate: Int) {

    private val lowShelf = BiquadFilter()
    private val midPeak = BiquadFilter()
    private val highShelf = BiquadFilter()

    private var lowShelfDb = Float.NaN
    private var midPeakDb = Float.NaN
    private var highShelfDb = Float.NaN
    private var gainLinear = 1f

    fun configure(preset: EffectPreset, eqLowDb: Float, eqMidDb: Float, eqHighDb: Float, gainDb: Float) {
        val low = (preset.low + eqLowDb).coerceIn(-24f, 24f)
        val mid = (preset.mid + eqMidDb).coerceIn(-24f, 24f)
        val high = (preset.high + eqHighDb).coerceIn(-24f, 24f)
        if (low != lowShelfDb) {
            lowShelf.setLowShelf(120.0, low.toDouble(), sampleRate.toDouble())
            lowShelfDb = low
        }
        if (mid != midPeakDb) {
            midPeak.setPeaking(1200.0, mid.toDouble(), sampleRate.toDouble(), 1.1)
            midPeakDb = mid
        }
        if (high != highShelfDb) {
            highShelf.setHighShelf(6000.0, high.toDouble(), sampleRate.toDouble())
            highShelfDb = high
        }
        gainLinear = 10f.pow(gainDb / 20f)
    }

    fun reset() {
        lowShelf.reset(); midPeak.reset(); highShelf.reset()
    }

    fun process(buffer: FloatArray, count: Int) {
        lowShelf.process(buffer, count)
        midPeak.process(buffer, count)
        highShelf.process(buffer, count)
        for (i in 0 until count) {
            buffer[i] = softLimit(buffer[i] * gainLinear)
        }
    }

    /** 连续可导的软限幅：|v| <= 0.5 线性直通，超出部分 tanh 压缩，避免硬削波毛刺 */
    private fun softLimit(v: Float): Float {
        val a = abs(v)
        if (a <= 0.5f) return v
        val sign = if (v > 0f) 1f else -1f
        return sign * (0.5f + 0.5f * tanh((a - 0.5f) * 2f))
    }
}

/** 简易 radix-2 FFT（实时频谱可视化用） */
class Fft(private val size: Int) {

    private val rev = IntArray(size)
    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)

    init {
        var bits = 0
        while ((1 shl bits) < size) bits++
        for (i in 0 until size) {
            var r = 0
            for (b in 0 until bits) {
                if (i and (1 shl b) != 0) r = r or (1 shl (bits - 1 - b))
            }
            rev[i] = r
        }
        for (i in 0 until size / 2) {
            val ang = -2.0 * PI * i / size
            cosTable[i] = cos(ang)
            sinTable[i] = sin(ang)
        }
    }

    fun magnitude(input: FloatArray, out: FloatArray) {
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        for (i in 0 until size) re[rev[i]] = input[i].toDouble()
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var base = 0
            while (base < size) {
                for (j in 0 until half) {
                    val k = j * step
                    val wr = cosTable[k]; val wi = sinTable[k]
                    val xr = re[base + j + half]; val xi = im[base + j + half]
                    val tr = xr * wr - xi * wi
                    val ti = xr * wi + xi * wr
                    re[base + j + half] = re[base + j] - tr
                    im[base + j + half] = im[base + j] - ti
                    re[base + j] += tr
                    im[base + j] += ti
                }
                base += len
            }
            len = len shl 1
        }
        for (i in out.indices) {
            if (i < size / 2) out[i] = sqrt(re[i] * re[i] + im[i] * im[i]).toFloat()
        }
    }
}

/** RMS 电平转 dBFS */
fun rmsToDb(rms: Float): Float = if (rms > 1e-6f) 20f * log10(rms) else -120f
