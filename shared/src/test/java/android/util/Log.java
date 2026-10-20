package android.util;

/** Host-side logger only. Networking, crypto, session and stream code remain real in JVM tests. */
public final class Log {
    public static int i(String tag, String msg) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable t) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable t) { return 0; }
}
