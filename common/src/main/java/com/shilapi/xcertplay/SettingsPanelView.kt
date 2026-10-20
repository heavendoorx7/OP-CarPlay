package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.IdentityHashMap

/** Presentation only. Owns no preferences, permissions, lifecycle or connection state. */
internal class SettingsPanelView(
    context: Context,
    private val pages: List<Page>,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onExit: () -> Unit,
) : LinearLayout(context) {
    class Page(val key: String, val title: String, val caption: String, val description: String) {
        val groups = mutableListOf<Pair<String, LinearLayout>>()
        fun group(context: Context, title: String, first: Boolean = false): LinearLayout =
            LinearLayout(context).apply {
                orientation = VERTICAL
                if (first) groups.add(0, title to this) else groups.add(title to this)
            }
    }

    private val backgroundColor = Color.rgb(19, 20, 22)
    private val sidebarColor = Color.rgb(24, 25, 28)
    private val surfaceColor = Color.rgb(29, 31, 34)
    private val lineColor = Color.rgb(45, 47, 52)
    private val primary = Color.rgb(238, 238, 240)
    private val secondary = Color.rgb(160, 163, 172)
    private val accent = Color.rgb(255, 145, 92)
    private var scale = 1f
    private var styledWidth = 0
    private var current = pages.first().key
    val selectedCategory: String get() = current
    private val sidebar = LinearLayout(context)
    private val body = LinearLayout(context)
    private val header = LinearLayout(context)
    private val title = text("", 28f, primary)
    private val caption = text("", 11f, secondary)
    private val description = text("", 13f, secondary)
    private val frame = FrameLayout(context)
    private val footer = LinearLayout(context)
    private val nav = LinearLayout(context)
    private val brand = LinearLayout(context)
    private val logo = ImageView(context)
    private val brandName = text("OP CarPlay", 18f, primary)
    private val brandHint = text("FOR XPENG P7", 10f, secondary)
    private val navCaption = text("设置", 11f, secondary)
    private val close = button("×", onCancel)
    private val save = button("保存并重新连接", onSave)
    private val cancel = button("取消修改", onCancel)
    private val exit = button("退出应用", onExit)
    private val footerHint = text("保存后重新连接 CarPlay", 12f, secondary)
    private val version = text("OP CarPlay · v0.7", 11f, secondary)
    private val navButtons = linkedMapOf<String, Button>()
    private val scrolls = linkedMapOf<String, ScrollView>()
    private val columns = mutableListOf<LinearLayout>()
    private val cards = mutableListOf<LinearLayout>()
    private val sectionTitles = mutableListOf<TextView>()
    private val originalSizes = IdentityHashMap<TextView, Float>()
    private val originalMargins = IdentityHashMap<View, Int>()

    init {
        orientation = HORIZONTAL
        isClickable = true
        setBackgroundColor(backgroundColor)
        sidebar.orientation = VERTICAL
        sidebar.setBackgroundColor(sidebarColor)
        addView(sidebar, LayoutParams(220, LayoutParams.MATCH_PARENT))
        body.orientation = VERTICAL
        addView(body, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        brand.gravity = Gravity.CENTER_VERTICAL
        logo.setImageDrawable(context.packageManager.getApplicationIcon(context.packageName))
        logo.contentDescription = "OP CarPlay"
        brand.addView(logo, LayoutParams(42, 42))
        val wordmark = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(brandName)
            addView(brandHint)
        }
        brand.addView(wordmark, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 10 })
        sidebar.addView(brand)
        sidebar.addView(navCaption)
        nav.orientation = VERTICAL
        val navScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(nav)
        }
        sidebar.addView(navScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        for (page in pages) {
            val tab = button(page.title) { select(page.key) }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                contentDescription = page.title
                setCompoundDrawablesRelativeWithIntrinsicBounds(NavIcon(page.key, secondary), null, null, null)
            }
            navButtons[page.key] = tab
            nav.addView(tab, LayoutParams(LayoutParams.MATCH_PARENT, 48))

            val column = LinearLayout(context).apply { orientation = VERTICAL }
            columns.add(column)
            for ((label, group) in page.groups) {
                if (label.isNotEmpty()) {
                    val sectionTitle = text(label, 14f, secondary)
                    sectionTitles.add(sectionTitle)
                    column.addView(sectionTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                }
                cards.add(group)
                column.addView(group, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                arrangeActions(group)
                capture(group)
            }
            val scroll = ScrollView(context).apply {
                isFillViewport = false
                clipToPadding = false
                isFocusableInTouchMode = true
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                addView(column)
                visibility = if (page.key == current) VISIBLE else GONE
            }
            scrolls[page.key] = scroll
            frame.addView(scroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        sidebar.addView(exit, LayoutParams(LayoutParams.MATCH_PARENT, 44))
        sidebar.addView(version, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        header.gravity = Gravity.CENTER_VERTICAL
        val headings = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(caption)
            addView(title)
            addView(description)
        }
        header.addView(headings, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        close.contentDescription = "放弃修改并退出设置"
        header.addView(close, LayoutParams(40, 40))
        body.addView(header)
        body.addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        footer.gravity = Gravity.CENTER_VERTICAL
        footer.addView(footerHint, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(cancel, LayoutParams(LayoutParams.WRAP_CONTENT, 44))
        footer.addView(save, LayoutParams(LayoutParams.WRAP_CONTENT, 44).apply { marginStart = 12 })
        body.addView(footer)
        select(current, hideKeyboard = false)
    }

    fun select(key: String, hideKeyboard: Boolean = true) {
        val page = pages.firstOrNull { it.key == key } ?: return
        if (hideKeyboard) {
            findFocus()?.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(windowToken, 0)
        }
        current = key
        title.text = page.title
        caption.text = page.caption
        description.text = page.description
        scrolls.forEach { (id, view) -> view.visibility = if (id == key) VISIBLE else GONE }
        navButtons.forEach { (id, tab) ->
            tab.isSelected = id == key
            tab.setTextColor(if (id == key) accent else secondary)
            tab.background = rounded(if (id == key) Color.rgb(52, 39, 31) else Color.TRANSPARENT, 9)
            tab.compoundDrawablesRelative[0]?.setTint(if (id == key) accent else secondary)
        }
        // Selecting a category must not stop/restart media or persist any setting.
    }

    fun revealError(key: String, error: View?) {
        select(key)
        if (error == null) return
        error.post { error.requestRectangleOnScreen(android.graphics.Rect(0, 0, error.width, error.height), false) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0 && w != styledWidth) {
            styledWidth = w
            applySizing(w)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun applySizing(w: Int) {
        scale = (w / 1200f).coerceIn(0.85f, 2f)
        val compact = w / scale < 1000
        sidebar.layoutParams.width = u(if (compact) 184 else 228)
        sidebar.setPadding(u(16), u(30), u(16), u(20))
        brand.setPadding(u(10), 0, u(10), u(30))
        logo.layoutParams = LayoutParams(u(42), u(42))
        (brand.getChildAt(1).layoutParams as LayoutParams).marginStart = u(10)
        setSize(brandName, 18f); setSize(brandHint, 9f)
        brandHint.letterSpacing = .12f
        navCaption.setPadding(u(14), 0, 0, u(12)); setSize(navCaption, 11f)
        navButtons.forEach { (key, tab) ->
            tab.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, u(44)).apply { bottomMargin = u(6) }
            setSize(tab, 14f)
            tab.setPadding(u(14), 0, u(10), 0)
            tab.compoundDrawablePadding = u(12)
            tab.setCompoundDrawablesRelative(NavIcon(key, secondary).apply { setBounds(0, 0, u(19), u(19)) }, null, null, null)
        }
        styleAction(exit, false); exit.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        exit.layoutParams.height = u(44)
        setSize(version, 11f); version.setPadding(u(12), u(16), 0, 0)
        header.setPadding(u(44), u(28), u(44), u(24))
        setSize(title, 28f); title.setTypeface(null, Typeface.BOLD)
        setSize(caption, 10f); caption.letterSpacing = .15f
        setSize(description, 13f); description.setPadding(0, u(7), 0, 0)
        close.layoutParams = LayoutParams(u(40), u(40))
        setSize(close, 27f); close.setTextColor(secondary)
        close.backgroundTintList = null
        close.background = rounded(backgroundColor, 20, lineColor)
        columns.forEach { it.setPadding(u(44), 0, u(44), u(24)) }
        cards.forEach {
            it.setPadding(u(22), u(20), u(22), u(20))
            it.background = rounded(surfaceColor, 14, lineColor)
            (it.layoutParams as LayoutParams).bottomMargin = u(24)
            styleTree(it)
            (it.getChildAt(0)?.layoutParams as? MarginLayoutParams)?.topMargin = 0
        }
        sectionTitles.forEach {
            setSize(it, 14f)
            it.setPadding(0, 0, 0, u(12))
        }
        footer.setPadding(u(44), u(17), u(44), u(20))
        footer.background = rounded(backgroundColor, 0, lineColor)
        setSize(footerHint, 12f)
        styleAction(cancel, false); styleAction(save, true)
        cancel.layoutParams.height = u(44); save.layoutParams.height = u(44)
        (save.layoutParams as LayoutParams).marginStart = u(12)
        select(current, hideKeyboard = false)
    }

    private fun capture(view: View) {
        if (view is TextView) originalSizes[view] = view.textSize / resources.displayMetrics.scaledDensity
        originalMargins[view] = (view.layoutParams as? MarginLayoutParams)?.topMargin ?: 0
        if (view is ViewGroup) for (i in 0 until view.childCount) capture(view.getChildAt(i))
    }

    /** Reuse the same controls/listeners; only their presentation container changes. */
    private fun arrangeActions(group: ViewGroup) {
        val children = (0 until group.childCount).map { group.getChildAt(it) }
        children.filterIsInstance<ViewGroup>().forEach(::arrangeActions)
        if (group !is LinearLayout || group is RadioGroup) return
        fun action(view: View) = view is Button && view !is CompoundButton && view.tag != "op.action.disclosure"
        if (group.orientation == HORIZONTAL && children.isNotEmpty() && children.all(::action)) {
            val row = SettingsActionRow(context)
            group.removeAllViews()
            children.forEach { row.addView(it, ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
            group.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else if (group.orientation == VERTICAL) {
            var index = 0
            while (index < group.childCount) {
                if (!action(group.getChildAt(index))) { index++; continue }
                val row = SettingsActionRow(context)
                while (index < group.childCount && action(group.getChildAt(index))) {
                    val child = group.getChildAt(index)
                    group.removeViewAt(index)
                    row.addView(child, ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                }
                group.addView(row, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                index++
            }
        }
    }

    private fun styleTree(view: View) {
        (view.layoutParams as? MarginLayoutParams)?.let {
            val old = originalMargins[view] ?: 0
            it.topMargin = if (old > 0) u(if (old > resources.displayMetrics.density * 20) 18 else 10) else 0
        }
        when (view) {
            is SettingsActionRow -> {
                view.gap = u(12)
                (view.layoutParams as? MarginLayoutParams)?.let {
                    it.topMargin = u(16)
                    it.bottomMargin = u(4)
                }
            }
            is EditText -> {
                setSize(view, 14f)
                view.minimumHeight = u(44)
                view.setTextColor(primary)
                view.backgroundTintList = null
                view.background = rounded(Color.rgb(20, 22, 25), 8, Color.rgb(57, 60, 68))
                view.setPadding(u(13), u(6), u(13), u(6))
                (view.parent as? LinearLayout)?.let { row ->
                    if (row.orientation == HORIZONTAL && row.childCount == 2) {
                        row.getChildAt(0).layoutParams = LayoutParams(u(165), LayoutParams.WRAP_CONTENT)
                    }
                }
            }
            is RadioGroup -> {
                // Keep the original group, IDs, tags and onCheckedChange listener.
                view.orientation = HORIZONTAL
                view.setPadding(u(4), u(4), u(4), u(4))
                view.background = rounded(Color.rgb(20, 22, 25), 9, lineColor)
                val fullscreen = view.tag == "op.fullscreen"
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    child.layoutParams = RadioGroup.LayoutParams(0, u(if (fullscreen) 150 else 46), 1f).apply {
                        if (i > 0) marginStart = u(if (fullscreen) 12 else 4)
                    }
                }
            }
            is RadioButton -> {
                val fullscreen = (view.parent as? View)?.tag == "op.fullscreen"
                view.buttonDrawable = null
                view.isAllCaps = false
                view.minWidth = 0; view.minHeight = 0
                setSize(view, 13f)
                view.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(primary, secondary)))
                view.backgroundTintList = null
                view.background = if (fullscreen) ModePreviewDrawable(view.tag == true, scale, accent) else StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), rounded(Color.rgb(56, 58, 64), 6, Color.rgb(71, 74, 82)))
                    addState(intArrayOf(), rounded(Color.TRANSPARENT, 6))
                }
                view.gravity = if (fullscreen) Gravity.BOTTOM or Gravity.START else Gravity.CENTER
                view.setPadding(u(8), 0, u(8), if (fullscreen) u(13) else 0)
                view.minimumHeight = u(44)
                view.maxLines = 2
            }
            is Switch -> {
                view.thumbDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(primary)
                    setSize(u(22), u(22))
                }
                view.trackDrawable = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = u(14).toFloat()
                    setSize(u(46), u(28))
                }
                view.switchMinWidth = u(46)
                view.thumbTintList = ColorStateList.valueOf(Color.rgb(233, 233, 235))
                view.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, Color.rgb(69, 72, 80)))
                view.minimumWidth = u(52); view.minimumHeight = u(44)
                // Hit area stays at least 44 logical units; no touch listeners are replaced.
            }
            is Button -> {
                styleAction(view, false)
                view.minimumHeight = u(48)
                view.minimumWidth = u(88)
                view.maxLines = 2
                view.stateListAnimator = null
                val emphasis = view.tag == "op.action.accent"
                val disclosure = view.tag == "op.action.disclosure"
                val color = when {
                    emphasis -> Color.rgb(52, 40, 31)
                    disclosure -> Color.TRANSPARENT
                    else -> Color.rgb(36, 38, 43)
                }
                view.setTextColor(if (emphasis) accent else primary)
                view.background = RippleDrawable(
                    ColorStateList.valueOf(Color.argb(35, 255, 255, 255)),
                    rounded(color, 8, when { emphasis -> Color.rgb(92, 65, 46); disclosure -> null; else -> Color.rgb(64, 67, 74) }),
                    rounded(Color.WHITE, 8),
                )
                if (disclosure) {
                    view.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    view.setPadding(0, u(8), u(10), u(8))
                    (view.layoutParams as? LayoutParams)?.let {
                        it.width = LayoutParams.MATCH_PARENT
                        it.topMargin = u(12)
                        it.bottomMargin = u(10)
                    }
                }
            }
            is SeekBar -> {
                view.progressTintList = ColorStateList.valueOf(accent)
                view.thumbTintList = ColorStateList.valueOf(accent)
                view.minimumHeight = u(36)
                view.setPadding(u(5), 0, u(5), 0)
            }
            is TextView -> {
                val old = originalSizes[view] ?: 16f
                setSize(view, when { old >= 24 -> 20f; old >= 18 -> 15f; old >= 16 -> 13f; else -> 12f })
                // Error labels keep their original danger color.
                if (Color.red(view.currentTextColor) <= Color.green(view.currentTextColor) * 1.35f) {
                    view.setTextColor(if (old >= 18) primary else secondary)
                }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) styleTree(view.getChildAt(i))
    }

    private fun styleAction(button: Button, primaryAction: Boolean) {
        button.backgroundTintList = null
        button.background = rounded(if (primaryAction) accent else Color.rgb(38, 40, 46), 8, if (primaryAction) accent else lineColor)
        button.setTextColor(if (primaryAction) Color.rgb(39, 24, 15) else primary)
        button.isAllCaps = false
        button.minWidth = 0; button.minHeight = 0
        button.minimumHeight = u(44)
        button.setPadding(u(17), u(8), u(17), u(8))
        setSize(button, 13f)
    }

    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; minWidth = 0; minHeight = 0
        setOnClickListener { action() }
    }
    private fun text(label: String, size: Float, color: Int) = TextView(context).apply {
        text = label; textSize = size; setTextColor(color); includeFontPadding = false
    }
    private fun u(value: Int) = (value * scale).toInt()
    private fun setSize(view: TextView, value: Float) = view.setTextSize(TypedValue.COMPLEX_UNIT_PX, value * scale)
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = u(radius).toFloat()
        stroke?.let { setStroke(maxOf(1, u(1)), it) }
    }
}

