package com.shilapi.xcertplay

import java.util.concurrent.TimeUnit

/** No caller-provided command, package, user, operation or mode. */
internal object HotspotGrantCommand {
    const val PACKAGE = "com.shilapi.xcertplay.p7"
    const val OK = 0
    const val WRITE_FAILED = 1
    const val VERIFY_FAILED = 2
    const val TIMEOUT = 3
    val grantArgs: List<String> get() = listOf("/system/bin/cmd", "appops", "set", "--user", "0", PACKAGE, "WRITE_SETTINGS", "allow")
    val queryArgs: List<String> get() = listOf("/system/bin/cmd", "appops", "get", "--user", "0", PACKAGE, "WRITE_SETTINGS")

    data class Result(val exit: Int, val output: String, val timedOut: Boolean = false)

    fun run(execute: (List<String>) -> Result = ::execute): Int {
        val set = execute(grantArgs)
        if (set.timedOut) return TIMEOUT
        if (set.exit != 0 || set.output.contains("Error:", ignoreCase = true) || set.output.contains("Exception")) return WRITE_FAILED
        val get = execute(queryArgs)
        if (get.timedOut) return TIMEOUT
        if (get.exit != 0) return VERIFY_FAILED
        return if (Regex("(?m)^\\s*WRITE_SETTINGS:\\s*allow(?:;|\\s|$)").containsMatchIn(get.output)) OK else VERIFY_FAILED
    }

    private fun execute(args: List<String>): Result {
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        try {
            // These two commands produce only a few status lines, never application data.
            if (!p.waitFor(5, TimeUnit.SECONDS)) return Result(-1, "", true)
            val bytes = ByteArray(4096)
            val count = p.inputStream.read(bytes)
            return Result(p.exitValue(), if (count > 0) String(bytes, 0, count, Charsets.UTF_8) else "")
        } finally {
            p.destroy()
            p.inputStream.close()
            p.errorStream.close()
            p.outputStream.close()
        }
    }
}
