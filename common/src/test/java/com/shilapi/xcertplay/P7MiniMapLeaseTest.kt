package com.shilapi.xcertplay
import org.junit.Assert.*
import org.junit.Test

class P7MiniMapLeaseTest {
    private class Host(var active: Boolean=true):P7MiniMapLease.Host {
        val calls=mutableListOf<String>();var marker=false;var failStop=false;var failStart=false;var failRebuild=false
        override fun running()=active
        override fun remember(){calls+="remember";marker=true}
        override fun stop(){calls+="stop";active=false;if(failStop)throw java.io.IOException()}
        override fun start(){
            calls+="start";if(!failStart)active=true
            if(failRebuild)throw java.io.IOException("surface_rebuild_failed")
        }
        override fun forget(){calls+="forget";marker=false}
    }
    @Test fun pauseAndResumeOnlyOwnedProducer(){
        val h=Host();val l=P7MiniMapLease(h,false)
        assertTrue(l.acquire());assertTrue(h.marker);assertEquals(listOf("remember","stop"),h.calls)
        assertTrue(l.acquire());assertEquals(2,h.calls.size)
        assertTrue(l.release());assertTrue(h.active);assertFalse(h.marker)
        assertTrue(l.release());assertEquals(listOf("remember","stop","start","forget"),h.calls)
    }
    @Test fun missingNativeServiceIsNotStartedOnExit(){
        val h=Host(false);val l=P7MiniMapLease(h,false)
        assertTrue(l.acquire());assertTrue(l.release());assertTrue(h.calls.isEmpty())
    }
    @Test fun stopExceptionStillLeavesRecoveryObligation(){
        val h=Host();h.failStop=true;val l=P7MiniMapLease(h,false)
        assertThrows(java.io.IOException::class.java){l.acquire()}
        assertTrue(h.marker);assertTrue(l.release());assertTrue(h.active)
    }
    @Test fun restartFailureDoesNotEraseRecovery(){
        val h=Host();val l=P7MiniMapLease(h,false);l.acquire();h.failStart=true
        assertFalse(l.release());assertTrue(h.marker)
        h.failStart=false;assertTrue(l.release());assertFalse(h.marker)
    }
    @Test fun guardRestartRecoversPersistedLease(){
        val h=Host(false);h.marker=true;val l=P7MiniMapLease(h,true)
        assertTrue(l.release());assertTrue(h.active);assertEquals(listOf("start","forget"),h.calls)
    }
    @Test fun nativeRestartDuringPlaybackIsPausedAgain(){
        val h=Host();val l=P7MiniMapLease(h,false);l.acquire();h.active=true
        assertTrue(l.acquire());assertEquals(2,h.calls.count{it=="stop"});l.release();assertTrue(h.active)
    }
    @Test fun runningServiceWithFailedSurfaceRebuildKeepsRecoveryObligation(){
        val h=Host();val l=P7MiniMapLease(h,false)
        assertTrue(l.acquire());h.failRebuild=true
        assertThrows(java.io.IOException::class.java){l.release()}
        assertTrue(h.active);assertTrue(h.marker);assertFalse(h.calls.contains("forget"))
        h.failRebuild=false
        assertTrue(l.release());assertFalse(h.marker)
        assertEquals(2,h.calls.count{it=="start"})
        assertEquals(1,h.calls.count{it=="forget"})
    }
}
