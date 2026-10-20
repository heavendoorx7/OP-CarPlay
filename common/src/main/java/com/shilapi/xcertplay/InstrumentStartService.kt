package com.shilapi.xcertplay

import android.content.Context
import android.os.Binder
import android.os.Process
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

@Keep
class InstrumentStartService(context: Context) : IInstrumentStartService.Stub() {
    private val clientUid=context.applicationInfo.uid
    init {
        require(context.packageName==P7InstrumentCommand.PACKAGE && clientUid in 10000 until 100000)
        require(Process.myUid()==2000)
    }
    override fun start(): Int {
        if(Binder.getCallingUid()!=clientUid)throw SecurityException()
        val p=ProcessBuilder(P7InstrumentCommand.startArgs).redirectErrorStream(true).start()
        return try {if(p.waitFor(5,TimeUnit.SECONDS))p.exitValue() else -1}
        finally {p.destroy();p.inputStream.close();p.outputStream.close();p.errorStream.close()}
    }
    override fun destroy() {
        if(Binder.getCallingUid() !in intArrayOf(clientUid,2000,0))throw SecurityException()
        kotlin.system.exitProcess(0)
    }
}
