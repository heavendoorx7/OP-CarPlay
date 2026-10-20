package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** E28 Android 9's own tethering service, always with this application's package/UID.
 * No shell, credentials, AP reconfiguration, Wi-Fi toggle, or local-only fallback.
 */
internal class NativeHotspotApi(private val context: Context) {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var prepared = false

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    fun prepare() {
        if (prepared) return
        check(NativeHotspotPreferences.supported())
        // Resolve only the five tethering members. This changes reflection access
        // inside this process, not Android's permission checks or any system setting.
        val lookup = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, emptyArray<Class<*>>().javaClass)
        val runtimeClass = Class.forName("dalvik.system.VMRuntime")
        val getter = lookup.invoke(runtimeClass, "getRuntime", emptyArray<Class<*>>()) as java.lang.reflect.Method
        val exemptions = lookup.invoke(runtimeClass, "setHiddenApiExemptions", arrayOf(Array<String>::class.java)) as java.lang.reflect.Method
        exemptions.invoke(getter.invoke(null), arrayOf(
            "Landroid/net/ConnectivityManager;->mService",
            "Landroid/net/ConnectivityManager;->getTetheredIfaces",
            "Landroid/net/ConnectivityManager;->getTetherableWifiRegexs",
            "Landroid/net/IConnectivityManager;->startTethering",
            "Landroid/net/wifi/WifiManager;->getWifiApState",
        ) as Any)
        prepared = true
    }

    fun state(): Int = WifiManager::class.java.getDeclaredMethod("getWifiApState").apply { isAccessible = true }.invoke(wifi) as Int

    fun interfaceReady(): Boolean {
        val names = ConnectivityManager::class.java.getDeclaredMethod("getTetheredIfaces").apply { isAccessible = true }.invoke(connectivity) as Array<*>
        val patterns = ConnectivityManager::class.java.getDeclaredMethod("getTetherableWifiRegexs").apply { isAccessible = true }.invoke(connectivity) as Array<*>
        return names.filterIsInstance<String>().any { name ->
            patterns.filterIsInstance<String>().any { Regex(it).matches(name) } &&
                NetworkInterface.getByName(name)?.let { network ->
                    network.isUp && Collections.list(network.inetAddresses).any {
                        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress
                    }
                } == true
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    fun start(handler: Handler, callback: (Int) -> Unit) {
        val field = ConnectivityManager::class.java.getDeclaredField("mService").apply { isAccessible = true }
        val service = field.get(connectivity)
        val result = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { callback(resultCode) }
        }
        // Same Binder path used by ConnectivityManager.startTethering on the saved E28 framework.
        // false avoids launching carrier provisioning UI; this firmware has no provisioning app.
        Class.forName("android.net.IConnectivityManager").getDeclaredMethod("startTethering",
            Int::class.javaPrimitiveType, ResultReceiver::class.java, Boolean::class.javaPrimitiveType,
            String::class.java).apply { isAccessible = true }.invoke(service, 0, result, false, context.packageName)
    }
}
