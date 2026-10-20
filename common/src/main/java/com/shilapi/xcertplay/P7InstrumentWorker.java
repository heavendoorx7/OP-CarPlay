package com.shilapi.xcertplay;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.json.JSONObject;

/**
 * Shell-only navigation-image worker with an optional fixed OEM lane-display lease.
 * The app owns the frame socket; closing that socket ends this process. No boot installation.
 */
public final class P7InstrumentWorker {
    private static final String PACKAGE = "com.shilapi.xcertplay.p7";
    private static final String FRAMES = "op.p7.instrument.frames.v1";
    private static final String CONTROL = "op.p7.instrument.control.v1";
    private static final int MAGIC = 0x4f504332;
    private static volatile long progress = SystemClock.elapsedRealtime();
    private static volatile String state = "starting";
    private static volatile long frames;
    private static volatile int width, height;
    private static int appUid;
    private static Object vehicle, shared;
    private static Method readRequest, queue;
    private static final P7NativeMapLeaseClient nativeMap=new P7NativeMapLeaseClient();
    private static volatile long repeatedFrames, missingFrames;
    private static volatile int requestedFps;


    public static void main(String[] args) {
        LocalServerSocket control = null;
        try {
            if (Process.myUid() != 2000 || Build.VERSION.SDK_INT != 28 || !"E28".equals(Build.MODEL)) return;
            if (args.length == 1 && "--status".equals(args[0])) { printStatus(); return; }
            if (args.length != 0) return;
            Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
            ApplicationInfo app = (ApplicationInfo) Class.forName("android.content.pm.IPackageManager")
                .getMethod("getApplicationInfo", String.class, int.class, int.class).invoke(pm, PACKAGE, 0, 0);
            if (app == null || app.uid < 10000 || app.uid >= 100000) return;
            appUid = app.uid;
            control = new LocalServerSocket(CONTROL); // Kernel-enforced single instance.
            final LocalServerSocket server = control;
            Thread status = new Thread(() -> serveStatus(server), "op-instrument-status");
            status.setDaemon(true); status.start();
            Thread watchdog = new Thread(() -> {
                while (true) {
                    SystemClock.sleep(1000);
                    if (SystemClock.elapsedRealtime() - progress > 10000) System.exit(124);
                }
            }, "op-instrument-watchdog");
            watchdog.setDaemon(true); watchdog.start();
            initializeVendor();
            runFrames();
        } catch (Throwable ignored) {
            // Deliberately do not log map data, paths, requests, or exception messages.
        } finally {
            nativeMap.release();
            if (control != null) try { control.close(); } catch (Exception ignored) {}
        }
        System.exit(0);
    }

    private static void printStatus() throws Exception {
        try (LocalSocket socket=new LocalSocket()) {
            socket.connect(new LocalSocketAddress(CONTROL,LocalSocketAddress.Namespace.ABSTRACT));
            if(socket.getPeerCredentials().getUid()!=2000)throw new SecurityException();
            socket.setSoTimeout(1500);
            String raw=new DataInputStream(socket.getInputStream()).readUTF();
            JSONObject status=new JSONObject(raw);
            if(status.optInt("protocol")!=1 || status.optInt("uid")!=2000)throw new SecurityException();
            System.out.println("OP_INSTRUMENT_STATUS:"+status.toString());
        }
    }

