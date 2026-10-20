package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class HotspotGrantCommandTest {
    @Test fun onlyWritesOwnPackageAndSingleOperation() {
        val calls = mutableListOf<List<String>>()
        val code = HotspotGrantCommand.run { args ->
            calls += args
            HotspotGrantCommand.Result(0, if (args[2] == "get") "WRITE_SETTINGS: allow; time=+10s" else "")
        }
        assertEquals(HotspotGrantCommand.OK, code)
        assertEquals(listOf("/system/bin/cmd", "appops", "set", "--user", "0", "com.shilapi.xcertplay.p7", "WRITE_SETTINGS", "allow"), calls[0])
        assertEquals(listOf("/system/bin/cmd", "appops", "get", "--user", "0", "com.shilapi.xcertplay.p7", "WRITE_SETTINGS"), calls[1])
        assertEquals(2, calls.size)
    }

    @Test fun nonzeroWriteStopsBeforeQuery() {
        var calls = 0
        assertEquals(HotspotGrantCommand.WRITE_FAILED, HotspotGrantCommand.run { calls++; HotspotGrantCommand.Result(1, "denied") })
        assertEquals(1, calls)
    }

    @Test fun textualShellErrorWithZeroExitIsNotSuccess() {
        for (output in listOf("Error: Unknown package", "java.lang.SecurityException: denied")) {
            var calls = 0
            assertEquals(HotspotGrantCommand.WRITE_FAILED, HotspotGrantCommand.run { calls++; HotspotGrantCommand.Result(0, output) })
            assertEquals(1, calls)
        }
    }

    @Test fun writeTimeoutDoesNotContinue() {
        var calls = 0
        assertEquals(HotspotGrantCommand.TIMEOUT, HotspotGrantCommand.run { calls++; HotspotGrantCommand.Result(-1, "", true) })
        assertEquals(1, calls)
    }

    @Test fun noOperationsAndOtherPermissionsCannotConfirmGrant() {
        for (output in listOf("No operations.\nDefault mode: default", "CAMERA: allow", "WRITE_SETTINGS: deny", "WRITE_SETTINGS: allow_pending", "", "prefix WRITE_SETTINGS: allow")) {
            assertEquals(HotspotGrantCommand.VERIFY_FAILED, HotspotGrantCommand.run { args ->
                HotspotGrantCommand.Result(0, if (args[2] == "get") output else "")
            })
        }
    }

    @Test fun queryFailureIsNotSuccessEvenWithAllowText() {
        assertEquals(HotspotGrantCommand.VERIFY_FAILED, HotspotGrantCommand.run { args ->
            if (args[2] == "get") HotspotGrantCommand.Result(1, "WRITE_SETTINGS: allow") else HotspotGrantCommand.Result(0, "")
        })
    }

    @Test fun queryTimeoutIsExplicit() {
        assertEquals(HotspotGrantCommand.TIMEOUT, HotspotGrantCommand.run { args ->
            if (args[2] == "get") HotspotGrantCommand.Result(-1, "", true) else HotspotGrantCommand.Result(0, "")
        })
    }
}
