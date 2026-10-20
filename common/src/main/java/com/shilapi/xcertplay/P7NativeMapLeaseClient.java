package com.shilapi.xcertplay;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Only the bundled guardian can own the native bitmap pause; the sender does not stop navigation. */
final class P7NativeMapLeaseClient implements AutoCloseable {
    private LocalSocket socket;
    private long retryAt;
    private boolean held;
    volatile int laneState;
    volatile long laneCallbacks, laneEvents, laneHideCalls, laneRestoreCalls;
    boolean acquire(boolean hideLane) {
        try {
            if(socket==null) {
                if(SystemClock.elapsedRealtime()<retryAt)return false;
                retryAt=SystemClock.elapsedRealtime()+10000;
                // Same trusted APK and system classpath as this worker; no caller-provided code.
                String cp=System.getenv("CLASSPATH");
                if(cp==null || cp.indexOf("/data/app/")!=0)throw new IOException();
                ProcessBuilder b=new ProcessBuilder("/system/bin/nohup","/system/bin/app_process","/system/bin",
                    "--nice-name=op-p7-map-guard",P7NativeMapGuard.class.getName());
                b.environment().put("CLASSPATH",cp);
                b.redirectInput(new java.io.File("/dev/null"));b.redirectOutput(new java.io.File("/dev/null"));b.redirectError(new java.io.File("/dev/null"));b.start();
                for(int i=0;i<20&&socket==null;i++) {
                    LocalSocket next=new LocalSocket();
                    try {
                        next.connect(new LocalSocketAddress(P7NativeMapGuard.SOCKET,LocalSocketAddress.Namespace.ABSTRACT));
                        if(next.getPeerCredentials().getUid()!=2000)throw new SecurityException();
                        next.setSoTimeout(4500);socket=next;
                    }catch(IOException e){next.close();SystemClock.sleep(100);}
                }
            }
            if(socket==null)return false;
            held=command(held?2:1,hideLane);return held;
        }catch(Exception e){close();return false;}
    }
    void release() {
        if(socket==null)return;
        try {if(held && !command(3,false)){close();return;}held=false;}
        catch(Exception e){close();}
        // No need to keep a privileged guard alive while no usable map exists.
        close();
    }
    private boolean command(int value,boolean hideLane) throws IOException {
        DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeInt(value);
        if(value==1||value==2)out.writeBoolean(hideLane);
        out.flush();
        DataInputStream in=new DataInputStream(socket.getInputStream());
        boolean ok=in.readInt()==1;
        laneState=in.readInt();laneCallbacks=in.readLong();laneEvents=in.readLong();
        laneHideCalls=in.readLong();laneRestoreCalls=in.readLong();
        return ok;
    }
    public void close() {try{if(socket!=null)socket.close();}catch(Exception ignored){}socket=null;held=false;}
}
