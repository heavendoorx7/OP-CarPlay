package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import android.view.TextureView

/** Synthetic local-only exporter exercise, absent from release builds. */
class ClusterExportFixtureActivity : Activity() {
    private var export: ClusterFrameExport? = null
    private lateinit var preview: ClusterLabPreview
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        preview = ClusterLabPreview(this)
        setContentView(FrameLayout(this).apply { addView(preview, FrameLayout.LayoutParams(1000, 420)) })
        preview.postDelayed({
            val cls = ClusterLabPreview::class.java
            val textureField = cls.getDeclaredField("texture").apply { isAccessible = true }
            val texture = textureField.get(preview) as TextureView
            val canvas = texture.lockCanvas()
            if (canvas != null) { canvas.drawColor(Color.rgb(220, 100, 30)); texture.unlockCanvasAndPost(canvas) }
            preview.session(true)
            preview.active(111, true)
            preview.decoded(111, 1600, 600)
            val updateField = cls.getDeclaredField("updates").apply { isAccessible = true }
            updateField.setLong(preview, 10)
            val capture = cls.getDeclaredMethod("captureForInstrument", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            export = ClusterFrameExport("op.clusterlab.fixture") { w, h ->
                updateField.setLong(preview, updateField.getLong(preview) + 1)
                @Suppress("UNCHECKED_CAST")
                capture.invoke(preview, w, h) as Pair<Long, android.graphics.Bitmap>?
            }
        }, 600)
    }
    override fun onDestroy() { export?.close(); preview.dispose(); super.onDestroy() }
}