    private static void initializeVendor() throws Exception {
        Object activity = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Object permission = Class.forName("android.app.IActivityManager")
            .getMethod("checkPermission", String.class, int.class, int.class)
            .invoke(activity, "android.car.permission.CAR_VENDOR_EXTENSION", Process.myPid(), Process.myUid());
        if (!Integer.valueOf(PackageManager.PERMISSION_GRANTED).equals(permission)) throw new SecurityException();
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "car_service");
        if (binder == null || !"android.car.ICar".equals(binder.getInterfaceDescriptor())) throw new SecurityException();
        Object car = Class.forName("android.car.ICar$Stub").getMethod("asInterface", IBinder.class).invoke(null,binder);
        Method get = Class.forName("android.car.ICar").getMethod("getCarService",String.class);
        String vt = "android.car.hardware.XpVehicle.IXpVehicle", st = "android.car.xpsharedmemory.IXpSharedMemory";
        IBinder vb = (IBinder) get.invoke(car,"xp_vehicle"), sb = (IBinder) get.invoke(car,"xp_shared_memory");
        if (vb==null || sb==null || !vt.equals(vb.getInterfaceDescriptor()) || !st.equals(sb.getInterfaceDescriptor()))
            throw new SecurityException();
        vehicle = Class.forName(vt+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,vb);
        shared = Class.forName(st+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,sb);
        readRequest = Class.forName(vt).getMethod("getNaviBmpInfoRequiredByIcm");
        queue = Class.forName(st).getMethod("queueMemoryBuffer",int.class,ParcelFileDescriptor.class,int.class);
    }

    private static void runFrames() throws Exception {
        LocalSocket socket = null;
        long until = SystemClock.elapsedRealtime()+20000;
        state="waiting_app";
        while (socket==null && SystemClock.elapsedRealtime()<until) {
            progress=SystemClock.elapsedRealtime();
            LocalSocket attempt = new LocalSocket();
            try {
                attempt.connect(new LocalSocketAddress(FRAMES,LocalSocketAddress.Namespace.ABSTRACT));
                if (attempt.getPeerCredentials().getUid()!=appUid) throw new SecurityException();
                socket=attempt;
            } catch (java.io.IOException e) { attempt.close();SystemClock.sleep(500); }
        }
        if (socket==null) return;
        try {
            socket.setSoTimeout(2500);
            DataInputStream input=new DataInputStream(socket.getInputStream());
            DataOutputStream output=new DataOutputStream(socket.getOutputStream());
            output.writeInt(MAGIC);output.flush();
            if (input.readInt()!=MAGIC) throw new SecurityException();
            long lastSequence=-1;
            while (true) {
                long start=SystemClock.elapsedRealtime();progress=start;
                output.writeInt(2);output.flush();if(input.readInt()!=1) break;
                output.writeInt(3);output.flush();
                boolean hideNativeLane=input.readInt()==1;
                P7ImagePolicy policy;
                try { policy=currentPolicy(); }
                catch (IllegalArgumentException e) {
                    if (!"no_active_request".equals(e.getMessage()) && !"no_request".equals(e.getMessage())) throw e;
                    nativeMap.release();state="waiting_request";SystemClock.sleep(500);continue;
                }
                width=policy.width;height=policy.height;requestedFps=policy.fps;
                output.writeInt(1);output.writeInt(width);output.writeInt(height);output.flush();
                long seq=input.readLong();int length=input.readInt();
                if(length<0 || length>1048576)throw new SecurityException();
                byte[] jpeg=new byte[length];input.readFully(jpeg);
                if(length>0 && seq>=0) {
                    P7ImagePolicy.checkJpeg(jpeg);
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                    BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,bounds);
                    if(bounds.outWidth!=policy.width || bounds.outHeight!=policy.height)throw new SecurityException();
                    P7ImagePolicy fresh;
                    try { fresh=currentPolicy(); }
                    catch(IllegalArgumentException e) {
                        if("no_active_request".equals(e.getMessage()) || "no_request".equals(e.getMessage()))continue;
                        throw e;
                    }
                    if(!policy.sameRequest(fresh)){nativeMap.release();continue;}
                    if(!nativeMap.acquire(hideNativeLane)) {
                        nativeMap.release();state="native_pause_unavailable";
                        SystemClock.sleep(500);continue;
                    }
                    ParcelFileDescriptor fd=(ParcelFileDescriptor)ParcelFileDescriptor.class
                        .getDeclaredMethod("fromData",byte[].class,String.class).invoke(null,jpeg,null);
                    if(fd==null)throw new SecurityException();
                    try { queue.invoke(shared,0x2170161d,fd,jpeg.length);frames++; }
                    finally {fd.close();}
                    if(seq==lastSequence)repeatedFrames++;
                    lastSequence=seq;state="submitting";
                } else {missingFrames++;nativeMap.release();state="waiting_map";}
                progress=SystemClock.elapsedRealtime();
                SystemClock.sleep(Math.max(1,policy.intervalMs-(progress-start)));
            }
        } finally {nativeMap.release();socket.close();}
    }

    private static P7ImagePolicy currentPolicy() throws Exception {
        Object raw=readRequest.invoke(vehicle);
        if(!(raw instanceof String) || ((String)raw).isEmpty())throw new IllegalArgumentException("no_request");
        if(((String)raw).length()>16384)throw new SecurityException();
        JSONObject obj=new JSONObject((String)raw);
        Object need=obj.opt("Need");
        boolean yes=Boolean.TRUE.equals(need) || Integer.valueOf(1).equals(need) || "true".equals(need);
        if(!yes && !(Boolean.FALSE.equals(need) || Integer.valueOf(0).equals(need) || "false".equals(need)))
            throw new SecurityException();
        return new P7ImagePolicy(yes,number(obj,"Size-w"),number(obj,"Size-h"),
            number(obj,"Fps"),number(obj,"Format"));
    }
    private static int number(JSONObject obj,String key)throws Exception {
        Object value=obj.get(key);
        if(value instanceof Integer)return (Integer)value;
        if(value instanceof String && ((String)value).matches("[0-9]{1,5}"))return Integer.parseInt((String)value);
        throw new SecurityException();
    }
    private static void serveStatus(LocalServerSocket server) {
        while(true) {
            LocalSocket accepted;
            try { accepted=server.accept(); } catch (Exception e) { return; }
            try (LocalSocket client=accepted) {
                int uid=client.getPeerCredentials().getUid();
                if(uid!=appUid && uid!=2000)continue;
                client.setSoTimeout(1000);
                JSONObject data=new JSONObject();
                data.put("protocol",1);data.put("uid",2000);data.put("state",state);
                data.put("submittedFrames",frames);data.put("width",width);data.put("height",height);
                data.put("repeatedFrames",repeatedFrames);data.put("missingFrames",missingFrames);data.put("requestedFps",requestedFps);
                data.put("laneState",nativeMap.laneState);data.put("laneCallbacks",nativeMap.laneCallbacks);
                data.put("laneEvents",nativeMap.laneEvents);data.put("laneHideCalls",nativeMap.laneHideCalls);
                data.put("laneRestoreCalls",nativeMap.laneRestoreCalls);
                DataOutputStream output=new DataOutputStream(client.getOutputStream());
                output.writeUTF(data.toString());output.flush();
            } catch (Exception ignored) { /* A client disconnect must not stop status service. */ }
        }
    }
}
