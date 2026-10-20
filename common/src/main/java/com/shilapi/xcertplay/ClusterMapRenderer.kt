package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.ClusterLabDisplay
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Independent decoder surface backed by an offscreen EGL buffer, never a window or overlay. */
internal class ClusterMapRenderer(context: Context, enabled: Boolean) : AutoCloseable {
    companion object {
        private var instance: ClusterMapRenderer? = null
        @Synchronized fun obtain(context: Context, enabled: Boolean): ClusterMapRenderer {
            val current = instance?.takeUnless { it.closed }
                ?: ClusterMapRenderer(context.applicationContext, enabled).also { instance = it }
            current.setEnabled(enabled)
            return current
        }
        @Synchronized fun stop() { instance?.close(); instance = null }
    }
    private val thread = HandlerThread("op-instrument-render").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    @Volatile private var enabled = enabled
    @Volatile private var hideNativeLane = context.getSharedPreferences("p7-instrument", Context.MODE_PRIVATE)
        .getBoolean("hide_native_lane", true)
    @Volatile private var sessionActive = false
    @Volatile private var streamActive = false
    @Volatile private var decodedSize: Pair<Int, Int>? = null
    private var renderer: AndroidMediaSink? = null
    @Volatile private var surface: Surface? = null
    private var texture: SurfaceTexture? = null
    private var display = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var buffer = EGL14.EGL_NO_SURFACE
    private var config: android.opengl.EGLConfig? = null
    private var outputWidth = 0
    private var outputHeight = 0
    private var program = 0
    private var textureId = 0
    private var sequence = 0L
    private var updatedAt = 0L
    private val transform = FloatArray(16)
    private val cropTransform = FloatArray(16)
    private val sampleTransform = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0) }
    private var exporter: InstrumentFrameServer? = null
    val available: Boolean get() = !closed && surface != null

    init {
        handler.post {
            try {
                initialize()
                if (this.enabled && P7InstrumentCommand.supported()) startExport()
            } catch (_: Exception) { closed = true; release(); thread.quitSafely() }
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        handler.post {
            if (closed) return@post
            if (value && P7InstrumentCommand.supported() && surface != null) startExport()
            else { exporter?.close(); exporter = null }
        }
    }

    private fun startExport() {
        if (exporter == null) exporter = InstrumentFrameServer(::capture) { hideNativeLane }
    }
    fun setHideNativeLane(value: Boolean) { hideNativeLane = value }

    fun configure(profile: ClusterLabDisplay.Profile, content: ClusterLabDisplay.Content) = Unit
    fun offered(content: Set<ClusterLabDisplay.Content>) = Unit

    fun bind(next: AndroidMediaSink) {
        handler.post {
            if (closed) return@post
            if (renderer !== next) {
                surface?.let { renderer?.clearSurface(ClusterLabDisplay.TYPE, it) }
                decodedSize = null; streamActive = false; sequence = 0
            }
            renderer = next
            val profile = ClusterLabDisplay.Profile.P7_CENTERED
            next.setStreamDimensions(ClusterLabDisplay.TYPE, profile.width, profile.height)
            Log.i("OPInstrumentSize", "build=1349 requested=${profile.width}x${profile.height}")
            surface?.let { next.setSurface(ClusterLabDisplay.TYPE, it) }
            val state = next.screenStateSnapshot()
            streamActive = ClusterLabDisplay.TYPE in state.activeTypes
            decodedSize = state.decodedSizes[ClusterLabDisplay.TYPE]
        }
    }

    fun session(value: Boolean) {
        sessionActive = value
        if (!value) { streamActive = false; decodedSize = null }
    }
    fun active(type: Int, value: Boolean) {
        if (type == ClusterLabDisplay.TYPE) { streamActive = value; if (!value) decodedSize = null }
    }
    fun decoded(type: Int, width: Int, height: Int) {
        if (type == ClusterLabDisplay.TYPE && width in 1..4096 && height in 1..4096) {
            val crop = P7InstrumentFraming.crop(width, height)
            Log.i("OPInstrumentSize", "build=1349 decoded=${width}x${height} crop=${crop.left},${crop.top},${crop.width},${crop.height}")
            decodedSize = width to height
        }
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val choices = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
            EGL14.EGL_NONE),0,choices,0,1,count,0) && count[0] > 0)
        config = choices[0]
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        resize(664,664)
        val ids = IntArray(1)
        GLES20.glGenTextures(1,ids,0);textureId=ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec4 p;attribute vec4 t;uniform mat4 m;varying vec2 uv;void main(){gl_Position=p;uv=(m*t).xy;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;uniform samplerExternalOES s;varying vec2 uv;void main(){gl_FragColor=texture2D(s,uv);}")
        program=GLES20.glCreateProgram()
        GLES20.glAttachShader(program,vertex);GLES20.glAttachShader(program,fragment);GLES20.glLinkProgram(program)
        val linked=IntArray(1);GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0)
        GLES20.glDeleteShader(vertex);GLES20.glDeleteShader(fragment);check(linked[0]!=0)
        texture=SurfaceTexture(textureId).apply {
            setDefaultBufferSize(ClusterLabDisplay.Profile.P7_CENTERED.width, ClusterLabDisplay.Profile.P7_CENTERED.height)
            setOnFrameAvailableListener({
                if (!closed) runCatching {
                    // Drain every decoded frame, even though JPEG export is limited to the instrument request.
                    updateTexImage();getTransformMatrix(transform)
                    sequence++;updatedAt=SystemClock.elapsedRealtime()
                }
            },handler)
        }
        surface=Surface(texture)
        renderer?.setSurface(ClusterLabDisplay.TYPE, requireNotNull(surface))
    }

    private fun shader(type: Int, source: String): Int {
        val id=GLES20.glCreateShader(type)
        GLES20.glShaderSource(id,source);GLES20.glCompileShader(id)
        val good=IntArray(1);GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,good,0)
        check(good[0]!=0);return id
    }

    private fun resize(width: Int, height: Int) {
        if (outputWidth==width && outputHeight==height) return
        if (buffer!=EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,eglContext)
            EGL14.eglDestroySurface(display,buffer)
        }
        buffer=EGL14.eglCreatePbufferSurface(display,config,
            intArrayOf(EGL14.EGL_WIDTH,width,EGL14.EGL_HEIGHT,height,EGL14.EGL_NONE),0)
        check(buffer!=EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,buffer,buffer,eglContext))
        outputWidth=width;outputHeight=height
    }

    private fun capture(width: Int, height: Int): Pair<Long, Bitmap>? {
        if (closed || !enabled || !sessionActive || !streamActive) return null
        val task=FutureTask<Pair<Long,Bitmap>?> {
            val size=decodedSize
            if (closed || !enabled || !sessionActive || !streamActive || size==null ||
                sequence==0L || SystemClock.elapsedRealtime()-updatedAt>5000) null
            else {
                resize(width,height)
                GLES20.glViewport(0,0,width,height);GLES20.glClearColor(0f,0f,0f,1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val crop=P7InstrumentFraming.crop(size.first,size.second)
                crop.writeUvMatrix(size.first,size.second,cropTransform)
                // Apply the logical crop before the decoder's texture orientation/crop.
                Matrix.multiplyMM(sampleTransform,0,transform,0,cropTransform,0)
                val scale=minOf(width.toDouble()/crop.width,height.toDouble()/crop.height)
                val w=(crop.width*scale).toInt().coerceIn(1,width)
                val h=(crop.height*scale).toInt().coerceIn(1,height)
                GLES20.glViewport((width-w)/2,(height-h)/2,w,h)
                GLES20.glUseProgram(program)
                val p=GLES20.glGetAttribLocation(program,"p");val t=GLES20.glGetAttribLocation(program,"t")
                vertices.position(0);GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,vertices)
                vertices.position(2);GLES20.glVertexAttribPointer(t,2,GLES20.GL_FLOAT,false,16,vertices)
                GLES20.glEnableVertexAttribArray(p);GLES20.glEnableVertexAttribArray(t)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"m"),1,false,sampleTransform,0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"s"),0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
                val pixels=ByteBuffer.allocateDirect(width*height*4)
                GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels)
                check(GLES20.glGetError()==GLES20.GL_NO_ERROR)
                // glReadPixels starts at the bottom; Android bitmap rows start at the top.
                val upright=ByteArray(width*height*4)
                for (row in 0 until height) {
                    pixels.position((height-1-row)*width*4)
                    pixels.get(upright,row*width*4,width*4)
                }
                val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                image.copyPixelsFromBuffer(ByteBuffer.wrap(upright))
                sequence to image
            }
        }
        if (!handler.post(task)) return null
        return try { task.get(1500,TimeUnit.MILLISECONDS) }
        catch (_: Exception) {
            handler.post { runCatching { task.get()?.second?.recycle() } }
            null
        }
    }

    fun dispose() = close()
    override fun close() {
        closed=true;sessionActive=false
        exporter?.close();exporter=null
        handler.post { release();thread.quitSafely() }
    }
    private fun release() {
        exporter?.close();exporter=null
        surface?.let { renderer?.clearSurface(ClusterLabDisplay.TYPE,it);it.release() }
        surface=null;renderer=null;texture?.release();texture=null
        if (display!=EGL14.EGL_NO_DISPLAY) {
            if (program!=0) GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if (buffer!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,buffer)
            if (eglContext!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,eglContext)
            EGL14.eglReleaseThread();EGL14.eglTerminate(display)
        }
        display=EGL14.EGL_NO_DISPLAY
    }
}
