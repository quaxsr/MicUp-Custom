package com.micup.custom.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import rikka.shizuku.Shizuku

/** Shizuku 当前状态快照 */
data class ShizukuStatus(
    val installed: Boolean = false,
    val binderAlive: Boolean = false,
    val authorized: Boolean = false,
    val version: Int = -1
) {
    val ready: Boolean get() = binderAlive && authorized
}

/**
 * Shizuku 接入门面：
 * - 状态探测 / 授权请求（v13 标准 API）
 * - shell 命令执行（反射调用 Shizuku#newProcess，规避 API 细分版本编译期差异，运行期失败自动降级）
 */
object ShizukuGate {

    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    const val SUI_PACKAGE = "rikka.sui"
    const val REQUEST_CODE = 10086

    private val mainHandler = Handler(Looper.getMainLooper())

    // ————— Binder 死亡自动重连（unlink → 递增退避 relink，重试有上限） —————
    private const val RECONNECT_MAX_ATTEMPTS = 5
    private val RECONNECT_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var reconnectScheduled = false
    private var reconnectTask: Runnable? = null

    // ————— MIUI/HyperOS 检测与提示（非 Compose 通道：系统 Toast） —————
    private const val MIUI_SECURE_HINT = "检测到小米/澎湃OS：请前往开发者选项开启【USB调试（安全设置）】，否则 Shizuku 无法稳定运行"

    @Volatile
    private var miuiHintShown = false

    /**
     * 监听器配对记录：与 onChange 一一对应，避免半注册残留；
     * linked 标记当前是否已挂载到 Shizuku，重连 unlink/relink 时防重复注册与监听泄漏
     */
    private class BinderListenerPair(
        val onChange: () -> Unit,
        val received: Shizuku.OnBinderReceivedListener,
        val dead: Shizuku.OnBinderDeadListener
    ) {
        @Volatile
        var linked: Boolean = false
    }
    private val listenerPairs = mutableListOf<BinderListenerPair>()

