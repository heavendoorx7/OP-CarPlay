package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.ClusterLabDisplay
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.json.JSONObject
import java.io.File

/** Separate on-screen decoder target. This class has NO vehicle output interface. */
class ClusterLabPreview(context: Context) : LinearLayout(context), TextureView.SurfaceTextureListener {
    private val handler = Handler(Looper.getMainLooper())
    private val label = TextView(context).apply { setTextColor(Color.WHITE); textSize = 13f }
    private val texture = TextureView(context).apply { surfaceTextureListener = this@ClusterLabPreview }
    private var renderer: AndroidMediaSink? = null
    private var surface: Surface? = null
    private var active = false
    private var session = false
    private var decoded: Pair<Int, Int>? = null
    private var primaryDecoded: Pair<Int, Int>? = null
    private var offered = emptySet<ClusterLabDisplay.Content>()
    private var updates = 0L
    private var connections = 0
    private var streamStarts = 0
    private var lastWrite = 0L
    private var profile = ClusterLabDisplay.Profile.WIDE
    private var content = ClusterLabDisplay.Content.MAP
    private var disposed = false
    private val frameExport = if (android.os.Build.MODEL == "E28" && android.os.Build.VERSION.SDK_INT == 28)
        ClusterFrameExport("op.clusterlab.frames") { width, height -> captureForInstrument(width, height) }
    else null
    val aspectRatio: Double get() = profile.height.toDouble() / profile.width
    private val report = File(context.getExternalFilesDir(null) ?: context.filesDir, "cluster-lab/status.json")
    private val startedAt = System.currentTimeMillis()
    private val heartbeat = object : Runnable {
        override fun run() {
            if (disposed) return
            publish()
            handler.postDelayed(this, 2000)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(15, 18, 22))
        setPadding(8, 8, 8, 8)
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        // Do not pass touches on this preview to the underlying primary CarPlay canvas.
        isClickable = true
        contentDescription = "独立仪表流预览；实车投放须运行离线投放工具"
        publish()
        handler.postDelayed(heartbeat, 2000)
    }

    fun configure(profile: ClusterLabDisplay.Profile, content: ClusterLabDisplay.Content) {
        this.profile = profile
        this.content = content
        renderer?.setStreamDimensions(ClusterLabDisplay.TYPE, profile.width, profile.height)
        publish()
    }

    fun bind(next: AndroidMediaSink) {
        if (renderer !== next) {
            surface?.let { renderer?.clearSurface(ClusterLabDisplay.TYPE, it) }
            decoded = null
            primaryDecoded = null
            updates = 0
            active = false
            offered = emptySet()
        }
        renderer = next
        next.setStreamDimensions(ClusterLabDisplay.TYPE, profile.width, profile.height)
        surface?.let { next.setSurface(ClusterLabDisplay.TYPE, it) }
        val snapshot = next.screenStateSnapshot()
        active = ClusterLabDisplay.TYPE in snapshot.activeTypes
        decoded = snapshot.decodedSizes[ClusterLabDisplay.TYPE]
        publish()
    }

    fun session(active: Boolean) {
        if (active && !session) connections++
        session = active
        if (!active) { decoded = null; primaryDecoded = null; updates = 0; this.active = false }
        publish()
    }

    fun offered(content: Set<ClusterLabDisplay.Content>) { offered = content; publish() }
    fun active(type: Int, active: Boolean) {
        if (type != ClusterLabDisplay.TYPE) return
        if (active && !this.active) { streamStarts++; updates = 0; decoded = null }
        this.active = active
        if (!active) { decoded = null; updates = 0 }
        publish()
    }
    fun decoded(type: Int, width: Int, height: Int) {
        if (type == ClusterLabDisplay.TYPE) decoded = width to height
        if (type == 110) primaryDecoded = width to height
        publish()
    }

