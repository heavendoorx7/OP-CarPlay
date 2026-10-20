package com.shilapi.xcertplay

/** Exact, package-owned launch only. No caller-controlled shell arguments or global grants. */
internal object P7InstrumentCommand {
    const val PACKAGE="com.shilapi.xcertplay.p7"
    const val FRAME_SOCKET="op.p7.instrument.frames.v1"
    const val CONTROL_SOCKET="op.p7.instrument.control.v1"
    fun supported() = android.os.Build.MODEL=="E28" && android.os.Build.VERSION.SDK_INT==28
    val startArgs: List<String> get() = listOf("/system/bin/sh","-c",SCRIPT)
    const val SCRIPT = """op_apk="$(/system/bin/pm path com.shilapi.xcertplay.p7)"; case "${'$'}op_apk" in package:/data/app/*/base.apk) ;; *) exit 3;; esac; CLASSPATH="${'$'}{op_apk#package:}:/system/framework/android.car.jar" /system/bin/nohup /system/bin/app_process /system/bin --nice-name=op-p7-instrument com.shilapi.xcertplay.P7InstrumentWorker >/dev/null 2>&1 </dev/null &"""
    fun shellLine() = startArgs.joinToString(" ") { "'"+it.replace("'","'\\''")+"'" }
}
