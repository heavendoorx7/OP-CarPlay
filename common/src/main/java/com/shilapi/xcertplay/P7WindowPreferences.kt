package com.shilapi.xcertplay

import android.content.Context
import android.os.Bundle
import android.content.Intent

internal object P7WindowPreferences {
    const val MODE_KEY = "op_fullscreen_bars"
    private val session = FullscreenModeState()

    fun endSession() { session.reset() }
    private fun prefs(context: Context) = context.getSharedPreferences("op_window", Context.MODE_PRIVATE)
    fun defaultBars(context: Context): Boolean = prefs(context).getBoolean("default_fullscreen_bars", false)
    fun saveDefault(context: Context, bars: Boolean) {
        prefs(context).edit().putBoolean("default_fullscreen_bars", bars).apply()
    }
    fun currentBars(context: Context): Boolean = prefs(context).getBoolean("fullscreen_bars", false)
    fun enter(context: Context, saved: Bundle?, intent: Intent): Boolean {
        val restored = saved?.takeIf { it.containsKey(MODE_KEY) }?.getBoolean(MODE_KEY)
        val switched = intent.takeIf { it.hasExtra(MODE_KEY) }?.getBooleanExtra(MODE_KEY, false)
        return session.enter(defaultBars(context), restored, switched).also { select(context, it) }
    }
    fun select(context: Context, bars: Boolean) {
        session.select(bars)
        prefs(context).edit().putBoolean("fullscreen_bars", bars).apply()
    }
}
