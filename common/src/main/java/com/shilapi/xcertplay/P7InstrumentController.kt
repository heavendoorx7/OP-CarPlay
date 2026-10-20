package com.shilapi.xcertplay

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Starts only the bundled fixed-purpose shell worker. CarPlay's transport is never restarted here. */
internal class P7InstrumentController(
    private val activity: Activity,
    private val update: (String)->Unit,
) : AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val work=Executors.newSingleThreadExecutor()
    @Volatile private var closed=false
    @Volatile private var socket: Socket?=null
    private var enabled=true
    private var session=false
    private var busy=false
    private var bound=false
    @Volatile private var token=0
    private var nextAttempt=0L
    private val requestCode=134001
    var status: String="等待 CarPlay 连接"
        private set
    private val userService=Shizuku.UserServiceArgs(ComponentName(activity,InstrumentStartService::class.java))
        .tag("op.p7.instrument.v1").processNameSuffix("instrument_start")
        .daemon(false).debuggable(false).version(1349)
    private val timeout=Runnable {
        runCatching{socket?.close()}
        finish("启动等待超时；可在仪表导航中重试授权")
    }
    private val permission=Shizuku.OnRequestPermissionResultListener { code,result->
        if(!closed && busy && code==requestCode) {
            if(result==PackageManager.PERMISSION_GRANTED)bindShizuku()
            else finish("尚未允许 OP 使用 Shizuku")
        }
    }
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?) {
            if(closed || !busy || !bound)return
            val generation=token
            work.execute {
                val code=runCatching{IInstrumentStartService.Stub.asInterface(binder).start()}.getOrDefault(-1)
                val message=if(code==0) awaitWorker() else "Shizuku 未能启动仪表通道，请确认使用 shell 模式"
                main.post{if(!closed && generation==token)finish(message)}
            }
        }
        override fun onServiceDisconnected(name:ComponentName?) {
            if(!closed && busy)finish("Shizuku 启动服务已断开")
        }
    }
    private val poll=object:Runnable {
        override fun run() {
            if(closed)return
            if(enabled && !busy) {
                work.execute {
                    val current=readWorker()
                    main.post {
                        if(closed || busy || !enabled)return@post
                        if(current!=null)show(current)
                        else if(session && SystemClock.elapsedRealtime()>=nextAttempt) {
                            nextAttempt=SystemClock.elapsedRealtime()+30000
                            if(shizukuGranted())startShizuku(false) else startAdb(false)
                        }
                    }
                }
            }
            main.postDelayed(this,3000)
        }
    }

    init {
        Shizuku.addRequestPermissionResultListener(permission)
        main.post(poll)
    }
    fun setEnabled(value:Boolean) {
        if(enabled==value)return
        enabled=value
        if(!value) {
            token++;busy=false;runCatching{socket?.close()};releaseShizuku()
            main.removeCallbacks(timeout);show("仪表导航已关闭")
        } else {
            nextAttempt=0
            show(if(session) "CarPlay 已连接，正在检查仪表通道…" else "等待 CarPlay 连接")
            main.removeCallbacks(poll);main.post(poll)
        }
    }
    fun session(value:Boolean) {
        session=value
        if(!value && enabled)show("等待 CarPlay 连接")
        if(value){
            if(enabled && status=="等待 CarPlay 连接")show("CarPlay 已连接，正在检查仪表通道…")
            main.removeCallbacks(poll);main.post(poll)
        }
    }
    private fun show(message:String){
        // Fixed lifecycle/status text only; never log credentials or navigation data.
        if(status!=message)Log.i("OPInstrumentStart",message)
        status=message;update(message)
    }

    fun requestAdb() { if(!enabled)show("请先开启仪表导航并保存，再启动通道") else startAdb(true) }
    private fun startAdb(interactive:Boolean) {
        if(closed || busy || !enabled)return
        if(!P7InstrumentCommand.supported() || activity.packageName!=P7InstrumentCommand.PACKAGE) {
            show("仪表导航仅适配老 P7 Android 9");return
        }
        busy=true;val generation=++token
        show("正在连接车机本机 ADB…")
        main.postDelayed(timeout,if(interactive)75000 else 12000)
        work.execute {
            val message=try {launchAdb(interactive,generation)} catch(_:Exception) {"仪表启动失败，可在设置中重试"}
            main.post{if(!closed && generation==token)finish(message)}
        }
    }
    private fun launchAdb(interactive:Boolean,generation:Int):String {
        val identity=try { LocalAdbIdentity() } catch (_:Exception) {
            return "本机 ADB 身份读取失败；可使用已运行的 Shizuku 启动仪表"
        }
        for(port in intArrayOf(5050,5555)) {
            if(closed || generation!=token)return "已停止仪表启动"
            val s=Socket();socket=s
            try {
                s.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127,0,0,1)),port),1500)
                s.soTimeout=4000
                val adb=LocalAdbProtocol(s.getInputStream(),s.getOutputStream(),identity)
                adb.connect {
                    if(!interactive)throw AuthorizationRequired()
                    s.soTimeout=55000
                    main.post{if(!closed && generation==token)show("请在车机调试弹窗中允许 OP；之后会自动启动仪表")}
                }
                s.soTimeout=5000
                if(adb.execute(LocalAdbProtocol.UID_ARGS).output.trim()!="2000")
                    return "仪表通道需要已开启的 shell ADB"
                if(closed || generation!=token)return "已停止仪表启动"
                val result=adb.execute(P7InstrumentCommand.startArgs)
                if(result.exit!=0)return "系统未能启动仪表通道"
                return awaitWorker()
            } catch(_:AuthorizationRequired) {
                return "需要一次授权：点击“本机 ADB 启动”或“通过 Shizuku 启动”"
            } catch(_:java.net.SocketTimeoutException) {
                if(interactive)return "等待调试授权超时，请允许 OP 后重试"
            } catch(_:Exception) { }
            finally {runCatching{s.close()};if(socket===s)socket=null}
        }
        return "本机 ADB 未开启；可使用已运行的 Shizuku 或离线安装工具启动"
    }
    private class AuthorizationRequired:Exception()

    fun requestShizuku() { if(!enabled)show("请先开启仪表导航并保存，再启动通道") else startShizuku(true) }
    private fun shizukuGranted()=runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    private fun startShizuku(interactive:Boolean) {
        if(closed || busy || !enabled)return
        if(!P7InstrumentCommand.supported()){show("仪表导航仅适配老 P7 Android 9");return}
        if(!Shizuku.pingBinder()){show("请先启动 Shizuku，再点此按钮");return}
        busy=true;token++
        main.postDelayed(timeout,60000)
        try {
            if(shizukuGranted())bindShizuku()
            else if(interactive) {
                show("请允许 OP 使用 Shizuku")
                Shizuku.requestPermission(requestCode)
            } else finish("需要先允许 OP 使用 Shizuku")
        } catch(_:Exception){finish("无法连接 Shizuku")}
    }
    private fun bindShizuku() {
        if(closed || !busy || bound)return
        bound=true;show("正在通过 Shizuku 启动仪表…")
        try{Shizuku.bindUserService(userService,connection)}
        catch(_:Exception){finish("Shizuku 启动失败")}
    }
    private fun releaseShizuku(){
        if(bound){bound=false;runCatching{Shizuku.unbindUserService(userService,connection,true)}}
    }
    private fun awaitWorker():String {
        repeat(16) {
            if(closed)return "已退出"
            readWorker()?.let{return it}
            Thread.sleep(250)
        }
        return "未收到仪表通道响应；CarPlay 主屏仍可正常使用"
    }
    private fun readWorker():String? {
        val local=LocalSocket()
        return try {
            local.connect(LocalSocketAddress(P7InstrumentCommand.CONTROL_SOCKET,LocalSocketAddress.Namespace.ABSTRACT))
            if(local.peerCredentials.uid!=2000)return null
            local.soTimeout=1500
            val data=JSONObject(DataInputStream(local.inputStream).readUTF())
            if(data.optInt("protocol")!=1 || data.optInt("uid")!=2000)return null
            when(data.optString("state")) {
                "submitting" -> "仪表通道运行中 · ${data.optInt("width")}×${data.optInt("height")}" +
                    when(data.optInt("laneState")) {
                        1 -> "\n原车车道提示：准备隐藏"
                        2 -> "\n原车车道提示：已提交隐藏（${data.optLong("laneHideCalls")} 次）"
                        3 -> "\n原车车道提示：恢复请求未确认"
                        4 -> "\n原车车道提示：接口不可用，保留原车显示"
                        5 -> "\n原车车道提示：原车当前未提供可显示的车道条"
                        6 -> "\n原车车道提示：已交回原车，等待原车更新"
                        else -> ""
                    }
                "waiting_request" -> "已连接；请让原车仪表显示导航卡片"
                "native_pause_unavailable" -> "原车小地图暂未让出通道，已保留原车画面；主屏不受影响"
                "waiting_map" -> "已连接；等待 iPhone 独立地图"
                "waiting_app","starting" -> "仪表通道正在启动"
                else -> null
            }
        } catch(_:Exception){null} finally {runCatching{local.close()}}
    }
    private fun finish(message:String) {
        busy=false;token++;main.removeCallbacks(timeout);releaseShizuku()
        nextAttempt=SystemClock.elapsedRealtime()+30000
        if(!closed)show(message)
    }
    override fun close() {
        closed=true;token++;busy=false;main.removeCallbacksAndMessages(null)
        runCatching{socket?.close()};releaseShizuku()
        Shizuku.removeRequestPermissionResultListener(permission);work.shutdownNow()
    }
}
