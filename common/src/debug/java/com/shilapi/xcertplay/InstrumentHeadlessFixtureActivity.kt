package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.view.Surface
import java.io.File

/** Synthetic pixel check of the real offscreen renderer; never in the release APK. */
class InstrumentHeadlessFixtureActivity : Activity() {
    private var renderer: ClusterMapRenderer? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val r=ClusterMapRenderer(this,true);renderer=r
        Thread {
            val output=File(getExternalFilesDir(null),"headless-check.txt")
            try {
                val field=ClusterMapRenderer::class.java.getDeclaredField("surface").apply{isAccessible=true}
                repeat(40){if(field.get(r)==null)Thread.sleep(100)}
                val surface=field.get(r) as Surface
                r.session(true);r.active(111,true);r.decoded(111,664,664)
                val capture=ClusterMapRenderer::class.java.getDeclaredMethod("capture",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}
                val paint=Paint()
                var sequence=0L
                repeat(2){ pass ->
                    val c=surface.lockCanvas(null)
                    c.drawColor(Color.BLACK)
                    paint.color=if(pass==0)Color.RED else Color.GREEN
                    c.drawRect(0f,0f,332f,332f,paint)
                    paint.color=Color.BLUE;c.drawRect(332f,332f,664f,664f,paint)
                    surface.unlockCanvasAndPost(c)
                    Thread.sleep(400)
                    @Suppress("UNCHECKED_CAST")
                    val frame=capture.invoke(r,660,660) as Pair<Long,Bitmap>?
                    check(frame!=null && frame.first>sequence);sequence=frame.first
                    val top=frame.second.getPixel(100,100);val bottom=frame.second.getPixel(550,550)
                    check(if(pass==0)Color.red(top)>220&&Color.blue(top)<30 else Color.green(top)>220&&Color.red(top)<30)
                    check(Color.blue(bottom)>220&&Color.red(bottom)<30)
                    check(Color.red(frame.second.getPixel(550,100))<30)
                    frame.second.recycle()
                }
                r.session(false);check(capture.invoke(r,660,660)==null)
                r.session(true);r.active(111,true);r.decoded(111,664,664)
                r.setEnabled(false);check(capture.invoke(r,660,660)==null)
                output.writeText("PASS: headless EGL; 664 to 660; upright RGB; fresh frames; disconnect; disabled")
            }catch(e:Throwable){output.writeText("FAIL: "+e.stackTraceToString())}
            finally{r.close()}
        }.start()
    }
    override fun onDestroy(){renderer?.close();super.onDestroy()}
}
