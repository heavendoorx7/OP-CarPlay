package com.shilapi.xcertplay;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shell guardian's narrow lane-display adapter. Observes XUI; writes only the lane field directly
 * to the existing ICM API, so its clear/restore messages never feed back into the XUI observer.
 * No road text, coordinates or raw OEM JSON is retained or logged.
 */
final class P7NativeLaneBridge implements AutoCloseable {
    private static final String ROOT="com.xiaopeng.xuimanager.IXUIService";
    private static final String INFO="com.xiaopeng.xuimanager.contextinfo.IContextInfo";
    private static final String EVENTS=INFO+"EventListener";
    private static final File JOURNAL=new File("/data/local/tmp/op-instrument-lane-v1");
    private static final File TEMP=new File("/data/local/tmp/op-instrument-lane-v1.tmp");
    private static final String TAG="OPNativeLane";
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"op-native-lane");t.setDaemon(true);return t;
    });
    private P7LaneLease lease;
    private IBinder info;
    private Object vehicle;
    private Method send;
    private String bootId;
    private volatile boolean available, closed, requested, registered;
    private volatile long callbacks;
    private int lastStatus=-1;

    private final Binder listener=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(EVENTS);return true;}
            if(code<1 || code>40)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(EVENTS);
            callbacks++;
            if(closed)return true;
            try {
                if(code==12){
                    String text=data.readString();
                    if(text!=null && text.length()<=65536){
                        JSONObject json=new JSONObject(text);
                        JSONObject lane=json.optJSONObject("lane");
                        // Cruise lane messages can carry msgType=0 as well. The lane data
                        // itself wins; only the separate onMsgEvent callback denotes a reset.
                        if(lane!=null)accept(parse(lane));
                    }
                }else if(code==6){
                    if(data.readInt()!=0){
                        boolean show=data.readByte()!=0;
                        int type=data.readInt();
                        int[] back=readArray(data),front=readArray(data),toll=readArray(data);
                        data.readInt(); // OEM mode, not a lane visibility flag.
                        accept(new P7LaneLease.Snapshot(show,type,front,back,toll));
                    }
                }else if(code==14){
                    int message=data.readInt();
                    if(message==0||message==255)accept(P7LaneLease.Snapshot.empty());
                }
            }catch(Exception ignored){/* Reject malformed or unfamiliar data without changing the OEM display. */}
            return true;
        }
    };

    P7NativeLaneBridge() {
        try {
            checkFile(JOURNAL);checkFile(TEMP);
            bootId=new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()),StandardCharsets.US_ASCII).trim();
            if(!bootId.matches("[a-f0-9-]{36}"))throw new IOException();
            IBinder carBinder=service("car_service","android.car.ICar");
            Object car=Class.forName("android.car.ICar$Stub").getMethod("asInterface",IBinder.class).invoke(null,carBinder);
            IBinder vb=(IBinder)Class.forName("android.car.ICar").getMethod("getCarService",String.class).invoke(car,"xp_vehicle");
            String descriptor="android.car.hardware.XpVehicle.IXpVehicle";
            if(vb==null||!descriptor.equals(vb.getInterfaceDescriptor()))throw new IOException();
            vehicle=Class.forName(descriptor+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,vb);
            send=Class.forName(descriptor).getMethod("setIcmNavigationInfo",String.class);
            recover();
            lease=new P7LaneLease(new P7LaneLease.Host(){
                public void remember(P7LaneLease.Snapshot value)throws Exception{save(value);}
                public void write(P7LaneLease.Snapshot value)throws Exception{send.invoke(vehicle,value.json());}
                public void forget()throws Exception{deleteJournal();}
            });
            try{
                IBinder root=service("XuiServiceManager",ROOT);
                Parcel a=Parcel.obtain(),b=Parcel.obtain();
                try{
                    a.writeInterfaceToken(ROOT);a.writeString("contextinfo");
                    if(!root.transact(1,a,b,0))throw new IOException();
                    b.readException();info=b.readStrongBinder();
                    if(info==null||!INFO.equals(info.getInterfaceDescriptor()))throw new IOException();
                }finally{a.recycle();b.recycle();}
                subscribe(1);registered=true;
            }catch(Exception e){
                // Clearing is still useful without an observer; release then waits for OEM data.
                Log.i(TAG,"observer_unavailable");
            }
            available=true;
            executor.scheduleWithFixedDelay(()->{
                if(closed)return;
                lease.tick(SystemClock.elapsedRealtime());
                report();
            },40,40,TimeUnit.MILLISECONDS);
        }catch(Exception e){
            available=false;
            Log.i(TAG,"observer_unavailable");
        }
    }
    private void accept(P7LaneLease.Snapshot value) {
        P7LaneLease current=lease;
        if(!closed && current!=null)current.received(value,SystemClock.elapsedRealtime());
    }
    void setActive(boolean value) {
        requested=value;
        if(lease!=null)lease.setActive(value && available && !closed);
        report();
    }
    int status(){return !available && requested?P7LaneLease.UNAVAILABLE:lease==null?P7LaneLease.OFF:lease.status();}
    long callbacks(){return callbacks;}
    long events(){return lease==null?0:lease.events();}
    long hides(){return lease==null?0:lease.hides();}
    long restores(){return lease==null?0:lease.restores();}
    String diagnostics() {
        return "state="+status()+" callbacks="+callbacks+" laneEvents="+(lease==null?0:lease.events())+
            "hideCalls="+(lease==null?0:lease.hides())+" restoreCalls="+(lease==null?0:lease.restores());
    }
    private synchronized void report() {
        int status=status();
        if(status!=lastStatus){lastStatus=status;Log.i(TAG,diagnostics());}
    }
    public void close() {
        closed=true;executor.shutdownNow();
        if(lease!=null) {
            lease.setActive(false);
            for(int i=0;i<3;i++)if(lease.restore())break;
        }
        if(registered)try{subscribe(2);registered=false;}catch(Exception ignored){}
        report();
    }
    private static IBinder service(String name,String descriptor)throws Exception{
        IBinder b=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
        if(b==null||!descriptor.equals(b.getInterfaceDescriptor()))throw new IOException();
        return b;
    }
    private void subscribe(int command)throws Exception{
        if(command!=1&&command!=2)throw new IOException();
        Parcel a=Parcel.obtain(),b=Parcel.obtain();
        try {
            a.writeInterfaceToken(INFO);a.writeStrongBinder(listener);
            if(!info.transact(command,a,b,0))throw new IOException();
            b.readException();
        }finally{a.recycle();b.recycle();}
    }
    private static int[] readArray(Parcel data){
        int n=data.readInt();
        if(n==-1)return new int[0];
        if(n<0||n>7)throw new IllegalArgumentException();
        int[] result=new int[n];for(int i=0;i<n;i++)result[i]=data.readInt();
        return result;
    }
    static P7LaneLease.Snapshot parse(JSONObject lane)throws Exception{
        Object show=lane.opt("isLaneShow"),type=lane.opt("laneType");
        if(type==null && Boolean.FALSE.equals(show))type=0;
        if(!(show instanceof Boolean)||!(type instanceof Integer))throw new IllegalArgumentException();
        return new P7LaneLease.Snapshot((Boolean)show,(Integer)type,
            array(lane,"frontLane"),array(lane,"backLane"),array(lane,"tollGateInfo"));
    }
    private static int[] array(JSONObject json,String key)throws Exception{
        if(!json.has(key)||json.isNull(key))return new int[0];
        JSONArray a=json.getJSONArray(key);
        if(a.length()>7)throw new IllegalArgumentException();
        int[] result=new int[a.length()];
        for(int i=0;i<result.length;i++){
            Object value=a.get(i);
            if(!(value instanceof Integer))throw new IllegalArgumentException();
            result[i]=(Integer)value;
        }
        return result;
    }
    private static void checkFile(File f)throws Exception{
        if(Files.isSymbolicLink(f.toPath()))throw new IOException();
        if(f.exists() && (Os.stat(f.getPath()).st_uid!=2000 || !f.isFile() || f.length()>2048))throw new IOException();
    }
    private void save(P7LaneLease.Snapshot value)throws Exception{
        checkFile(JOURNAL);checkFile(TEMP);
        String text=bootId+"\n"+SystemClock.elapsedRealtime()+"\n"+(value==null?"await_oem":value.json());
        try(FileOutputStream out=new FileOutputStream(TEMP)){
            Os.chmod(TEMP.getPath(),0600);
            out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();
        }
        Os.rename(TEMP.getPath(),JOURNAL.getPath());
    }
    private void recover()throws Exception{
        if(!JOURNAL.exists())return;
        String[] parts=new String(Files.readAllBytes(JOURNAL.toPath()),StandardCharsets.UTF_8).split("\n",3);
        long now=SystemClock.elapsedRealtime();
        // After a long outage or reboot the saved junction may no longer be current.
        // Never replay stale arrows. Normal exit retains the live subscription until restoration.
        if(parts.length==3 && bootId.equals(parts[0])){
            long saved=Long.parseLong(parts[1]);
            if(!"await_oem".equals(parts[2]) && now>=saved && now-saved<=5000)
                send.invoke(vehicle,parse(new JSONObject(parts[2]).getJSONObject("lane")).json());
        }
        deleteJournal();
    }
    private static void deleteJournal()throws Exception{
        checkFile(JOURNAL);
        if(JOURNAL.exists()&&!JOURNAL.delete())throw new IOException();
    }
}
