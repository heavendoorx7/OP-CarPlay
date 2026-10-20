package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class P7InstrumentPolicyTest {
    @Test fun liveRequestRemainsBounded() {
        val p=P7ImagePolicy(true,660,660,5,2)
        assertEquals(200,p.intervalMs)
        assertTrue(p.sameRequest(P7ImagePolicy(true,660,660,5,2)))
        assertFalse(p.sameRequest(P7ImagePolicy(true,640,660,5,2)))
        listOf({P7ImagePolicy(false,660,660,5,2)}, {P7ImagePolicy(true,660,660,5,1)},
            {P7ImagePolicy(true,2000,660,5,2)}, {P7ImagePolicy(true,660,660,0,2)}).forEach { create ->
            assertThrows(IllegalArgumentException::class.java){create()}
        }
    }
    @Test fun onlyFixedInstrumentCommandMayUseShell() {
        val identity=object:LocalAdbProtocol.Identity {
            override fun signToken(token:ByteArray)=byteArrayOf()
            override fun publicKey()=byteArrayOf()
        }
        val output=ByteArrayOutputStream()
        val protocol=LocalAdbProtocol(ByteArrayInputStream(byteArrayOf()),output,identity)
        assertThrows(IllegalArgumentException::class.java){protocol.execute(listOf("/system/bin/sh","-c","id"))}
        assertEquals(0,output.size())
        assertEquals("com.shilapi.xcertplay.p7",P7InstrumentCommand.PACKAGE)
        assertTrue(P7InstrumentCommand.shellLine().startsWith("'/system/bin/sh' '-c' '"))
        assertFalse(P7InstrumentCommand.SCRIPT.contains("setprop"))
        assertFalse(P7InstrumentCommand.SCRIPT.contains("appops"))
    }
}
