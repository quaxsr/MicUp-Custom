package com.micup.custom.shizuku

import android.content.Context
import android.content.pm.PackageManager
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
        return ShizukuStatus(isInstalled(context), alive, authorized, version)
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
            val out = process.inputStream.bufferedReader().use { it.readText().trim() }
            val err = process.errorStream.bufferedReader().use { it.readText().trim() }
            val code = process.waitFor()
            ShellResult(
                code == 0, code,
                if (code == 0) out.ifBlank { "OK" } else err.ifBlank { out }.ifBlank { "exit=$code" }
            )
        }.getOrElse { ShellResult(false, -1, "命令执行异常：${it.message}") }
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
