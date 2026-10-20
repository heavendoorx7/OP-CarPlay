package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import android.graphics.SurfaceTexture
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import android.view.Surface
import android.view.TextureView
import android.widget.LinearLayout
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Debug-only real MediaCodec test: two synthesized gray videos, no phone/vehicle/network. */
class ClusterCodecFixtureActivity : Activity() {
    private val ready = CountDownLatch(2)
    private val decoded = ConcurrentHashMap<Int, Pair<Int, Int>>()
    private val views = mutableMapOf<Int, TextureView>()
    private val surfaces = mutableMapOf<Int, Surface>()
    private val referencePixels = ConcurrentHashMap<Int, Int>()
    private lateinit var sink: AndroidMediaSink

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        sink = AndroidMediaSink(this, onFirstVideoFrame = { type, w, h -> decoded[type] = w to h })
        sink.setStreamDimensions(110, 320, 160)
        sink.setStreamDimensions(111, 240, 240)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (type in listOf(110, 111)) {
            val view = TextureView(this)
            views[type] = view
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                    val s = Surface(t); surfaces[type] = s; sink.setSurface(type, s); ready.countDown()
                }
                override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                    surfaces.remove(type)?.let { sink.clearSurface(type, it); it.release() }
                    return true
                }
                override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit
            }
            row.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        setContentView(row)
        Thread({
            try {
                check(ready.await(5, TimeUnit.SECONDS)) { "Textures did not become available" }
                playFixture(110, "primary.mp4")
                playFixture(111, "alternate.mp4")
                Thread.sleep(1500)
                runOnUiThread {
                    runCatching {
                        val dark = views.getValue(110).bitmap!!.let { b ->
                            Color.red(b.getPixel(b.width / 2, b.height / 2)).also { b.recycle() }
                        }
                        val bright = views.getValue(111).bitmap!!.let { b ->
                            Color.red(b.getPixel(b.width / 2, b.height / 2)).also { b.recycle() }
                        }
                        val mainOk = decoded[110] == 320 to 160
                        val altOk = decoded[111] == 240 to 240
                        val isolated = bright - dark > 100 && dark > 0
                        sink.onScreenStreamActive(111, false)
                        write(JSONObject().put("synthetic", true).put("mainDimensionsCorrect", mainOk)
                            .put("altDimensionsCorrect", altOk).put("primaryPixel", dark)
                            .put("alternatePixel", bright).put("surfacesIsolated", isolated)
                            .put("directReferenceMainY", referencePixels[110])
                            .put("directReferenceAltY", referencePixels[111])
                            .put("passed", mainOk && altOk && isolated))
                    }.onFailure { fail(it) }
                }
            } catch (e: Exception) { fail(e) }
        }, "cluster-codec-fixture").start()
    }

    private fun playFixture(type: Int, asset: String) {
        val file = File(cacheDir, asset)
        assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        referencePixels[type] = decodeReference(file, surfaces[type])
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            fun nalu(key: String): ByteArray {
                val b = format.getByteBuffer(key)!!.duplicate()
                val a = ByteArray(b.remaining()); b.get(a)
                val prefix = if (a.take(4) == listOf<Byte>(0,0,0,1)) 4 else 3
                return a.copyOfRange(prefix, a.size)
            }
            val sps = nalu("csd-0"); val pps = nalu("csd-1")
            val avcc = byteArrayOf(1, sps[1], sps[2], sps[3], -1, -31,
                (sps.size ushr 8).toByte(), sps.size.toByte()) + sps +
                byteArrayOf(1, (pps.size ushr 8).toByte(), pps.size.toByte()) + pps
            sink.onScreenStreamActive(type, true)
            sink.onVideoCodec(type, VideoCodec.H264)
            sink.onVideoConfig(type, avcc)
            val dump = File(getExternalFilesDir(null), "fixture-input-$type.h264")
            dump.writeBytes(byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps)
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            var outputs = 0
            while (outputs < 30) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                buffer.position(0); buffer.limit(size)
                val a = ByteArray(size); buffer.get(a)
                dump.appendBytes(com.shilapi.xcertplay.media.MediaCodecSupport.toAnnexB(a))
                sink.onVideoFrame(type, a); outputs++
                extractor.advance(); Thread.sleep(67)
            }
            check(outputs == 30) { "Fixture supplied $outputs frames" }
        } finally { extractor.release(); file.delete() }
    }

    private fun decodeReference(file: File, surface: Surface?): Int {
        val extractor = MediaExtractor()
        val codec = MediaCodec.createDecoderByType("video/avc")
        try {
            extractor.setDataSource(file.absolutePath); extractor.selectTrack(0)
            val original = extractor.getTrackFormat(0)
            val format = MediaFormat.createVideoFormat("video/avc",
                original.getInteger("width"), original.getInteger("height"))
            for (key in listOf("csd-0", "csd-1")) {
                val source = original.getByteBuffer(key)!!.duplicate()
                val bytes = ByteArray(source.remaining()); source.get(bytes)
                format.setByteBuffer(key, ByteBuffer.wrap(bytes))
            }
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            codec.configure(format, surface, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                val index = codec.dequeueInputBuffer(1000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!; buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    codec.queueInputBuffer(index, 0, maxOf(0, size), System.nanoTime() / 1000,
                        if (size < 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (size >= 0) extractor.advance()
                }
                val out = codec.dequeueOutputBuffer(info, 1000)
                if (out >= 0) {
                    val image = codec.getOutputImage(out)
                    val value = image?.planes?.first()?.buffer?.get(0)?.toInt()?.and(255)
                        ?: if (surface != null && info.size > 0) 999 else null
                    image?.close(); codec.releaseOutputBuffer(out, false)
                    if (value != null) return value
                }
            }
            return -1
        } finally { runCatching { codec.stop() }; codec.release(); extractor.release() }
    }

    private fun fail(e: Throwable) = write(JSONObject().put("passed", false)
        .put("error", e.javaClass.simpleName + ": " + e.message))
    private fun write(value: JSONObject) {
        File(getExternalFilesDir(null), "codec-fixture.json").writeText(value.toString(2))
    }
    override fun onDestroy() {
        sink.close()
        surfaces.values.forEach { it.release() }; surfaces.clear()
        super.onDestroy()
    }
}