/** Compact action group that wraps at the available width, including split-screen. */
private class SettingsActionRow(context: Context) : ViewGroup(context) {
    var gap: Int = 0
    private val positions = IdentityHashMap<View, android.graphics.Rect>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val available = if (bounded) MeasureSpec.getSize(widthMeasureSpec) else Int.MAX_VALUE
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        positions.clear()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(if (bounded) available else 0, if (bounded) MeasureSpec.AT_MOST else MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val w = child.measuredWidth
            val h = child.measuredHeight
            if (x > 0 && x + w > available) { x = 0; y += rowHeight + gap; rowHeight = 0 }
            positions[child] = android.graphics.Rect(x, y, x + w, y + h)
            widest = maxOf(widest, x + w)
            rowHeight = maxOf(rowHeight, h)
            x += w + gap
        }
        setMeasuredDimension(resolveSize(widest, widthMeasureSpec), resolveSize(y + rowHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        positions.forEach { (child, rect) ->
            if (layoutDirection == LAYOUT_DIRECTION_RTL) child.layout(width - rect.right, rect.top, width - rect.left, rect.bottom)
            else child.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }
}

/** Radio-button background. Selection remains controlled by the original RadioGroup. */
private class ModePreviewDrawable(private val bars: Boolean, private val unit: Float, private val accent: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
    override fun draw(canvas: Canvas) {
        val selected = state.contains(android.R.attr.state_checked)
        fun box(rect: RectF, color: Int, radius: Float = 5f) {
            paint.style = Paint.Style.FILL; paint.color = color
            canvas.drawRoundRect(rect, radius * unit, radius * unit, paint)
        }
        val full = RectF(bounds).apply { inset(unit, unit) }
        box(full, if (selected) Color.rgb(45, 37, 31) else Color.rgb(29, 31, 34), 10f)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = unit
        paint.color = if (selected) accent else Color.rgb(67, 70, 78)
        canvas.drawRoundRect(full, 10 * unit, 10 * unit, paint)
        val screen = RectF(full.left + 14 * unit, full.top + 14 * unit, full.right - 14 * unit, full.bottom - 42 * unit)
        box(screen, Color.rgb(14, 16, 19))
        val video = RectF(screen).apply { inset(5 * unit, 5 * unit) }
        if (bars) { video.left += 10 * unit; video.top += 7 * unit; video.bottom -= 8 * unit }
        box(video, Color.rgb(56, 59, 67), 2f)
        if (bars) {
            box(RectF(video.left, screen.top + 4 * unit, video.right, screen.top + 6 * unit), Color.GRAY, 1f)
            box(RectF(video.left, screen.bottom - 7 * unit, video.right, screen.bottom - 4 * unit), Color.GRAY, 1f)
        }
        for (i in 0..3) {
            val x = video.centerX() + (i - 2) * 21 * unit
            box(RectF(x, video.centerY() - 8 * unit, x + 15 * unit, video.centerY() + 7 * unit), Color.rgb(100, 104, 114), 3f)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Small monochrome category glyphs, drawn locally without any external asset dependency. */
private class NavIcon(private val kind: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.7f; strokeCap = Paint.Cap.ROUND }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        when (kind) {
            "connection" -> { canvas.drawArc(2f, 3f, 22f, 23f, 220f, 100f, false, paint); canvas.drawArc(6f, 8f, 18f, 20f, 220f, 100f, false, paint); canvas.drawCircle(12f, 19f, 1f, paint) }
            "auth" -> { canvas.drawRoundRect(5f, 3f, 19f, 21f, 5f, 5f, paint); canvas.drawLine(8f, 12f, 11f, 15f, paint); canvas.drawLine(11f, 15f, 16f, 9f, paint) }
            "display" -> { canvas.drawRoundRect(3f, 4f, 21f, 17f, 2f, 2f, paint); canvas.drawLine(12f, 17f, 12f, 21f, paint); canvas.drawLine(8f, 21f, 16f, 21f, paint) }
            "audio" -> { canvas.drawRect(3f, 9f, 8f, 15f, paint); canvas.drawLine(8f, 9f, 13f, 5f, paint); canvas.drawLine(13f, 5f, 13f, 19f, paint); canvas.drawLine(13f, 19f, 8f, 15f, paint); canvas.drawArc(12f, 6f, 22f, 18f, -70f, 140f, false, paint) }
            "identity" -> { canvas.drawRoundRect(4f, 3f, 20f, 21f, 3f, 3f, paint); canvas.drawCircle(12f, 9f, 2.5f, paint); canvas.drawArc(7f, 13f, 17f, 21f, 180f, 180f, false, paint) }
            "general" -> { canvas.drawArc(4f, 4f, 20f, 20f, -50f, 280f, false, paint); canvas.drawLine(12f, 2f, 12f, 11f, paint) }
            else -> { canvas.drawLine(2f, 12f, 7f, 12f, paint); canvas.drawLine(7f, 12f, 10f, 5f, paint); canvas.drawLine(10f, 5f, 14f, 19f, paint); canvas.drawLine(14f, 19f, 17f, 12f, paint); canvas.drawLine(17f, 12f, 22f, 12f, paint) }
        }
        canvas.restore()
    }
    override fun setTint(tintColor: Int) { paint.color = tintColor; invalidateSelf() }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