    override fun onSurfaceTextureAvailable(t: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(t).also { renderer?.setSurface(ClusterLabDisplay.TYPE, it) }
    }
    override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(t: SurfaceTexture) {
        updates++
        if (SystemClock.elapsedRealtime() - lastWrite >= 1000) publish()
    }
    override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
        releaseSurface()
        return true
    }
    private fun releaseSurface() {
        surface?.let { renderer?.clearSurface(ClusterLabDisplay.TYPE, it); it.release() }
        surface = null
    }

    fun dispose() {
        disposed = true
        frameExport?.close()
        handler.removeCallbacks(heartbeat)
        releaseSurface()
        renderer = null
    }

    /** Runs on the UI thread; returns only a new, aspect-preserving bitmap. */
    private fun captureForInstrument(width: Int, height: Int): Pair<Long, Bitmap>? {
        val size = decoded ?: return null
        if (disposed || !session || !active || updates == 0L || !texture.isAvailable || !isShown) return null
        val scale = minOf(width.toDouble() / size.first, height.toDouble() / size.second)
        val fittedWidth = (size.first * scale).toInt().coerceIn(1, width)
        val fittedHeight = (size.second * scale).toInt().coerceIn(1, height)
        val source = texture.getBitmap(fittedWidth, fittedHeight) ?: return null
        try {
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(source, (width - fittedWidth) / 2f, (height - fittedHeight) / 2f, null)
            return updates to output
        } finally { source.recycle() }
    }

    private fun publish() {
        lastWrite = SystemClock.elapsedRealtime()
        val dimensions = decoded
        label.text = when {
            dimensions != null -> "仪表流 · ${dimensions.first}×${dimensions.second} · 画面更新 $updates"
            active -> "仪表流已建立，等待解码画面"
            session -> "CarPlay 已连接，等待独立仪表流"
            else -> "独立仪表地图 · 等待 iPhone"
        }
        val data = JSONObject().apply {
            put("schema", 1)
            put("package", context.packageName)
            put("writtenAtMillis", System.currentTimeMillis())
            put("runStartedAtMillis", startedAt)
            put("uptimeMillis", lastWrite)
            val exports = frameExport?.exportedFrames ?: 0
            put("stage", if (exports == 0L) "PHONE_MAP_PREVIEW_ONLY" else "LOCAL_FRAME_EXPORT")
            put("localFramesExported", exports)
            // Actual vehicle submission counts belong to the separately run sender.
            put("vehicleFramesSent", if (exports == 0L) 0 else JSONObject.NULL)
            put("sessionActive", session)
            put("sessionConnections", connections)
            put("altStreamActive", active)
            put("altStreamStarts", streamStarts)
            put("textureUpdates", updates)
            put("profile", profile.name)
            put("content", content.name)
            put("requestedWidth", profile.width)
            put("requestedHeight", profile.height)
            put("decodedWidth", dimensions?.first ?: 0)
            put("decodedHeight", dimensions?.second ?: 0)
            put("mainDecodedWidth", primaryDecoded?.first ?: 0)
            put("mainDecodedHeight", primaryDecoded?.second ?: 0)
            put("receivedMainFrames", renderer?.videoFrameCount(110) ?: 0)
            put("receivedAltFrames", renderer?.videoFrameCount(111) ?: 0)
            put("receivedMainConfigs", renderer?.videoConfigCount(110) ?: 0)
            put("receivedAltConfigs", renderer?.videoConfigCount(111) ?: 0)
            put("mainCodec", renderer?.videoCodecName(110) ?: "UNKNOWN")
            put("altCodec", renderer?.videoCodecName(111) ?: "UNKNOWN")
            put("offered", org.json.JSONArray(offered.map { it.name }))
            put("visualMapConfirmed", false) // Only the human observation can establish this.
        }
        runCatching {
            report.parentFile?.mkdirs()
            val temp = File(report.parentFile, "status.json.tmp")
            temp.writeText(data.toString(2))
            check(temp.renameTo(report))
            if ((renderer?.videoFrameCount(111) ?: 0) > 0) {
                val lastStream = File(report.parentFile, "last-stream.json")
                val pending = File(report.parentFile, "last-stream.json.tmp")
                pending.writeText(data.toString(2))
                check(pending.renameTo(lastStream))
            }
            // Keep the last decoded sample when the user disconnects or changes window/activity.
            // Counts and dimensions only; this never saves map pixels or route data.
            if (dimensions != null && updates > 0) {
                val last = File(report.parentFile, "last-video.json")
                val pending = File(report.parentFile, "last-video.json.tmp")
                pending.writeText(data.toString(2))
                check(pending.renameTo(last))
            }
        }.onFailure {
            android.util.Log.w("OPClusterLab", "Status write failed: ${it.javaClass.simpleName}")
        }
    }
}
