package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import android.widget.FrameLayout

/** Offline probe: no controller, microphone, hotspot changes, or real credentials. */
class P7DisplayProbeToggleActivity : Activity() {
    private var compat: P7WindowCompat? = null
    private lateinit var label: TextView
    private var lastBounds = ""

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.getStringExtra("probe_action") ?: return
        window.decorView.post {
            if (!hasWindowFocus()) return@post
            try {
                when (action) {
                    "transport-test" -> Thread({
                        val result = P7TransportProbe.run()
                        java.io.File(filesDir, "p7-transport-test.txt").writeText(result)
                        Log.i("P7TransportProbe", result)
                    }, "p7-loopback-test").start()
                    "fullscreen", "split" -> {
                        val type = android.view.WindowManager.LayoutParams::class.java
                            .getField("areaType").get(window.attributes) as? String
                        val cls = Class.forName("com.xiaopeng.screen.ScreenTaskManagerFactory")
                        val manager = cls.getMethod("get").invoke(null)
                        if (action == "fullscreen" && type in setOf("primary", "secondary")) {
                            cls.getMethod("enterFullScreen", String::class.java).invoke(manager, type)
                        } else if (action == "split" && type in setOf("logical", "physical", "immersion")) {
                            cls.getMethod("enterSplitScreen", String::class.java).invoke(manager, "primary")
                        }
                    }
                }
                Log.i("P7DisplayProbe", "Display test action=$action")
            } catch (error: ReflectiveOperationException) {
                Log.w("P7DisplayProbe", "Display action unavailable: ${error.javaClass.simpleName}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this).apply {
            text = "P7 显示测试\n可切换全屏 / 分屏\n返回键退出"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(20, 55, 80))
        }
        val root = FrameLayout(this)
        root.addView(label, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        compat = P7WindowCompat.attach(this)
        label.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val position = IntArray(2)
            label.getLocationInWindow(position)
            val bounds = "${label.width} × ${label.height}; origin=${position[0]},${position[1]}"
            if (bounds != lastBounds) {
                lastBounds = bounds
                label.text = "P7 显示测试\n$bounds\n可切换全屏 / 分屏，返回键退出"
                Log.i("P7DisplayProbe", bounds)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { compat?.requestDock() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { compat?.requestDock() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post { compat?.requestDock() }
    }

    override fun onDestroy() {
        compat?.close()
        super.onDestroy()
    }
}
