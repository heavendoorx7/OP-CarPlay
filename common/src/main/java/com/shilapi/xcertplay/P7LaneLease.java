package com.shilapi.xcertplay;

import java.util.Arrays;

/** Owns only the OEM junction lane strip, never navigation enable, maneuver or map fields. */
final class P7LaneLease {
    static final int OFF=0, WAITING=1, HIDDEN=2, RESTORE_PENDING=3, UNAVAILABLE=4, NATIVE_EMPTY=5, HANDED_BACK=6;
    static final class Snapshot {
        final boolean show;
        final int type;
        private final int[] front, back, toll;
        Snapshot(boolean show, int type, int[] front, int[] back, int[] toll) {
            if(type<0 || type>1)throw new IllegalArgumentException();
            this.show=show;this.type=type;
            this.front=copy(front);this.back=copy(back);this.toll=copy(toll);
            if(show && this.front.length+this.back.length+this.toll.length==0)throw new IllegalArgumentException();
        }
        private static int[] copy(int[] array) {
            if(array==null)return new int[0];
            if(array.length>7)throw new IllegalArgumentException();
            for(int value:array)if(value < -1 || value>255)throw new IllegalArgumentException();
            return array.clone();
        }
        static Snapshot empty(){return new Snapshot(false,0,null,null,null);}
        String json() {
            return "{\"lane\":{\"isLaneShow\":"+show+",\"laneType\":"+type+
                ",\"frontLane\":"+Arrays.toString(front)+",\"backLane\":"+Arrays.toString(back)+
                ",\"tollGateInfo\":"+Arrays.toString(toll)+"}}";
        }
        boolean visible(){return show && front.length+back.length+toll.length>0;}
    }
    interface Host {
        // Null means ownership only: no historical arrows are known and none may be invented.
        void remember(Snapshot value) throws Exception;
        void write(Snapshot value) throws Exception;
        void forget() throws Exception;
    }
    private final Host host;
    private Snapshot latest;
    private boolean active, modified, failed, handedBack;
    private long generation, applied=-1, changedAt, nextRestoreAt, nextRefreshAt;
    private long events, hides, restores;
    P7LaneLease(Host host){this.host=host;}

    synchronized void received(Snapshot value,long now) {
        if(latest==null || generation==applied)changedAt=now;
        latest=value;generation++;events++;
    }
    synchronized void setActive(boolean value) {
        if(value && !active){handedBack=false;nextRefreshAt=0;}
        active=value;
        if(!value)restore();
    }
    synchronized void tick(long now) {
        if(!active || failed){
            if(modified && now>=nextRestoreAt){nextRestoreAt=now+1000;restore();}
            return;
        }
        if(modified && generation==applied && now<nextRefreshAt)return;
        if(modified && generation!=applied && now-changedAt<60 && now<nextRefreshAt)return;
        try {
            // Hide immediately even when the already-visible OEM strip predates our listener.
            // Only save on ownership/event changes; periodic clears do not fsync flash every second.
            if(!modified || generation!=applied)host.remember(latest);
            modified=true; // An exception from Binder can still mean the write reached the receiver.
            host.write(Snapshot.empty());
            hides++;applied=generation;nextRefreshAt=now+1000;
        }catch(Exception e){failed=true;restore();}
    }
    synchronized boolean restore() {
        if(!modified)return true;
        try {
            // User accepted handing back to the next OEM update if no original snapshot exists.
            // Never "restore" a fabricated lane or replay an old route from another session.
            if(latest!=null)host.write(latest);
            host.forget();
            modified=false;applied=-1;nextRefreshAt=0;
            if(latest!=null)restores++;else handedBack=true;
            return true;
        }catch(Exception e){return false;}
    }
    synchronized int status() {
        if(modified && (!active || failed))return RESTORE_PENDING;
        if(failed)return UNAVAILABLE;
        if(!active)return handedBack?HANDED_BACK:OFF;
        if(modified)return HIDDEN;
        return WAITING;
    }
    synchronized long events(){return events;}
    synchronized long hides(){return hides;}
    synchronized long restores(){return restores;}
}
