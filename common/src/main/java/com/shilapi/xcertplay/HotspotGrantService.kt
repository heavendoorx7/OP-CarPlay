package com.shilapi.xcertplay

import android.content.Context
import android.os.Binder
import android.os.Process
import androidx.annotation.Keep

/** Short-lived Shizuku UserService. Not an exported Android Service or general shell. */
@Keep
class HotspotGrantService(context: Context) : IHotspotGrantService.Stub() {
    private val clientUid = context.applicationInfo.uid

    init {
        require(context.packageName == HotspotGrantCommand.PACKAGE)
        require(clientUid in 10000 until 100000) // Only the primary user's installed OP.
        require(Process.myUid() == 0 || Process.myUid() == 2000)
    }

    @Synchronized override fun authorize(): Int {
        if (Binder.getCallingUid() != clientUid) throw SecurityException("OP caller required")
        return try { HotspotGrantCommand.run() } catch (_: Exception) { HotspotGrantCommand.WRITE_FAILED }
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        if (caller != clientUid && caller != 0 && caller != 2000) throw SecurityException("OP or Shizuku required")
        // This class can only be instantiated inside a root/shell UserService process.
        kotlin.system.exitProcess(0)
    }
}
