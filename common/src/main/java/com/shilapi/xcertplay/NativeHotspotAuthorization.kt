package com.shilapi.xcertplay

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** User-initiated local ADB or Shizuku initialization, with computer command fallback. */
internal object NativeHotspotAuthorization {
    fun show(activity: Activity, onChecked: () -> Unit) {
        val scale = (activity.resources.displayMetrics.widthPixels / 1200f).coerceIn(0.85f, 2f)
        fun px(value: Int) = (value * scale).toInt()
        val primary = Color.rgb(236, 237, 239)
        val secondary = Color.rgb(165, 169, 179)
        val accent = Color.rgb(255, 146, 85)
        fun background(color: Int) = GradientDrawable().apply {
            setColor(color); cornerRadius = px(12).toFloat()
        }
        fun text(value: String, size: Int = 14, color: Int = secondary) = TextView(activity).apply {
            this.text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
            setTextColor(color)
            setLineSpacing(px(3).toFloat(), 1f)
        }
        fun button(label: String, action: () -> Unit) = Button(activity).apply {
            this.text = label; isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14).toFloat())
            setTextColor(primary); backgroundTintList = null
            background = background(Color.rgb(44, 47, 54))
            minHeight = 0; minimumHeight = px(48)
            setPadding(px(16), px(8), px(16), px(8))
            setOnClickListener { action() }
        }
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(26), px(22), px(26), px(20))
            background = background(Color.rgb(24, 26, 30))
        }
        root.addView(text("自动热点授权", 23, primary))
        root.addView(text("仅需为 OP 授予「修改系统设置」权限", 14).apply {
            setPadding(0, px(5), 0, px(14))
        })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { isFillViewport = false; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val status = text("", 16, accent).apply { setPadding(0, 0, 0, px(12)) }
        body.addView(status)
        lateinit var shizuku: ShizukuHotspotAuthorization
        lateinit var localAdb: LocalAdbHotspotAuthorization
        val adbStatus = text("无需电脑或 Shizuku；车机 ADB 必须已经开启。首次可能需要在车机允许调试连接。", 14)
        val adbGrant = button("本机 ADB 一键授权") { localAdb.request() }.apply {
            background = background(Color.rgb(255, 146, 85))
            setTextColor(Color.rgb(39, 24, 15))
        }
        body.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(16))
            background = background(Color.rgb(36, 38, 44))
            addView(text("直接在车机授权", 18, primary))
            addView(adbStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(10); bottomMargin = px(14)
            })
            addView(adbGrant, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        val shizukuStatus = text("Shizuku 已运行时，无需再连接电脑。首次使用需允许 OP 的 Shizuku 请求。", 14)
        val grant = button("使用 Shizuku 授权") { shizuku.request() }
        val shizukuCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(16))
            background = background(Color.rgb(36, 38, 44))
            addView(text("通过 Shizuku 授权", 18, primary))
            addView(shizukuStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(10); bottomMargin = px(14)
            })
            addView(grant, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        body.addView(shizukuCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) })
        val commands = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        body.addView(button("电脑 ADB 备用方式") {
            commands.visibility = if (commands.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        }.apply {
            background = background(Color.TRANSPARENT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, px(8), px(12), px(8))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) })
        body.addView(commands)
        commands.addView(text(
            "电脑已通过 ADB 连接车机时，执行下方命令，再返回检查授权。",
        ).apply { setPadding(0, px(12), 0, 0) })
        fun command(title: String, value: String) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(16), px(12), px(16), px(12))
                background = background(Color.rgb(15, 17, 20))
            }
            val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(text(title, 15, primary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            heading.addView(button("复制命令") {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, value))
                Toast.makeText(activity, "已复制到车机剪贴板", Toast.LENGTH_SHORT).show()
            })
            card.addView(heading)
            card.addView(text(value, 12, primary).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, px(10), 0, 0)
            })
            commands.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(12)
            })
        }
        val target = activity.packageName
        command("授权命令 · 仅 WRITE_SETTINGS", "adb shell cmd appops set --user 0 $target WRITE_SETTINGS allow")
        command("电脑查询 · 查看是否为 allow", "adb shell cmd appops get --user 0 $target WRITE_SETTINGS")
        commands.addView(text(
            "这些命令不会开启 ADB，也不会直接打开热点。ADB 必须已开启并允许该电脑连接；无需启动 Shizuku。\n" +
                "在 platform-tools 目录执行时，Mac 将 adb 改成 ./adb，Windows PowerShell 改成 .\\adb。" +
                "若已进入 adb shell，只输入 cmd appops 开始的部分；多台设备连接时需用 adb -s 指定车机。\n" +
                "复制仅写入车机剪贴板，不会传到电脑；也可拍下命令在电脑输入。授权后仍沿用原车热点配置，不保证其他车型或系统可用。",
            12,
        ).apply { setPadding(0, px(14), 0, px(10)) })
        fun refresh() {
            val granted = Settings.System.canWrite(activity)
            status.text = if (granted) "已授权 · 可返回开启自动热点并保存" else "尚未授权 · 选择下方任一方式"
            status.setTextColor(if (granted) Color.rgb(121, 211, 154) else accent)
            onChecked()
        }
        val actions = LinearLayout(activity).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, px(16), 0, 0)
        }
        actions.addView(button("检查授权") { refresh() })
        val done = button("完成") { dialog.dismiss() }
        actions.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = px(12)
        })
        root.addView(actions)
        dialog.setContentView(root)
        shizuku = ShizukuHotspotAuthorization(activity, { message, busy ->
            shizukuStatus.text = message
            grant.isEnabled = !busy
            adbGrant.isEnabled = !busy
            done.isEnabled = !busy
            dialog.setCancelable(!busy)
            dialog.setCanceledOnTouchOutside(!busy)
        }) { refresh() }
        localAdb = LocalAdbHotspotAuthorization(activity, { message, busy ->
            adbStatus.text = message
            grant.isEnabled = !busy
            adbGrant.isEnabled = !busy
            done.text = if (busy) "取消" else "完成"
            dialog.setCanceledOnTouchOutside(!busy)
        }) { refresh() }
        dialog.setOnDismissListener { localAdb.close(); shizuku.close(); onChecked() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val metrics = activity.resources.displayMetrics
            setLayout((metrics.widthPixels * 0.86f).toInt(), (metrics.heightPixels * 0.86f).toInt())
        }
        refresh()
    }
}
