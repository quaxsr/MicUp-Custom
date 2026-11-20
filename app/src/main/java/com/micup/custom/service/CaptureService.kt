package com.micup.custom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.micup.custom.MainActivity
import com.micup.custom.R
import com.micup.custom.audio.AudioEngine
import com.micup.custom.audio.EngineBus
import com.micup.custom.audio.EngineState
import com.micup.custom.data.CaptureSourceMode

/**
 * 前台采集服务：持有 MediaProjection 与引擎。
 * Android 14+ 必须先以 mediaProjection|microphone 类型进入前台，再 getMediaProjection。
 */
class CaptureService : Service() {

    private var engine: AudioEngine? = null
    private var projection: MediaProjection? = null

    @Volatile
    private var shuttingDown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_START -> handleStart(intent)
            else -> {
                // 系统重建且无有效任务：进入前台后立即退出
                createChannel()
                startForeground(NOTIF_ID, buildNotification())
                shutdown()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        shuttingDown = false
        createChannel()
        val modeName = intent.getStringExtra(EXTRA_MODE) ?: CaptureSourceMode.MICROPHONE.name
        val mode = runCatching { CaptureSourceMode.valueOf(modeName) }.getOrDefault(CaptureSourceMode.MICROPHONE)
        val micGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (mode == CaptureSourceMode.MICROPHONE && !micGranted) {
            EngineBus.state.value = EngineState.Error("缺少麦克风权限，无法启动采集")
            startForeground(NOTIF_ID, buildNotification())
            shutdown()
            return
        }

        when {
            Build.VERSION.SDK_INT >= 30 -> {
                var type = 0
                if (mode == CaptureSourceMode.MICROPHONE) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(NOTIF_ID, buildNotification(), type)
            }
            Build.VERSION.SDK_INT == 29 -> {
                val type = if (mode == CaptureSourceMode.MICROPHONE) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (type == 0) startForeground(NOTIF_ID, buildNotification())
                else startForeground(NOTIF_ID, buildNotification(), type)
            }
            else -> startForeground(NOTIF_ID, buildNotification())
        }

        try {
            val mp = if (mode == CaptureSourceMode.MICROPHONE) {
                null
            } else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val data = projectionData(intent) ?: throw IllegalStateException("投屏授权数据缺失")
                mpm.getMediaProjection(code, data)
            }
            projection = mp
            mp?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    shutdown()
                }
            }, Handler(Looper.getMainLooper()))

            val engine = AudioEngine(mp, mode) { shutdown() }
            this.engine = engine
            if (!engine.start()) shutdown()
        } catch (t: Throwable) {
            EngineBus.state.value = EngineState.Error("启动失败：${t.message ?: t.javaClass.simpleName}")
            shutdown()
        }
    }

    private fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        engine?.stop()
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.notif_action_stop), stopPi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun projectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_PROJECTION, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION)
        }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        try { projection?.stop() } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.micup.custom.action.START"
        const val ACTION_STOP = "com.micup.custom.action.STOP"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION = "extra_projection"
        private const val CHANNEL_ID = "micup_capture"
        private const val NOTIF_ID = 1001
    }
}
