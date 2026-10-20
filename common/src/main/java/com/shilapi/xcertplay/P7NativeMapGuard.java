package com.shilapi.xcertplay;

import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import java.io.*;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Independent lease guardian: worker death/EOF/timeout restores the one native bitmap service. */
public final class P7NativeMapGuard {
    static final String SOCKET="op.p7.instrument.native-lease.v2";
    static final String PACKAGE="com.xiaopeng.montecarlo";
    static final String CLASS="com.xiaopeng.montecarlo.service.minimap.MiniMapService";
    private static final String COMPONENT=PACKAGE+"/"+CLASS;
    private static final File JOURNAL=new File("/data/local/tmp/op-instrument-native-lease-v1");
    private static volatile long tick=SystemClock.elapsedRealtime();
    private static volatile boolean closing;
    private static volatile LocalSocket client;
    private static P7MiniMapLease lease;
    private static P7NativeLaneBridge lanes;

    public static void main(String[] args) {
        LocalServerSocket server=null;
        try {
            if(args.length!=0 || Process.myUid()!=2000 || Build.VERSION.SDK_INT!=28 || !"E28".equals(Build.MODEL))return;
            verifyRuntimeAccess();
            server=new LocalServerSocket(SOCKET);
            final LocalServerSocket listener=server;
            lease=new P7MiniMapLease(new FactoryHost(),JOURNAL.exists());
            if(!lease.release())return;
            Runtime.getRuntime().addShutdownHook(new Thread(P7NativeMapGuard::restore,"op-map-recovery"));
            Thread watchdog=new Thread(()->{
                while(!closing) {
                    SystemClock.sleep(500);
                    if(SystemClock.elapsedRealtime()-tick>12000) {
                        closing=true;
                        try {if(client!=null)client.close();listener.close();}catch(Exception ignored){}
                        restore();System.exit(0);
                    }
                }
            },"op-map-lease-watchdog");watchdog.setDaemon(true);watchdog.start();
            lanes=new P7NativeLaneBridge();
            client=server.accept();
            if(client.getPeerCredentials().getUid()!=2000)return;
            client.setSoTimeout(4000);
            DataInputStream input=new DataInputStream(client.getInputStream());
            DataOutputStream output=new DataOutputStream(client.getOutputStream());
            long lastCheck=0;
            while(!closing) {
                int command=input.readInt();tick=SystemClock.elapsedRealtime();
                if(command==0)break;
                boolean ok;
                if(command==1) {
                    boolean hideLane=input.readBoolean();
                    ok=lease.acquire();lastCheck=tick;
                    lanes.setActive(ok && hideLane);
                }
                else if(command==2) {
                    boolean hideLane=input.readBoolean();
                    ok=true;
                    if(tick-lastCheck>=1000){ok=lease.acquire();lastCheck=tick;}
                    lanes.setActive(ok && hideLane);
                } else if(command==3){lanes.setActive(false);ok=lease.release();}
                else break;
                output.writeInt(ok?1:0);
                output.writeInt(lanes.status());output.writeLong(lanes.callbacks());
                output.writeLong(lanes.events());output.writeLong(lanes.hides());output.writeLong(lanes.restores());
                output.flush();
            }
        }catch(Throwable ignored){}
        finally {
            closing=true;restore();
            try {if(client!=null)client.close();if(server!=null)server.close();}catch(Exception ignored){}
        }
        System.exit(0);
    }

    private static void verifyRuntimeAccess() throws Exception {
        if(Files.isSymbolicLink(JOURNAL.toPath()))throw new SecurityException();
        Object am=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Object allowed=Class.forName("android.app.IActivityManager")
            .getMethod("checkPermission",String.class,int.class,int.class)
            .invoke(am,"android.permission.REAL_GET_TASKS",Process.myPid(),Process.myUid());
        if(!Integer.valueOf(PackageManager.PERMISSION_GRANTED).equals(allowed))throw new SecurityException();
        // Compatibility is based on the fixed service's observed runtime state,
        // rather than one vehicle's package version, signer or manifest flags.
        // acquire() still confirms stop before output; release() still restores
        // only a producer this lease actually paused.
    }
    private static synchronized void restore() {
        if(lanes!=null)lanes.close();
        if(lease==null)return;
        for(int i=0;i<3;i++)try {if(lease.release())return;}catch(Exception ignored){}
        // Keep the fixed recovery marker if restoration cannot be confirmed.
    }
    private static class FactoryHost implements P7MiniMapLease.Host {
        public boolean running() throws Exception {
            Object am=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            List<?> services=(List<?>)Class.forName("android.app.IActivityManager")
                .getMethod("getServices",int.class,int.class).invoke(am,1024,0);
            if(services==null || services.size()>=1024)throw new IOException();
            for(Object item:services) {
                ActivityManager.RunningServiceInfo s=(ActivityManager.RunningServiceInfo)item;
                if(s.service!=null&&PACKAGE.equals(s.service.getPackageName())&&CLASS.equals(s.service.getClassName())&&s.started)return true;
            }
            return false;
        }
        public void remember() throws Exception {
            try(FileOutputStream out=new FileOutputStream(JOURNAL)){out.write("restore_minimap_v1\n".getBytes("UTF-8"));out.getFD().sync();}
            Os.chmod(JOURNAL.getPath(),0600);
        }
        public void forget() throws Exception {if(JOURNAL.exists()&&!JOURNAL.delete())throw new IOException();}
        public void stop() throws Exception {command(false);await(false);}
        public void start() throws Exception {command(true);await(true);}
        private void await(boolean expected) throws Exception {
            for(int i=0;i<8;i++){if(running()==expected)return;SystemClock.sleep(100);}
            throw new IOException("native_state_not_confirmed");
        }
        private void command(boolean restore) throws Exception {
            java.util.ArrayList<String> args=new java.util.ArrayList<>(java.util.Arrays.asList(
                "/system/bin/am",restore?"startservice":"stopservice","--user","0","-n",COMPONENT));
            if(restore) {
                // E28's singleton helper retains its released LocalMiniMapSurface after
                // onDestroy. A plain startservice leaves the service running without
                // frames. The OEM RESET action recreates and rebinds its ImageReader.
                args.add("-a");args.add("com.xiaopeng.montecarlo.minimap.RESET");
            }
            java.lang.Process p=new ProcessBuilder(args)
                .redirectErrorStream(true).start();
            try {
                // This P7 returns 255 with "Service stopped" for am stopservice.
                // Only the following runtime-state confirmation decides success;
                // a zero shell exit alone must not permit instrument output either.
                if(!p.waitFor(3,TimeUnit.SECONDS))throw new IOException("native_command_timeout");
                // Do not erase the recovery journal if the rebuild request failed,
                // even when ActivityManager still reports the service as started.
                if(restore && p.exitValue()!=0)throw new IOException("native_rebuild_rejected");
            }
            finally {p.destroy();p.getInputStream().close();p.getOutputStream().close();p.getErrorStream().close();}
        }
    }
}
