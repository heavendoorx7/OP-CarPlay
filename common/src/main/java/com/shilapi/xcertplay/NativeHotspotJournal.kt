package com.shilapi.xcertplay

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small device-local journal retained across service/process restarts and reboot.
 * Fixed vocabulary only: never stores SSID, password, IP, MAC, identifiers or payloads.
 */
internal object NativeHotspotJournal {
    const val FILE_NAME = "native-hotspot-status.json"
    private const val LIMIT = 120

    @Synchronized fun record(context: Context, stage: String, apState: Int = -1,
                             ready: Boolean = false, attempts: Int = 0, code: Int = -1, detail: String = "none") {
        if (!stage.matches(Regex("[a-z_]{1,48}")) || !detail.matches(Regex("[a-z_]{1,48}"))) return
        runCatching {
            val internal = AtomicFile(File(context.filesDir, FILE_NAME))
            val old = runCatching { JSONObject(String(internal.readFully(), Charsets.UTF_8)) }.getOrNull()
            val previous = old?.optJSONArray("events") ?: JSONArray()
            val events = JSONArray()
            for (i in maxOf(0, previous.length() - LIMIT + 1) until previous.length()) events.put(previous.get(i))
            val event = JSONObject().put("stage", stage).put("wall_ms", System.currentTimeMillis())
                .put("uptime_ms", SystemClock.elapsedRealtime())
                .put("boot_count", Settings.Global.getInt(context.contentResolver, "boot_count", -1))
                .put("ap_state", apState).put("interface_ready", ready).put("attempts", attempts).put("code", code).put("detail", detail)
            events.put(event)
            val report = JSONObject().put("schema", 1).put("package", context.packageName)
                .put("version_code", context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
                .put("uid", Process.myUid()).put("supported", NativeHotspotPreferences.supported())
                .put("enabled", NativeHotspotPreferences.enabled(context)).put("paused", NativeHotspotPreferences.paused(context))
                .put("allowed", NativeHotspotPreferences.allowed(context))
                .put("can_write", Settings.System.canWrite(context)).put("events", events)
            val bytes = report.toString().toByteArray(Charsets.UTF_8)
            write(internal, bytes)
            context.getExternalFilesDir(null)?.let { write(AtomicFile(File(it, FILE_NAME)), bytes) }
        }
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    @Synchronized fun status(context: Context): String {
        val permission = if (Settings.System.canWrite(context)) "已授权" else "未授权，可查看下方 ADB 授权命令"
        val last = runCatching {
            val data = JSONObject(String(AtomicFile(File(context.filesDir, FILE_NAME)).readFully(), Charsets.UTF_8))
            val events = data.getJSONArray("events")
            events.getJSONObject(events.length() - 1).getString("stage")
        }.getOrDefault("no_record")
        val label = when (last) {
            "ready" -> "热点已启用，接口已就绪（能否被手机发现仍需实测）"
            "expired" -> "本轮等待超时，请手动开热点后收集记录"
            "permission_denied" -> "缺少修改系统设置授权"
            "api_denied" -> "系统拒绝热点接口，请收集记录"
            "api_unavailable" -> "热点接口不兼容，请收集记录"
            "paused", "service_stopped" -> "自动恢复已暂停或服务已停止"
            "start_request", "callback_ok", "callback_failed", "checking" -> "正在检查／等待热点就绪"
            else -> last
        }
        return "修改系统设置：$permission\n最近结果：$label"
    }
}
