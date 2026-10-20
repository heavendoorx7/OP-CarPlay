package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import java.lang.reflect.InvocationTargetException

/** An opt-in Android 9 foreground service. It never launches a CarPlay Activity. */
class NativeHotspotService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var api: NativeHotspotApi
    private val recovery = HotspotRecovery()
    @Volatile private var closed = false
    private var generation = 0L
    private var lastTrigger = -60_000L
    private var receiverRegistered = false
    private var lastState = -1
    private var lastReady = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) trigger("wake")
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("op-hotspot").apply { start() }
        worker = Handler(thread.looper)
        api = NativeHotspotApi(applicationContext)
    }

    // This service is rejected on every OS except Android 9, before startForeground.
    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!NativeHotspotPreferences.allowed(this)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "原车热点自动恢复", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1329, Intent(this, OPCarPlayActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(1329, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("OP 原车热点自动恢复")
            .setContentText("开机、唤醒时检查；可在 OP 设置关闭，退出应用后暂停")
            .setContentIntent(open).setOngoing(true).build())
        if (!receiverRegistered) {
            // Service is strictly API 28/E28 gated; SCREEN_ON is a protected system broadcast.
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            receiverRegistered = true
        }
        val reason = intent?.getStringExtra("reason").takeIf { it in REASONS } ?: "service_restart"
        trigger(reason)
        return START_STICKY
    }

    private fun trigger(reason: String) {
        worker.post {
            if (closed || !NativeHotspotPreferences.allowed(this)) return@post
            val now = SystemClock.elapsedRealtime()
            // Mode handoffs/repeated SCREEN_ON cannot create overlapping retry loops.
            if (recovery.isActive && reason != "user_retry" && reason != "settings_saved") return@post
            if (now - lastTrigger < 1_500) return@post
            lastTrigger = now
            generation++
            worker.removeCallbacks(poll)
            recovery.begin(now)
            NativeHotspotJournal.record(this, reason)
            worker.post(poll)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            if (!NativeHotspotPreferences.allowed(this@NativeHotspotService)) {
                recovery.cancel(); stopSelf(); return
            }
            if (!Settings.System.canWrite(this@NativeHotspotService)) {
                NativeHotspotJournal.record(this@NativeHotspotService, "permission_denied")
                recovery.cancel(); stopSelf(); return
            }
            var phase = "prepare"
            try {
                api.prepare()
                phase = "state"
                lastState = api.state()
                phase = "interface"
                lastReady = lastState == 13 && api.interfaceReady()
                when (recovery.step(SystemClock.elapsedRealtime(), !closed && NativeHotspotPreferences.allowed(this@NativeHotspotService), lastState, lastReady)) {
                    HotspotRecovery.Step.START -> {
                        val ticket = generation
                        NativeHotspotJournal.record(this@NativeHotspotService, "start_request", lastState, lastReady, recovery.attempts)
                        if (closed || !NativeHotspotPreferences.allowed(this@NativeHotspotService)) return
                        phase = "start"
                        api.start(worker) { code ->
                            if (!closed && recovery.isActive && ticket == generation && NativeHotspotPreferences.allowed(this@NativeHotspotService)) {
                                NativeHotspotJournal.record(this@NativeHotspotService,
                                    if (code == 0) "callback_ok" else "callback_failed", lastState, lastReady, recovery.attempts, code)
                            }
                        }
                    }
                    HotspotRecovery.Step.READY -> {
                        NativeHotspotJournal.record(this@NativeHotspotService, "ready", lastState, true, recovery.attempts)
                        return
                    }
                    HotspotRecovery.Step.EXPIRED -> {
                        NativeHotspotJournal.record(this@NativeHotspotService, "expired", lastState, lastReady, recovery.attempts)
                        return
                    }
                    HotspotRecovery.Step.CANCELLED -> return
                    HotspotRecovery.Step.WAIT -> Unit
                }
                worker.postDelayed(this, 1_000)
            } catch (error: Exception) {
                var cause: Throwable = error
                while (cause is InvocationTargetException && cause.targetException != null) cause = cause.targetException
                val kind = when (cause) {
                    is SecurityException -> "denied"
                    is NoSuchMethodException -> "missing_method"
                    is NoSuchFieldException -> "missing_field"
                    is ClassNotFoundException -> "class_missing"
                    is IllegalAccessException -> "access_denied"
                    is IllegalArgumentException -> "bad_argument"
                    is android.os.RemoteException -> "remote_error"
                    else -> "other"
                }
                NativeHotspotJournal.record(this@NativeHotspotService,
                    if (cause is SecurityException) "api_denied" else "api_unavailable", lastState, lastReady, recovery.attempts, detail = "${phase}_${kind}")
                recovery.cancel()
            }
        }
    }

    override fun onDestroy() {
        closed = true
        worker.removeCallbacksAndMessages(null)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        // Keep the last result in the journal. It is needed after a reboot or ADB loss.
        thread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "op_native_hotspot"
        private val REASONS = setOf("app_open", "boot", "settings_saved", "user_retry", "wake", "service_restart")

        fun reconcile(context: Context, reason: String) {
            if (!NativeHotspotPreferences.allowed(context)) {
                context.stopService(Intent(context, NativeHotspotService::class.java))
                return
            }
            try {
                context.startForegroundService(Intent(context, NativeHotspotService::class.java).putExtra("reason", reason))
            } catch (_: RuntimeException) {
                NativeHotspotJournal.record(context, "background_start_denied")
            }
        }

        fun onAppOpen(context: Context) {
            NativeHotspotPreferences.resume(context)
            NativeHotspotJournal.record(context, "app_open")
            reconcile(context, "app_open")
        }

        fun onBoot(context: Context) {
            NativeHotspotPreferences.resume(context)
            NativeHotspotJournal.record(context, "boot")
            reconcile(context, "boot")
        }

        fun pause(context: Context) {
            NativeHotspotPreferences.pause(context)
            context.stopService(Intent(context, NativeHotspotService::class.java))
            NativeHotspotJournal.record(context, "paused")
        }
    }
}
