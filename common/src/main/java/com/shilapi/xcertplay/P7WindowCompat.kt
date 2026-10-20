package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.ColorFilter
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** E28 firmware 5.1.6 adds its own window flags and split-screen content padding. */
internal class P7WindowCompat private constructor(
    private val activity: Activity,
    private val onGeometryChanged: () -> Unit,
) :
    ViewTreeObserver.OnPreDrawListener {
    private val areaType = WindowManager.LayoutParams::class.java.getField("areaType")
    private val screenId = WindowManager.LayoutParams::class.java.getField("screenId")
    private val nativeWindowFlags = WindowManager.LayoutParams::class.java.getField("windowFlags")
    private val setActivityBars = Activity::class.java.getMethod(
        "setSystemBarVisibility", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
    )
    private val screenManagerClass = Class.forName("com.xiaopeng.screen.ScreenTaskManagerFactory")
    private val screenManager = screenManagerClass.getMethod("get").invoke(null)
    private val showSystemBar = screenManagerClass.getMethod(
        "setSystemBarVisibility", Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
    )
    private val decor = activity.window.decorView
    private val content = activity.findViewById<View>(android.R.id.content)
    private val frameworkRoot = content?.parent as? LinearLayout
    private val statusBarHeight = activity.resources.let { resources ->
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id != 0) resources.getDimensionPixelSize(id) else 0
    }
    // Measured DockUIContainer bounds are y=1080..1200 on this E28 display.
    // Android's generic navigation_bar_height is 198 px in the app's resource
    // configuration, so using it would add a spurious 78 px strip.
    private val dockHeight = 120
    // Actual E28 DockUIContainer type 2102 occupies x=0..120, separate from the bottom Dock.
    private val sideDockWidth = 120
    private var appliedLeftPadding: Int? = null
    private var baseLeftPadding = 0
    private var appliedRightPadding: Int? = null
    private var baseRightPadding = 0
    // E28 getRealSize() is rewritten to the task area when the process first
    // opens in split mode. Display.Mode retains the panel's physical pixels.
    private val displaySize = activity.windowManager.defaultDisplay.mode.let {
        Point(it.physicalWidth, it.physicalHeight)
    }
    private val position = IntArray(2)
    private var disabled = false
    private var requestedState: Pair<String?, Boolean>? = null
    private var lastBackdropLight = false
    private var lastThemeUiMode: Int? = null
    var showBarsFullscreen: Boolean = P7WindowPreferences.currentBars(activity)
        private set
    val isFullscreen: Boolean
        get() = !disabled && runCatching { areaType.get(activity.window.attributes) in FULLSCREEN_AREAS }.getOrDefault(false)
    var viewportDescription: String = "unmeasured"
        private set

    fun toggleFullscreenBars() {
        if (!isFullscreen) return
        showBarsFullscreen = !showBarsFullscreen
        P7WindowPreferences.select(activity, showBarsFullscreen)
        requestedState = null
        requestDock()
        decor.requestLayout()
        decor.invalidate()
    }

    fun measuredViewport(video: View): DisplaySize? {
        if (disabled || !video.isAttachedToWindow) return null
        val root = frameworkRoot ?: return null
        val attrs = activity.window.attributes
        val area = runCatching { areaType.get(attrs) as? String }.getOrNull()
        if (area !in SUPPORTED_AREAS) return null
        val bars = area !in FULLSCREEN_AREAS || showBarsFullscreen
        val rootPosition = IntArray(2).also(root::getLocationOnScreen)
        val videoPosition = IntArray(2).also(video::getLocationOnScreen)
        val measured = p7MeasuredViewport(
            P7PixelBounds(rootPosition[0], rootPosition[1], root.width, root.height),
            P7PixelBounds(videoPosition[0], videoPosition[1], video.width, video.height),
            P7ContentInsets(root.paddingTop, root.paddingBottom), displaySize.y,
            if (bars) statusBarHeight else 0, if (bars) dockHeight else 0,
            root.isLayoutRequested || content?.isLayoutRequested == true || video.isLayoutRequested,
            root.paddingLeft, root.paddingRight,
            if (area in FULLSCREEN_AREAS && bars) sideDockWidth else 0,
        )
        viewportDescription = "area=$area bars=$bars root=${root.width}x${root.height}@${rootPosition[0]},${rootPosition[1]} " +
            "insets=${root.paddingLeft},${root.paddingTop},${root.paddingRight},${root.paddingBottom} " +
            "video=${video.width}x${video.height}@${videoPosition[0]},${videoPosition[1]} " +
            "nativeFlags=${nativeWindowFlags.getInt(attrs)} ready=${measured != null}"
        return measured
    }

    init {
        // The Light.NoActionBar theme paints the vendor-added padding white.
        // Native bars are translucent; reserve their area against a dark background.
        frameworkRoot?.background = object : Drawable() {
            private val paint = Paint()
            override fun draw(canvas: Canvas) {
                canvas.drawColor(Color.BLACK)
                // Native daytime glyphs are dark (#1A1D26). A muted gray reduces
                // the bright strip while preserving contrast; split/night stay black.
                if (lastBackdropLight) {
                    paint.color = Color.rgb(0xB8, 0xBC, 0xC4)
                    canvas.drawRect(0f, 0f, bounds.width().toFloat(),
                        (frameworkRoot?.paddingTop ?: 0).toFloat(), paint)
                }
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.OPAQUE
        }
        decor.viewTreeObserver.addOnPreDrawListener(this)
    }

    fun requestDock(force: Boolean = false) {
        // The vendor service operates on the focused Activity. Never invoke it
        // from a background Activity or after this Activity loses window focus.
        if (disabled || !decor.isAttachedToWindow || !activity.hasWindowFocus()) return
        try {
            val attrs = activity.window.attributes
            if (screenId.getInt(attrs) != 0) return
            val area = areaType.get(attrs) as? String
            if (area !in SUPPORTED_AREAS) return
            val full = area in FULLSCREEN_AREAS
            val dockVisible = !full || showBarsFullscreen
            val themeUiMode = activity.resources.configuration.uiMode
            // E28 UiThemeManager resolves manual/automatic day-night changes into
            // the Activity's Configuration. getNightMode() alone is only a setting.
            val lightBackdrop = full && dockVisible && !isDarkMode(themeUiMode)
            if (lastThemeUiMode != themeUiMode) {
                lastThemeUiMode = themeUiMode
                Log.i(TAG, "Status theme: uiMode=0x${themeUiMode.toString(16)} night=${isDarkMode(themeUiMode)}; follows resolved vehicle configuration")
            }
            if (lightBackdrop != lastBackdropLight) {
                lastBackdropLight = lightBackdrop
                frameworkRoot?.background?.invalidateSelf()
            }
            // Keep the same split policy as before; only fullscreen adds the
            // Android immersive flags as well as requesting hidden native bars.
            val androidUi = if (!dockVisible) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else if (full) 0 else View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            val previousAndroidUi = decor.systemUiVisibility
            val uiChanged = previousAndroidUi != androidUi
            if (uiChanged) decor.systemUiVisibility = androidUi
            val fullscreenFlag = WindowManager.LayoutParams.FLAG_FULLSCREEN
            if (!dockVisible && activity.window.attributes.flags and fullscreenFlag == 0) {
                activity.window.addFlags(fullscreenFlag)
            } else if (dockVisible && activity.window.attributes.flags and fullscreenFlag != 0) {
                activity.window.clearFlags(fullscreenFlag)
            }
            val state = area to dockVisible
            // ActivityProxy can restore hidden-bar bits after configuration changes.
            // Factory.setSystemBarVisibility alone updates server state, not these bits.
            val hiddenBits = nativeWindowFlags.getInt(activity.window.attributes) and NATIVE_HIDDEN_BAR_MASK
            val expectedBits = if (dockVisible) 0 else NATIVE_HIDDEN_BAR_MASK
            val nativeFlagsChanged = hiddenBits != expectedBits
            if (nativeFlagsChanged) {
                setActivityBars.invoke(activity, -1, if (dockVisible) View.VISIBLE else View.INVISIBLE)
            }
            if (!force && !uiChanged && !nativeFlagsChanged && state == requestedState) return
            val changed = state != requestedState
            requestedState = state
            showSystemBar.invoke(screenManager, 0, NATIVE_STATUS_BAR_TYPE,
                if (dockVisible) View.VISIBLE else View.INVISIBLE)
            showSystemBar.invoke(screenManager, 0, NATIVE_NAVIGATION_BAR_TYPE,
                if (dockVisible) View.VISIBLE else View.INVISIBLE)
            Log.i(TAG, "Requested native bars: Dock=$dockVisible; area=$area; nativeFlags=${nativeWindowFlags.getInt(activity.window.attributes)} " +
                "force=$force changed=$changed androidUi=$previousAndroidUi->$androidUi nativeChanged=$nativeFlagsChanged")
            if (changed) onGeometryChanged()
        } catch (error: ReflectiveOperationException) {
            disable(error)
        } catch (error: RuntimeException) {
            disable(error)
        }
    }

    override fun onPreDraw(): Boolean {
        if (disabled) return true
        // E28 can change area/flags without an Android configuration callback.
        requestDock(force = false)
        try {
            val attrs = activity.window.attributes
            val area = areaType.get(attrs) as? String
            val root = frameworkRoot ?: return true
            // E28 reports zero WindowInsets even when native bars cover the window.
            // Reserve only their intersection with this window: split already ends
            // above the Dock, whereas fullscreen includes its 120 px region.
            if (screenId.getInt(attrs) == 0 && area in SUPPORTED_AREAS &&
                root.parent === decor &&
                (0 until root.childCount).all { root.getChildAt(it) === content || root.getChildAt(it).visibility == View.GONE } &&
                statusBarHeight > 0 && dockHeight > 0 && root.height > 0
            ) {
                root.getLocationOnScreen(position)
                val bars = area !in FULLSCREEN_AREAS || showBarsFullscreen
                val visibleDockHeight = if (bars) dockHeight else 0
                val visibleStatusHeight = if (bars) statusBarHeight else 0
                val insets = p7ContentInsets(position[1], root.height, displaySize.y, visibleStatusHeight, visibleDockHeight)
                if (root.paddingLeft != appliedLeftPadding) baseLeftPadding = root.paddingLeft
                val sideInset = if (bars && area in FULLSCREEN_AREAS)
                    p7SideBarInset(position[0], root.width, sideDockWidth) else 0
                val left = maxOf(baseLeftPadding, sideInset)
                if (root.paddingRight != appliedRightPadding) baseRightPadding = root.paddingRight
                // The native x=-1 window leaves 2279 pixels, but the encoder emits
                // even widths. Reserve one trailing pixel instead of scaling every column.
                val right = p7AlignedRightPadding(root.width, left, baseRightPadding,
                    bars && area in FULLSCREEN_AREAS)
                if (root.paddingTop != insets.top || root.paddingBottom != insets.bottom || root.paddingLeft != left || root.paddingRight != right) {
                    root.fitsSystemWindows = false
                    root.setPadding(left, insets.top, right, insets.bottom)
                    appliedLeftPadding = left
                    appliedRightPadding = right
                    Log.i(TAG, "Native bar insets: left=$left top=${insets.top} right=$right bottom=${insets.bottom}; area=$area")
                    onGeometryChanged()
                    return false // Let layout finish before presenting a frame.
                }
            }
        } catch (error: ReflectiveOperationException) {
            disable(error)
        } catch (error: RuntimeException) {
            disable(error)
        }
        return true
    }

    fun close() {
        if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(this)
    }

    private fun disable(error: Exception) {
        disabled = true
        Log.w(TAG, "Vendor window adaptation unavailable: ${error.javaClass.simpleName}")
    }

    companion object {
        private const val TAG = "P7WindowCompat"
        private const val NATIVE_STATUS_BAR_TYPE = 2000
        private const val NATIVE_NAVIGATION_BAR_TYPE = 2019
        private const val NATIVE_HIDDEN_BAR_MASK = 0x3000
        private val FULLSCREEN_AREAS = setOf("logical", "physical", "immersion")
        private val SUPPORTED_AREAS = setOf("primary", "secondary", "logical", "physical", "immersion")

        fun attach(activity: Activity, onGeometryChanged: () -> Unit = {}): P7WindowCompat? {
            if (!activity.resources.getBoolean(R.bool.config_p7_display_profile) ||
                Build.VERSION.SDK_INT != 28 || !Build.MODEL.equals("E28", ignoreCase = true)
            ) return null
            val physicalMode = activity.windowManager.defaultDisplay.mode
            if (physicalMode.physicalWidth != 2400 || physicalMode.physicalHeight != 1200) return null
            return try {
                P7WindowCompat(activity, onGeometryChanged)
            } catch (error: ReflectiveOperationException) {
                Log.w(TAG, "E28 window API unavailable: ${error.javaClass.simpleName}")
                null
            } catch (error: RuntimeException) {
                Log.w(TAG, "E28 window adapter unavailable: ${error.javaClass.simpleName}")
                null
            }
        }
    }
}