    fun ping(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0) }.isSuccess ||
            runCatching { context.packageManager.getPackageInfo(SUI_PACKAGE, 0) }.isSuccess

    fun status(context: Context): ShizukuStatus {
        val alive = ping()
        val authorized = alive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val version = if (alive) runCatching { Shizuku.getVersion() }.getOrDefault(-1) else -1
        val snapshot = ShizukuStatus(isInstalled(context), alive, authorized, version)
        maybeShowMiuiSecureHint(context, snapshot)
        return snapshot
    }

    /**
     * MIUI/HyperOS 检测：Redmi/小米设备 MANUFACTURER 均为 Xiaomi；
     * 再反射读 ro.mi.os.version.name 增强（失败静默忽略）。
     */
    fun isMiuiLikeDevice(): Boolean {
        if (Build.MANUFACTURER?.equals("Xiaomi", ignoreCase = true) == true) return true
        return runCatching {
            val prop = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "ro.mi.os.version.name") as? String
            !prop.isNullOrBlank()
        }.getOrDefault(false)
    }

    /**
     * 小米系设备上 Shizuku 已安装但 binder 不通（典型为缺【USB调试（安全设置）】）：
     * 经系统 Toast 提示（每进程一次，主线程执行），不动任何 UI 文件。
     */
    private fun maybeShowMiuiSecureHint(context: Context, status: ShizukuStatus) {
        if (miuiHintShown || !isMiuiLikeDevice()) return
        if (!status.installed || status.binderAlive) return
        miuiHintShown = true
        val app = context.applicationContext
        val show = { runCatching { Toast.makeText(app, MIUI_SECURE_HINT, Toast.LENGTH_LONG).show() } }
        if (Looper.myLooper() == Looper.getMainLooper()) show() else mainHandler.post { show() }
    }

    /** 发起授权请求；返回 false 表示 Shizuku 服务不可用 */
    fun requestPermission(): Boolean =
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }.isSuccess

    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    /**
     * 注册 binder 到达+断开监听（sticky 回调）。
     * @return 成功返回 true，异常返回 false。
     */
    fun addBinderStateListener(onChange: () -> Unit): Boolean {
        var receivedRef: Shizuku.OnBinderReceivedListener? = null
        var pairAdded = false
        return runCatching {
            val received = Shizuku.OnBinderReceivedListener {
                reconnectAttempts = 0 // binder 回归即清零重连计数
                onChange()
            }
            receivedRef = received
            val dead = Shizuku.OnBinderDeadListener {
                onChange()
                handleBinderDead()
            }
            val pair = BinderListenerPair(onChange, received, dead)
            listenerPairs.add(pair)
            pairAdded = true
            Shizuku.addBinderReceivedListenerSticky(received, mainHandler)
            Shizuku.addBinderDeadListener(dead, mainHandler)
            pair.linked = true
        }.onFailure { e ->
            // 半途失败：真正注销已注册到 Shizuku 的 received 监听器，并移除配对记录，避免残留
            receivedRef?.let { runCatching { Shizuku.removeBinderReceivedListener(it) } }
            if (pairAdded) listenerPairs.removeLastOrNull()
        }.getOrNull() != null
    }

    /**
     * 移除 binder 状态监听。
     */
    fun removeBinderStateListener(onChange: () -> Unit) {
        runCatching {
            val index = listenerPairs.indexOfFirst { it.onChange == onChange }
            if (index >= 0) {
                val pair = listenerPairs.removeAt(index)
                try {
                    Shizuku.removeBinderReceivedListener(pair.received)
                } catch (e: Exception) {
                    // 忽略
                }
                try {
                    Shizuku.removeBinderDeadListener(pair.dead)
                } catch (e: Exception) {
                    // 忽略
                }
            }
            // 全部监听已移除：取消挂起的重连任务，避免持有失效回调
            if (listenerPairs.isEmpty()) cancelReconnect()
        }
    }

    private fun cancelReconnect() {
        reconnectTask?.let { mainHandler.removeCallbacks(it) }
        reconnectTask = null
        reconnectScheduled = false
        reconnectAttempts = 0
    }

    /**
     * Binder 死亡：先整体 unlink（防半注册残留与重复监听），再按递增退避自动重连；
     * 重连进度与结果均经 onChange → ShizukuGate.status() 既有通道刷新 UI 状态。
     */
    private fun handleBinderDead() {
        if (listenerPairs.isEmpty()) return
        listenerPairs.forEach { pair ->
            if (pair.linked) {
                runCatching { Shizuku.removeBinderReceivedListener(pair.received) }
                runCatching { Shizuku.removeBinderDeadListener(pair.dead) }
                pair.linked = false
            }
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
            // 重试耗尽：被动重挂监听，交回 Shizuku 自身 sticky 通知机制兑底，避免无限循环
            relinkPassively()
            return
        }
        reconnectScheduled = true
        val delay = RECONNECT_BACKOFF_MS[reconnectAttempts.coerceAtMost(RECONNECT_BACKOFF_MS.size - 1)]
        val task = Runnable {
            reconnectScheduled = false
            reconnectTask = null
            if (listenerPairs.isEmpty()) return@Runnable
            if (attemptReconnect()) {
                reconnectAttempts = 0
            } else {
                reconnectAttempts++
                scheduleReconnect()
            }
        }
        reconnectTask = task
        mainHandler.postDelayed(task, delay)
    }

    /** 单次重连：binder 活跃则重新挂载监听（relink）并刷新状态；返回是否恢复 */
    private fun attemptReconnect(): Boolean {
        if (!ping()) return false
        if (!relinkPairs()) return false
        listenerPairs.forEach { pair -> runCatching { pair.onChange() } }
        return true
    }

    /** 重挂所有未挂载的监听配对（linked 标记防重复注册/监听泄漏） */
    private fun relinkPairs(): Boolean =
        listenerPairs.all { pair ->
            if (pair.linked) {
                true
            } else {
                runCatching {
                    Shizuku.addBinderReceivedListenerSticky(pair.received, mainHandler)
                    Shizuku.addBinderDeadListener(pair.dead, mainHandler)
                    pair.linked = true
                    true
                }.getOrDefault(false)
            }
        }

    /** 重试耗尽后的被动兑底：重挂监听、清零计数，等待 Shizuku 自身 sticky 回调恢复 */
    private fun relinkPassively() {
        if (relinkPairs()) {
            reconnectAttempts = 0
            listenerPairs.forEach { pair -> runCatching { pair.onChange() } }
        }
    }

    data class ShellResult(val success: Boolean, val exitCode: Int, val message: String)

    /**
     * 以 shell 身份执行命令（经 Shizuku）。
     * 反射调用以避免对 Shizuku API 细分版本的编译期强依赖；不支持时返回可读错误并回退。
     */
    fun runShell(command: String): ShellResult {
        if (!ping()) return ShellResult(false, -1, "Shizuku 未连接")
        val process = runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        }.getOrElse {
            return ShellResult(false, -1, "当前 Shizuku 版本不支持执行命令")
        }
        return runCatching {
            val out = StringBuilder()
            val err = StringBuilder()
            val errorThread = Thread { process.errorStream.bufferedReader().use { it.readText().let { err.append(it) } } }
            errorThread.start()
            process.inputStream.bufferedReader().use { it.readText().let { out.append(it) } }
            errorThread.join()
            val done = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                process.destroy()
                return ShellResult(false, -1, "命令执行超时")
            }
            val exitCode = process.exitValue()
            ShellResult(
                exitCode == 0, if (exitCode == -2) -1 else exitCode,
                if (exitCode == 0) out.toString().trim().ifBlank { "OK" } else err.toString().trim().ifBlank { out.toString().trim() }.ifBlank { "exit=$exitCode" }
            )
        }.getOrElse { e ->
            process.destroy()
            ShellResult(false, -1, when (e) {
                is NoSuchMethodException -> "当前 Shizuku 版本不支持执行命令"
                is IllegalStateException -> "Shizuku 未连接或服务已断开"
                else -> "命令执行异常：${e.message}"
            })
        }
    }

    /**
     * 增强授权（shell 级预授权）：
     * 1) pm grant —— 免弹窗直接授予 RECORD_AUDIO；
     * 2) appops set PROJECT_MEDIA allow —— 多数 ROM 投屏授权弹窗自动通过。
     * 任一失败不影响标准授权流程，仅失去“免弹窗”加成。
     */
    fun applyEnhancement(packageName: String): ShellResult {
        val mic = runShell("pm grant $packageName android.permission.RECORD_AUDIO")
        val projection = runShell("appops set $packageName PROJECT_MEDIA allow")
        return if (mic.success || projection.success) {
            ShellResult(true, 0, listOf(mic.message, projection.message).joinToString("；"))
        } else {
            ShellResult(false, -1, projection.message)
        }
    }
}
