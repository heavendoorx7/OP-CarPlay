package com.shilapi.xcertplay

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class P7LaneLeaseTest {
    private class Host: P7LaneLease.Host {
        val writes=mutableListOf<String>()
        var journal:String?=null
        var saves=0
        var failPersist=false
        var failWrite=false
        var failForget=false
        override fun remember(value:P7LaneLease.Snapshot?){
            if(failPersist)throw IOException()
            journal=value?.json()?:"await_oem"
            saves++
        }
        override fun write(value:P7LaneLease.Snapshot){
            writes+=value.json()
            if(failWrite)throw IOException()
        }
        override fun forget(){if(failForget)throw IOException();journal=null}
    }
    private fun lane(id:Int=1)=P7LaneLease.Snapshot(true,0,intArrayOf(id),intArrayOf(2),null)
    @Test fun alreadyVisibleStripIsClearedWithoutANewCallback() {
        val h=Host();val l=P7LaneLease(h)
        l.setActive(true);l.tick(10000)
        assertEquals(P7LaneLease.HIDDEN,l.status())
        assertEquals(listOf(P7LaneLease.Snapshot.empty().json()),h.writes)
        assertEquals("await_oem",h.journal);assertEquals(0L,l.events())
    }
    @Test fun unknownSnapshotHandsBackWithoutInventingArrowsOrContinuingClears() {
        val h=Host();val l=P7LaneLease(h);l.setActive(true);l.tick(0)
        l.setActive(false);l.tick(1000);l.tick(2000)
        assertEquals(1,h.writes.size);assertNull(h.journal)
        assertEquals(0L,l.restores());assertEquals(P7LaneLease.HANDED_BACK,l.status())
        l.received(lane(),2100);l.tick(3000)
        assertEquals(1,h.writes.size)
    }
    @Test fun periodicClearMaintainsOwnershipWithoutRepeatedJournalWrites() {
        val h=Host();val l=P7LaneLease(h);l.setActive(true);l.tick(0)
        l.tick(999);assertEquals(1,h.writes.size)
        l.setActive(true);l.tick(1000);assertEquals(2,h.writes.size)
        l.tick(1999);assertEquals(2,h.writes.size)
        l.tick(2000);assertEquals(3,h.writes.size);assertEquals(1,h.saves)
        l.setActive(false);l.tick(4000);assertEquals(3,h.writes.size)
        l.setActive(true);l.tick(4000)
        assertEquals(4,h.writes.size);assertEquals(2,h.saves)
    }
    @Test fun saveBeforeClearAndRestoreOnlyLatestNativeSnapshot() {
        val h=Host();val l=P7LaneLease(h)
        l.setActive(true);l.received(lane(),0);l.tick(100)
        assertEquals(lane().json(),h.journal)
        assertEquals(listOf(P7LaneLease.Snapshot.empty().json()),h.writes)
        assertEquals(P7LaneLease.HIDDEN,l.status())
        l.received(lane(3),200);l.tick(300);l.setActive(false)
        assertEquals(lane(3).json(),h.writes.last());assertNull(h.journal)
        assertEquals(1L,l.restores());assertEquals(2L,l.hides())
    }
    @Test fun inactiveObserverDoesNotWrite() {
        val h=Host();val l=P7LaneLease(h);l.received(lane(),0);l.tick(100)
        assertTrue(h.writes.isEmpty());assertEquals(P7LaneLease.OFF,l.status())
    }
    @Test fun nativeClearReplacesOldArrowForRestoration() {
        val h=Host();val l=P7LaneLease(h);l.setActive(true);l.received(lane(),0);l.tick(100)
        l.received(P7LaneLease.Snapshot.empty(),200);l.tick(300);l.setActive(false)
        assertEquals(P7LaneLease.Snapshot.empty().json(),h.writes.last())
    }
    @Test fun knownEmptyStateStillClearsAndRestoresOnlyAnEmptyLane() {
        val h=Host();val l=P7LaneLease(h);l.setActive(true)
        l.received(P7LaneLease.Snapshot.empty(),0);l.tick(100)
        assertEquals(P7LaneLease.HIDDEN,l.status())
        l.setActive(false)
        assertEquals(List(2){P7LaneLease.Snapshot.empty().json()},h.writes)
        assertEquals(1L,l.restores())
    }
    @Test fun duplicateEventsCoalesceAndDoNotStarveUnderContinuousUpdates() {
        val h=Host();val l=P7LaneLease(h);l.setActive(true)
        l.tick(0)
        for(i in 1..6){l.received(lane(),i*10L);l.tick(i*10L)}
        assertEquals(1,h.writes.size)
        l.received(lane(3),70);l.tick(70)
        assertEquals(2,h.writes.size);assertEquals(lane(3).json(),h.journal)
        l.tick(1069);assertEquals(2,h.writes.size)
        l.tick(1070);assertEquals(3,h.writes.size)
        assertEquals(2,h.saves)
    }
    @Test fun noWriteWhenRecoveryCannotBeSaved() {
        val h=Host();h.failPersist=true;val l=P7LaneLease(h);l.setActive(true)
        l.received(lane(),0);l.tick(100)
        assertTrue(h.writes.isEmpty());assertEquals(P7LaneLease.UNAVAILABLE,l.status())
    }
    @Test fun UncertainWriteStillRequiresRecoveryAndRetriesAreBounded() {
        val h=Host();h.failWrite=true;val l=P7LaneLease(h);l.setActive(true)
        l.received(lane(),0);l.tick(100)
        assertNotNull(h.journal);assertEquals(P7LaneLease.RESTORE_PENDING,l.status())
        l.tick(200);val n=h.writes.size
        l.tick(210);l.tick(220);assertEquals(n,h.writes.size)
        h.failWrite=false;assertTrue(l.restore());assertNull(h.journal)
        assertEquals(lane().json(),h.writes.last());assertEquals(P7LaneLease.UNAVAILABLE,l.status())
    }
    @Test fun callbackImmediatelyBeforeExitRestoresNewestEvenWithoutNextHideTick() {
        val h=Host();val l=P7LaneLease(h);l.setActive(true);l.received(lane(),0);l.tick(100)
        l.received(lane(4),200);l.setActive(false)
        assertEquals(lane(4).json(),h.writes.last())
    }
    @Test fun releaseIdempotentAndLaterReacquireWorks() {
        val h=Host();val l=P7LaneLease(h);l.setActive(true);l.received(lane(),0);l.tick(100)
        l.setActive(false);val n=h.writes.size;l.setActive(false);assertTrue(l.restore())
        assertEquals(n,h.writes.size)
        l.setActive(true);l.tick(1000);assertEquals(P7LaneLease.HIDDEN,l.status())
    }
    @Test fun forgetFailureKeepsRecoveryObligation() {
        val h=Host();val l=P7LaneLease(h);l.setActive(true);l.received(lane(),0);l.tick(100)
        h.failForget=true;l.setActive(false)
        assertNotNull(h.journal);assertEquals(P7LaneLease.RESTORE_PENDING,l.status())
        h.failForget=false;assertTrue(l.restore());assertNull(h.journal)
    }
    @Test fun snapshotCannotBeMutatedThroughSourceArrays() {
        val array=intArrayOf(1);val s=P7LaneLease.Snapshot(true,0,array,null,null)
        array[0]=255
        assertTrue(s.json().contains("\"frontLane\":[1]"))
        assertFalse(s.json().contains("msgType"));assertFalse(s.json().contains("maneuver"))
    }
    @Test fun rejectInvalidLaneShapeInsteadOfTruncatingRecovery() {
        assertThrows(IllegalArgumentException::class.java){P7LaneLease.Snapshot(true,0,null,null,null)}
        assertThrows(IllegalArgumentException::class.java){P7LaneLease.Snapshot(true,0,IntArray(8),null,null)}
        assertThrows(IllegalArgumentException::class.java){P7LaneLease.Snapshot(true,2,intArrayOf(1),null,null)}
        assertThrows(IllegalArgumentException::class.java){P7LaneLease.Snapshot(true,0,intArrayOf(256),null,null)}
    }
}
