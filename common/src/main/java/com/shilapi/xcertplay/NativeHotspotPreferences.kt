package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

internal object NativeHotspotPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("op_native_hotspot", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled_v1", false)
    fun paused(context: Context) = prefs(context).getBoolean("paused_until_open_or_boot", false)
    fun supported() = Build.VERSION.SDK_INT == 28 && Build.MODEL.equals("E28", ignoreCase = true)
    fun allowed(context: Context) = HotspotEligibility.allowed(
        supported(), enabled(context), AirPlayPersistence.loadWirelessEnabled(context),
        AirPlayPersistence.loadWirelessHotspotMode(context) == WirelessHotspotMode.MANUAL, paused(context),
    )
    // Small synchronous commits keep exit/off intent across process death.
    fun setEnabled(context: Context, value: Boolean): Boolean =
        prefs(context).edit().putBoolean("enabled_v1", value).commit()
    fun pause(context: Context): Boolean =
        prefs(context).edit().putBoolean("paused_until_open_or_boot", true).commit()
    fun resume(context: Context): Boolean =
        prefs(context).edit().putBoolean("paused_until_open_or_boot", false).commit()
}
