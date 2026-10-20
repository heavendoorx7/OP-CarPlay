package com.shilapi.xcertplay

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** User initiated authorization only; never participates in CarPlay or hotspot startup. */
internal class ShizukuHotspotAuthorization(
    private val activity: Activity,
    private val update: (String, Boolean) -> Unit,
    private val onChecked: () -> Unit,
) : DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var closed = false
    private var busy = false
    private var binding = false
    private var executing = false
    private var generation = 0
    private val requestCode = 133401
    private val args = Shizuku.UserServiceArgs(ComponentName(activity, HotspotGrantService::class.java))
        .tag("op.write-settings.v1").processNameSuffix("hotspot_permission")
        .daemon(false).debuggable(false).version(1334)
    private val timeout = Runnable { if (busy) finish("等待超时，请确认 Shizuku 仍在运行后重试。") }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (!closed && busy && code == requestCode) {
            if (result == PackageManager.PERMISSION_GRANTED) bind()
            else finish("未允许 OP 使用 Shizuku；没有修改热点权限。")
        }
    }
    private val deathListener = Shizuku.OnBinderDeadListener {
        main.post { if (!closed && busy) finish("Shizuku 已停止；请重新启动后检查授权结果。") }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed || !busy || !binding || executing) return
            if (binder == null || !binder.pingBinder()) { finish("Shizuku 授权服务不可用，请重试。"); return }
            executing = true
            val token = generation
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, 15_000)
            update("正在授予 OP 修改系统设置权限…", true)
            worker.execute {
                val result = runCatching { IHotspotGrantService.Stub.asInterface(binder).authorize() }.getOrDefault(-1)
                main.post {
                    if (closed || !busy || generation != token) return@post
                    if (result == HotspotGrantCommand.OK) verify(token, 0)
                    else finish(when (result) {
                        HotspotGrantCommand.TIMEOUT -> "系统授权命令超时，请检查后重试。"
                        HotspotGrantCommand.VERIFY_FAILED -> "系统没有确认授权；请使用电脑授权方式检查。"
                        else -> "授权未完成，系统或 Shizuku 拒绝了操作。"
                    })
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            if (!closed && busy) finish("授权服务已断开，请检查授权结果后重试。")
        }
    }

    init {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderDeadListener(deathListener)
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(this)
    }

    fun request() {
        if (closed || busy) return
        if (Settings.System.canWrite(activity)) { update("已授权，无需重复操作。", false); onChecked(); return }
        if (!NativeHotspotPreferences.supported()) { update("此功能仅针对老款 P7 的 Android 9 系统。", false); return }
        if (android.os.Process.myUid() / 100000 != 0) { update("请在车机主用户中授权。", false); return }
        if (!Shizuku.pingBinder()) { update("Shizuku 未运行或尚未连接。请先在 Shizuku 中确认“正在运行”，再重试。", false); return }
        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) { update("请使用 Shizuku 13 或更新版本。", false); return }
            busy = true; generation++
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
            else if (Shizuku.shouldShowRequestPermissionRationale()) finish("请在 Shizuku → 已授权应用中允许 OP CarPlay，再返回重试。")
            else {
                update("请在 Shizuku 弹窗中允许 OP CarPlay，随后会自动继续。", true)
                main.postDelayed(timeout, 60_000)
                Shizuku.requestPermission(requestCode)
            }
        } catch (_: Exception) { finish("无法连接 Shizuku，请确认其运行状态后重试。") }
    }

    private fun bind() {
        if (closed || !busy || binding) return
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 15_000)
        binding = true
        update("正在连接 Shizuku 授权服务…", true)
        try { Shizuku.bindUserService(args, connection) }
        catch (_: Exception) { finish("未能启动授权服务，请检查 Shizuku 后重试。") }
    }

    private fun verify(token: Int, attempt: Int) {
        if (closed || !busy || generation != token) return
        if (Settings.System.canWrite(activity)) finish("授权成功。返回开启自动热点选项，并保存设置。")
        else if (attempt < 7) main.postDelayed({ verify(token, attempt + 1) }, 250)
        else finish("命令已执行，但 OP 仍未获得权限；请使用电脑授权方式检查。")
    }

    private fun releaseService() {
        if (!binding) return
        binding = false; executing = false
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    private fun finish(message: String) {
        busy = false; generation++
        main.removeCallbacks(timeout)
        releaseService()
        if (!closed) { update(message, false); onChecked() }
    }

    fun close() {
        if (closed) return
        closed = true; busy = false; generation++
        main.removeCallbacksAndMessages(null)
        releaseService()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderDeadListener(deathListener)
        (activity as? LifecycleOwner)?.lifecycle?.removeObserver(this)
        worker.shutdownNow()
    }

    override fun onDestroy(owner: LifecycleOwner) = close()
}
