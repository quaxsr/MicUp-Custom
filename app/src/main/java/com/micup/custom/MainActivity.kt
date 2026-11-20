package com.micup.custom

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import com.micup.custom.audio.EngineBus
import com.micup.custom.audio.EngineParams
import com.micup.custom.audio.EngineState
import com.micup.custom.data.CaptureSourceMode
import com.micup.custom.data.MicUpSettings
import com.micup.custom.data.SettingsRepository
import com.micup.custom.service.CaptureService
import com.micup.custom.shizuku.ShizukuGate
import com.micup.custom.shizuku.ShizukuStatus
import com.micup.custom.ui.AuroraBackground
import com.micup.custom.ui.MainScreen
import com.micup.custom.ui.SettingsScreen
import com.micup.custom.ui.theme.MicUpTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MicUpRoot() }
    }
}

@Composable
private fun MicUpRoot() {
    val context = LocalContext.current
    val view = LocalView.current
    val repo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    var ui by remember { mutableStateOf(MicUpSettings()) }
    var previewing by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<CaptureSourceMode?>(null) }
    var shizuku by remember { mutableStateOf(ShizukuStatus()) }

    // 设置装载（rememberTuning 关闭时，可调参数启动即回默认）
    LaunchedEffect(Unit) {
        repo.settings.collect { s ->
            if (!previewing) {
                ui = if (s.rememberTuning) s
                else s.copy(gainDb = 0f, eqLowDb = 0f, eqMidDb = 0f, eqHighDb = 0f)
            }
        }
    }

    fun preview(s: MicUpSettings) {
        previewing = true
        ui = s
    }

    fun commit(s: MicUpSettings) {
        previewing = false
        ui = s
        scope.launch { repo.update(s) }
    }

    // Shizuku 状态轮询 + 授权结果监听
    LaunchedEffect(Unit) {
        while (true) {
            shizuku = ShizukuGate.status(context)
            delay(3000)
        }
    }
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ ->
            shizuku = ShizukuGate.status(context)
        }
        ShizukuGate.addPermissionListener(listener)
        onDispose { ShizukuGate.removePermissionListener(listener) }
    }

    // 引擎状态与可视化数据
    val engineState by EngineBus.state.collectAsState()
    val frame by EngineBus.frame.collectAsState()

    // UI 设置 → 引擎热更新
    LaunchedEffect(ui) {
        EngineBus.params.value = EngineParams(
            gainDb = ui.gainDb,
            preset = ui.preset,
            eqLowDb = ui.eqLowDb,
            eqMidDb = ui.eqMidDb,
            eqHighDb = ui.eqHighDb,
            monitorEnabled = ui.monitorEnabled,
            bufferMultiplier = ui.bufferPreset.multiplier
        )
    }

    fun projectionIntent(): Intent {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }

    fun launchCapture(mode: CaptureSourceMode, resultCode: Int, data: Intent?) {
        val intent = Intent(context, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_MODE, mode.name)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
        if (data != null) intent.putExtra(CaptureService.EXTRA_PROJECTION, data)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopCapture() {
        context.startService(
            Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            val mode = pendingMode ?: CaptureSourceMode.MICROPHONE
            pendingMode = null
            launchCapture(mode, 0, null)
        } else {
            EngineBus.state.value = EngineState.Error("未授予麦克风权限，无法进行麦克风采集")
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val mode = pendingMode
        pendingMode = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null && mode != null) {
            launchCapture(mode, result.resultCode, result.data)
        }
    }

    fun requestStart(mode: CaptureSourceMode) {
        when (mode) {
            CaptureSourceMode.MICROPHONE -> {
                pendingMode = null
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    launchCapture(mode, 0, null)
                } else {
                    pendingMode = CaptureSourceMode.MICROPHONE
                    val perms = if (Build.VERSION.SDK_INT >= 33) {
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    }
                    permissionLauncher.launch(perms)
                }
            }
            CaptureSourceMode.INTERNAL_PLAYBACK -> {
                pendingMode = mode
                projectionLauncher.launch(projectionIntent())
            }
            CaptureSourceMode.SHIZUKU_ENHANCED -> {
                if (!shizuku.ready) {
                    EngineBus.state.value = EngineState.Error("请先安装并运行 Shizuku，然后在采集源卡片完成授权")
                    return
                }
                pendingMode = mode
                scope.launch(Dispatchers.IO) {
                    ShizukuGate.applyEnhancement(context.packageName)
                    withContext(Dispatchers.Main) {
                        shizuku = ShizukuGate.status(context)
                        projectionLauncher.launch(projectionIntent())
                    }
                }
            }
        }
    }

    MicUpTheme(ui.themeMode, ui.materialStyle) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AuroraBackground(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                AnimatedContent(
                    targetState = showSettings,
                    transitionSpec = {
                        (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 4 } + fadeOut())
                    },
                    label = "nav"
                ) { show ->
                    if (show) {
                        SettingsScreen(
                            settings = ui,
                            onCommit = { commit(it) },
                            onBack = { showSettings = false }
                        )
                    } else {
                        MainScreen(
                            settings = ui,
                            onPreview = { preview(it) },
                            onCommit = { commit(it) },
                            engineState = engineState,
                            frame = frame,
                            shizuku = shizuku,
                            onStart = { requestStart(it) },
                            onStop = { stopCapture() },
                            onShizukuAuth = {
                                if (!ShizukuGate.requestPermission()) {
                                    EngineBus.state.value = EngineState.Error("Shizuku 未运行，请先启动 Shizuku 服务")
                                }
                            },
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    BackHandler(enabled = showSettings) { showSettings = false }

    // 屏幕常亮
    LaunchedEffect(ui.keepScreenOn) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            if (ui.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
