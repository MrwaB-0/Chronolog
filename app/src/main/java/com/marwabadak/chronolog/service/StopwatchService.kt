package com.marwabadak.chronolog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.marwabadak.chronolog.MainActivity
import com.marwabadak.chronolog.R
import com.marwabadak.chronolog.core.MeasurementSession
import com.marwabadak.chronolog.core.RecordStore
import com.marwabadak.chronolog.core.TickPlayer
import com.marwabadak.chronolog.core.TimeFormat

/**
 * 측정 중인 스톱워치를 화면과 분리해 유지하는 포그라운드 서비스.
 *
 * - 알림에 현재 회차, 시작 시각, 실시간 경과 시간을 표시한다.
 * - 알림 액션 "종료 및 저장" 으로 앱을 열지 않고도 측정을 끝내고 기록을 저장한다.
 * - 초침 소리 스케줄링을 서비스가 담당하므로 앱이 백그라운드로 내려가거나 화면이 꺼져도 계속 들린다.
 *   (소리는 사용자가 켠 경우에만 재생한다)
 * - 화면이 꺼진 동안에도 타이머가 정확히 흐르도록 부분 wake lock 을 측정 중에만 잡는다.
 */
class StopwatchService : Service(), MeasurementSession.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tickPlayer: TickPlayer
    private lateinit var notificationManager: NotificationManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var ticking = false
    private var lastTickedSecond = -1L
    private var foreground = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!MeasurementSession.isRunning) {
                ticking = false
                return
            }
            val elapsed = MeasurementSession.elapsedMs()
            val second = elapsed / 1_000L
            if (second != lastTickedSecond) {
                lastTickedSecond = second
                if (RecordStore(this@StopwatchService).soundEnabled) {
                    tickPlayer.tick()
                }
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
            }
            handler.postDelayed(this, 120L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        tickPlayer = TickPlayer()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        MeasurementSession.addListener(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SAVE -> {
                MeasurementSession.stopAndSave(this)
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            else -> Unit
        }

        if (!MeasurementSession.isRunning) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }
        startAsForeground()
        beginTicking()
        return START_NOT_STICKY
    }

    override fun onMeasurementChanged() {
        if (!MeasurementSession.isRunning) {
            stopSelfCleanly()
            return
        }
        lastTickedSecond = -1L
        if (!ticking) beginTicking()
        if (foreground) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        MeasurementSession.removeListener(this)
        handler.removeCallbacks(tickRunnable)
        ticking = false
        tickPlayer.release()
        releaseWakeLock()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 내부 동작

    private fun startAsForeground() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        foreground = true
        acquireWakeLock()
    }

    private fun beginTicking() {
        if (ticking) return
        ticking = true
        lastTickedSecond = -1L
        handler.post(tickRunnable)
    }

    private fun stopSelfCleanly() {
        handler.removeCallbacks(tickRunnable)
        ticking = false
        releaseWakeLock()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        try {
            val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            // 안전 장치로 12시간 뒤 자동 해제한다.
            lock.acquire(12L * 60L * 60L * 1_000L)
            wakeLock = lock
        } catch (t: Throwable) {
            wakeLock = null
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (t: Throwable) {
            // 이미 해제된 경우는 무시한다.
        }
        wakeLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val index = MeasurementSession.index
        val elapsed = MeasurementSession.elapsedMs()
        val startText = TimeFormat.timeHms(MeasurementSession.startEpochMs)
        val body = getString(
            R.string.notification_text,
            TimeFormat.elapsedHmsShort(elapsed),
            startText
        )

        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, StopwatchService::class.java).setAction(ACTION_STOP_SAVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stopwatch)
            .setContentTitle(getString(R.string.notification_title, index))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_action_open), openIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        const val ACTION_START = "com.marwabadak.chronolog.action.START"
        const val ACTION_STOP = "com.marwabadak.chronolog.action.STOP"
        const val ACTION_STOP_SAVE = "com.marwabadak.chronolog.action.STOP_SAVE"

        private const val CHANNEL_ID = "stopwatch_running"
        private const val NOTIFICATION_ID = 4102
        private const val REQUEST_OPEN = 4200
        private const val REQUEST_STOP = 4201
        private const val WAKE_LOCK_TAG = "StudyStopwatch::Timing"

        /** 측정 시작과 함께 포그라운드 서비스를 올린다. */
        fun start(context: Context) {
            val intent = Intent(context, StopwatchService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                // 백그라운드 시작 제한 등으로 실패해도 측정 자체는 계속 유지된다.
            }
        }

        /** 서비스를 정리한다. (측정 세션은 호출한 쪽에서 이미 정지했다고 가정) */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, StopwatchService::class.java))
            } catch (t: Throwable) {
                // 이미 종료된 경우는 무시한다.
            }
        }
    }
}
