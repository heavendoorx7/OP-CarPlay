package com.shilapi.xcertplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/** User initiated loopback-only initialization. Never starts or reconfigures adbd. */
internal class LocalAdbHotspotAuthorization(
    private val activity: Activity,
    private val update: (String, Boolean) -> Unit,
    private val onChecked: () -> Unit,
) : DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    private var busy = false
    @Volatile private var generation = 0

    init { (activity as? LifecycleOwner)?.lifecycle?.addObserver(this) }

    fun request() {
        if (closed || busy) return
        if (Settings.System.canWrite(activity)) { update("已授权，无需重复操作。", false); onChecked(); return }
        if (!NativeHotspotPreferences.supported()) { update("此功能仅针对老款 P7 的 Android 9 系统。", false); return }
        if (android.os.Process.myUid() / 100000 != 0 || activity.packageName != HotspotGrantCommand.PACKAGE) {
            update("请在车机主用户中使用正式 OP CarPlay。", false); return
        }
        busy = true
        val token = ++generation
        update("正在检查本机 ADB（5050／5555）…", true)
        val timeout = Runnable {
            if (!closed && busy && generation == token) {
                runCatching { socket?.close() }
                finish("授权等待超时。请检查车机的调试确认提示，再点一次授权。")
            }
        }
        main.postDelayed(timeout, 75_000)
        worker.execute {
            val message = authorize(token)
            main.post {
                if (closed || !busy || generation != token) return@post
                main.removeCallbacks(timeout)
                if (message == null) verify(token, 0) else finish(message)
            }
        }
    }

    private fun authorize(token: Int): String? {
        var identity: LocalAdbIdentity? = null
        var reachedAdb = false
        var waitingForUser = false
        var failure = "本机 5050／5555 均未能连接。请确认车机 ADB 已开启；OP 不能自行开启关闭的调试服务。"
        for (port in intArrayOf(5050, 5555)) {
            if (!active(token)) return "已取消授权。"
            val s = Socket()
            socket = s
            try {
                if (!active(token)) return "已取消授权。"
                // Literal loopback address only: no DNS, hotspot subnet or remote server.
                s.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 1500)
                s.soTimeout = 4000
                val localIdentity = identity ?: LocalAdbIdentity().also { identity = it }
                val adb = LocalAdbProtocol(s.getInputStream(), s.getOutputStream(), localIdentity)
                adb.connect {
                    reachedAdb = true; waitingForUser = true
                    s.soTimeout = 55_000
                    main.post {
                        if (!closed && busy && generation == token) update("请在车机的 USB／ADB 调试弹窗中允许 OP 的本机连接，随后会自动继续。", true)
                    }
                }
                if (!active(token)) return "已取消授权。"
                reachedAdb = true
                s.soTimeout = 5000
                val uid = adb.execute(LocalAdbProtocol.UID_ARGS)
                if (uid.exit != 0 || uid.output.trim() !in setOf("0", "2000")) return "已连接，但 ADB 没有可用于授权的 shell 权限。"
                return when (HotspotGrantCommand.run { args ->
                    if (!active(token)) throw java.util.concurrent.CancellationException()
                    adb.execute(args)
                }) {
                    HotspotGrantCommand.OK -> null
                    HotspotGrantCommand.VERIFY_FAILED -> "命令已执行，但系统没有确认权限。请检查授权状态；不会把连接成功当成授权成功。"
                    else -> "ADB 已连接，但系统拒绝了 OP 的修改系统设置授权。"
                }
            } catch (_: SocketTimeoutException) {
                if (waitingForUser) return "ADB 已响应，但尚未允许 OP 的连接。请检查调试弹窗；若没有弹窗，当前系统可能不支持应用内确认。"
                failure = "本机端口未在规定时间内完成 ADB 握手，请检查调试服务状态。"
            } catch (_: LocalAdbProtocol.ProtocolError) {
                failure = "本机端口响应不符合所需的 ADB 协议，尚未完成授权。"
            } catch (_: java.security.GeneralSecurityException) {
                return "无法建立 OP 的本机 ADB 安全密钥，请改用 Shizuku 或电脑授权。"
            } catch (_: Exception) {
                if (reachedAdb) return "ADB 授权连接已中断，请检查授权状态后重试。"
            } finally {
                runCatching { s.close() }
                if (socket === s) socket = null
            }
        }
        return failure
    }

    private fun active(token: Int) = !closed && generation == token && !Thread.currentThread().isInterrupted

    private fun verify(token: Int, attempt: Int) {
        if (closed || !busy || generation != token) return
        if (Settings.System.canWrite(activity)) finish("授权成功。返回开启自动热点选项，并保存设置。")
        else if (attempt < 7) main.postDelayed({ verify(token, attempt + 1) }, 250)
        else finish("ADB 命令已完成，但 OP 仍未获得权限；请改用其他授权方式检查。")
    }

    private fun finish(message: String) {
        busy = false; generation++
        if (!closed) { update(message, false); onChecked() }
    }

    fun close() {
        if (closed) return
        closed = true; busy = false; generation++
        main.removeCallbacksAndMessages(null)
        runCatching { socket?.close() }
        worker.shutdownNow()
        (activity as? LifecycleOwner)?.lifecycle?.removeObserver(this)
    }
    override fun onDestroy(owner: LifecycleOwner) = close()
}
