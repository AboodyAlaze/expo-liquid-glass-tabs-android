package expo.modules.liquidglasstabsandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TabData(
  val key: String,
  val title: String,
  val icon: String,
  val iconPath: String?,
  val badge: String?,
  val accessibilityLabel: String,
)

/**
 * The native Liquid Glass tab bar — a plain Android View (no Jetpack Compose).
 *
 * Why no Compose: Compose can only run once the view is attached to a window, but React Native
 * measures views before attaching them (that was the "Cannot locate windowRecomposer" black
 * screen). It also costs start-up time and pins library versions that can clash with a new Expo
 * SDK. Everything here is drawn with RenderNode + an AGSL shader, so it starts instantly and has
 * nothing that can fail on attach.
 *
 * How the glass sees the screens: they're a SIBLING drawn before this view. Before every frame
 * Android draws anyway (OnPreDrawListener — never when idle) we record those siblings into
 * [source] (references to their display lists, not pixels). The bar glass refracts [source];
 * the lens refracts [source] + the bar itself.
 */
class LiquidTabBarView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val onTabPress by EventDispatcher()
  private val onTabLongPress by EventDispatcher()

  private val d = resources.displayMetrics.density

  // ───────────────────────── props ─────────────────────────
  internal var tabs: List<TabData> = emptyList()
    set(v) {
      if (v == field) return // same tabs re-sent by a JS re-render: nothing to do (keeps animations smooth)
      field = v
      layoutDirty = true
      a11y.invalidateRoot()
      invalidate()
    }
  internal var bottomInsetDp = 0f
    set(v) { if (field != v) { field = v; layoutDirty = true; invalidate() } }
  internal var dark = false
    set(v) { field = v; invalidate() }
  internal var accent = DEFAULT_ACCENT
    set(v) { field = v; invalidate() }
  internal var inactiveColor: Int? = null
    set(v) { field = v; invalidate() }
  internal var quality = Quality.resolve(context, "auto")
    set(v) { field = v; invalidate() }
  internal var haptics = true

  private var selectedJs = 0 // what JS says is selected (-1 = none, e.g. a hidden route)
  private var shown = 0      // what we show: optimistic after a tap, JS confirms

  internal fun setSelectedFromJs(i: Int) {
    selectedJs = i
    removeCallbacks(revert)
    if (!touching) {
      shown = i
      if (i >= 0 && layoutBar()) springTo(selX, tabCenter(i), 420f, 0.75f)
    }
    a11y.invalidateRoot()
    invalidate()
  }

  // ───────────────────────── geometry ─────────────────────────
  private var layoutDirty = true
  private var barL = 0f
  private var barT = 0f
  private var barR = 0f
  private var barB = 0f
  private var barH = 0f
  private var tabW = 0f
  private val barInset get() = 5f * d
  private var labels: Array<String> = emptyArray()
  private var selXReady = false

  private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT_BOLD
  }

  private fun layoutBar(): Boolean {
    val n = tabs.size
    if (width == 0 || height == 0 || n == 0) return false
    if (!layoutDirty) return true
    barH = 66f * d
    val barW = min(width - 32f * d, (90f * n).coerceIn(280f, 420f) * d)
    barL = (width - barW) / 2f
    barR = barL + barW
    barB = height - (bottomInsetDp + 12f) * d
    barT = barB - barH
    tabW = (barW - barInset * 2) / n
    // label size: 10sp, but never let a large system font break the bar
    val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    labelPaint.textSize = min(sp, 10f * d * 1.3f)
    labels = Array(n) { i -> TextUtils.ellipsize(tabs[i].title, labelPaint, tabW - 8f * d, TextUtils.TruncateAt.END).toString() }
    layoutDirty = false
    if (!selXReady) { selX.snap(tabCenter(shown.coerceAtLeast(0))); selXReady = true }
    else if (!touching) springTo(selX, tabCenter(shown.coerceAtLeast(0)), 420f, 0.75f)
    return true
  }

  private fun tabCenter(i: Int) = barL + barInset + tabW * (i.coerceIn(0, max(0, tabs.size - 1)) + 0.5f)
  private fun nearestTab(x: Float) = ((x - barL - barInset) / tabW).toInt().coerceIn(0, max(0, tabs.size - 1))

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    layoutDirty = true
  }

  // React Native lays this view out; there are no children for LinearLayout to arrange.
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit

  init {
    setWillNotDraw(false)
    clipChildren = false
    clipToPadding = false // Expo SDK 58's ExpoView clips to the padding box unless this is off
  }

  // ───────────────────────── springs + frame loop ─────────────────────────
  private val selX = Spring(0f, 420f, 0.75f)
  private val lift = Spring(0f, 500f, 0.85f)     // 0 = resting pill, 1 = lens fully out
  private val stretch = Spring(0f, 250f, 0.35f)  // lens stretch from drag speed
  private val appear = Spring(0f, 600f, 1f)      // glass fades in on the first real backdrop
  private var pulseAt = 0L

  private fun springTo(s: Spring, target: Float, stiffness: Float, damping: Float) {
    s.stiffness = stiffness; s.dampingRatio = damping; s.target = target
    startLoop()
  }

  private var looping = false
  private var lastFrame = 0L
  private val frame = object : Choreographer.FrameCallback {
    override fun doFrame(now: Long) {
      val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceIn(0.001f, 0.032f)
      lastFrame = now
      if (pulseAt != 0L && SystemClock.uptimeMillis() >= pulseAt) {
        pulseAt = 0L
        lift.stiffness = 500f; lift.dampingRatio = 1f; lift.target = 0f
      }
      var moving = selX.step(dt)
      moving = lift.step(dt) || moving
      moving = stretch.step(dt) || moving
      moving = appear.step(dt) || moving
      invalidate()
      if (moving || pulseAt != 0L) Choreographer.getInstance().postFrameCallback(this)
      else { looping = false; lastFrame = 0L }
    }
  }

  private fun startLoop() {
    if (looping || !isAttachedToWindow) { invalidate(); return }
    looping = true; lastFrame = 0L
    Choreographer.getInstance().postFrameCallback(frame)
  }

  // ───────────────────────── touch ─────────────────────────
  private var touching = false
  private var dragging = false
  private var longFired = false
  private var pressedTab = 0
  private var lastTickTab = 0
  private var downX = 0f
  private var lastX = 0f
  private var lastT = 0L
  private val slop = ViewConfiguration.get(context).scaledTouchSlop

  private val longPress = Runnable {
    longFired = true
    haptic(HapticFeedbackConstants.LONG_PRESS)
    onTabLongPress(mapOf("index" to pressedTab))
  }

  private val revert = Runnable {
    // JS didn't confirm the tap (prevented, or same tab) → go back to what JS says
    if (!touching && shown != selectedJs) {
      shown = selectedJs
      if (shown >= 0) springTo(selX, tabCenter(shown), 420f, 0.75f)
      invalidate()
    }
  }

  private fun inBar(x: Float, y: Float) =
    x >= barL - 4f * d && x <= barR + 4f * d && y >= barT - 10f * d && y <= barB + 8f * d

  override fun onTouchEvent(e: MotionEvent): Boolean {
    if (!layoutBar()) return false
    when (e.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        // Outside the capsule we're transparent to touches: the screen below gets them.
        if (!inBar(e.x, e.y)) return false
        touching = true; dragging = false; longFired = false
        downX = e.x; lastX = e.x; lastT = e.eventTime
        pressedTab = nearestTab(e.x); lastTickTab = pressedTab
        parent?.requestDisallowInterceptTouchEvent(true)
        removeCallbacks(revert)
        // Instant response: the lens swells right under the finger on the first frame.
        pulseAt = 0L
        springTo(lift, 0.45f, 900f, 0.8f)
        springTo(selX, tabCenter(pressedTab), 900f, 0.85f)
        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (!touching) return false
        if (!dragging && abs(e.x - downX) > slop) {
          dragging = true
          removeCallbacks(longPress)
          springTo(lift, 1f, 500f, 0.55f)
        }
        if (dragging) {
          val x = e.x.coerceIn(tabCenter(0), tabCenter(tabs.size - 1))
          selX.snap(x) // zero latency: the lens IS under the finger
          val dt = ((e.eventTime - lastT).coerceAtLeast(1L)) / 1000f
          val speed = abs(e.x - lastX) / dt / d // dp/s
          springTo(stretch, (speed / 1600f).coerceAtMost(1f) * 0.2f, 900f, 1f)
          lastX = e.x; lastT = e.eventTime
          val t = nearestTab(x)
          if (t != lastTickTab) { lastTickTab = t; haptic(HapticFeedbackConstants.CLOCK_TICK) }
          invalidate()
        }
        return true
      }
      MotionEvent.ACTION_UP -> { finish(commit = true); return true }
      MotionEvent.ACTION_CANCEL -> { finish(commit = false); return true }
    }
    return touching
  }

  private fun finish(commit: Boolean) {
    if (!touching) return
    touching = false
    removeCallbacks(longPress)
    parent?.requestDisallowInterceptTouchEvent(false)
    springTo(stretch, 0f, 250f, 0.35f)
    if (commit && !longFired) {
      select(if (dragging) nearestTab(selX.value) else pressedTab)
      springTo(lift, 0f, if (dragging) 260f else 380f, if (dragging) 0.75f else 0.62f)
    } else {
      springTo(lift, 0f, 300f, 0.8f)
      if (shown >= 0) springTo(selX, tabCenter(shown), 420f, 0.75f)
    }
    dragging = false
  }

  /** Optimistic select: show it now, tell JS, revert if JS doesn't confirm. */
  private fun select(i: Int) {
    shown = i
    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    springTo(selX, tabCenter(i), 420f, 0.75f)
    onTabPress(mapOf("index" to i))
    removeCallbacks(revert)
    postDelayed(revert, 450)
    a11y.invalidateRoot()
  }

  private fun haptic(type: Int) {
    if (haptics) performHapticFeedback(type)
  }

  // ───────────────────────── backdrop capture ─────────────────────────
  private val source: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) RenderNode("liquid-glass-tabs-backdrop") else null
  private var ready = false
  private var capturing = false
  private var failed = false
  private var failedAt = 0L
  private var slowFrames = 0
  private var skip = 0
  private val loc = IntArray(2)
  private val parentLoc = IntArray(2)

  private val preDraw = ViewTreeObserver.OnPreDrawListener {
    capture()
    true // never cancels a frame
  }

  /**
   * Safe capture: no re-entrancy, nothing while hidden or not laid out, an error pauses it for
   * 100 ms (then it retries) instead of breaking anything, and it backs off on slow frames.
   */
  private fun capture() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || quality == Quality.LOW) return
    if (failed) {
      if (SystemClock.uptimeMillis() - failedAt < 100) return
      failed = false
    }
    if (capturing || !isShown || width == 0 || height == 0) return
    if (skip > 0) { skip--; return }
    capturing = true
    val t0 = System.nanoTime()
    try {
      if (captureUnsafe() && !ready) {
        ready = true
        springTo(appear, 1f, 600f, 1f)
      }
    } catch (t: Throwable) {
      failed = true; failedAt = SystemClock.uptimeMillis()
      Log.w("LiquidGlassTabs", "Backdrop capture paused after an error", t)
    } finally {
      capturing = false
    }
    val ms = (System.nanoTime() - t0) / 1_000_000f
    if (ms > 6f) { if (++slowFrames >= 3) skip = 2 } else slowFrames = 0
  }

  private fun isAncestorOfMe(v: View): Boolean {
    var p = parent
    while (p != null) { if (p === v) return true; p = p.parent }
    return false
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun captureUnsafe(): Boolean {
    val node = source as RenderNode
    // The screens are drawn right before the bar: find the first ancestor that has siblings before it.
    var anchor: View = this
    var parent = anchor.parent as? ViewGroup ?: return false
    var index = parent.indexOfChild(anchor)
    var climbs = 0
    while (index <= 0) {
      if (++climbs > 4) return false // never capture half the app
      anchor = parent
      parent = anchor.parent as? ViewGroup ?: return false
      index = parent.indexOfChild(anchor)
    }
    getLocationInWindow(loc)
    parent.getLocationInWindow(parentLoc)
    val dx = (loc[0] - parentLoc[0]).toFloat()
    val dy = (loc[1] - parentLoc[1]).toFloat()

    node.setPosition(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
    val canvas = node.beginRecording()
    try {
      canvas.translate(-dx, -dy) // record in OUR local coordinates
      for (i in 0 until index) {
        val child = parent.getChildAt(i)
        if (child.visibility != View.VISIBLE || child.alpha == 0f || child.width == 0 || child.height == 0) continue
        if (isAncestorOfMe(child)) continue // a view that contains the bar would make a draw loop
        val count = canvas.save()
        canvas.translate(child.left + child.translationX, child.top + child.translationY)
        if (child.alpha < 1f) canvas.saveLayerAlpha(0f, 0f, child.width.toFloat(), child.height.toFloat(), (child.alpha * 255).toInt())
        child.draw(canvas)
        canvas.restoreToCount(count)
      }
    } finally {
      node.endRecording()
    }
    return true
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    viewTreeObserver.addOnPreDrawListener(preDraw)
  }

  override fun onDetachedFromWindow() {
    viewTreeObserver.removeOnPreDrawListener(preDraw)
    removeCallbacks(longPress); removeCallbacks(revert)
    Choreographer.getInstance().removeFrameCallback(frame); looping = false
    touching = false; dragging = false
    super.onDetachedFromWindow()
  }

  // ───────────────────────── drawing ─────────────────────────
  private val barRenderer = GlassRenderer(d)
  private val lensRenderer = GlassRenderer(d)
  private val barLook = GlassLook()
  private val lensLook = GlassLook(blur = 0f, refraction = 18f, bezel = 22f, chroma = 0.16f, highlight = 1.3f, saturation = 1.25f, tint = Color.argb(10, 255, 255, 255))
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val rect = RectF()
  private val iconBounds = Rect()
  private var content: Any? = null // RenderNode: bar + pill + icons (also what the lens refracts)
  private var lensSource: Any? = null

  override fun onDraw(canvas: Canvas) {
    if (!layoutBar()) return
    val glassOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && quality != Quality.LOW && canvas.isHardwareAccelerated
    barLook.tint = if (dark) Color.argb(36, 0, 0, 0) else Color.argb(26, 255, 255, 255)
    if (quality == Quality.HIGH) { barLook.blur = 3f; barLook.chroma = 0.10f; lensLook.chroma = 0.16f }
    else { barLook.blur = 0f; barLook.chroma = 0f; lensLook.chroma = 0f }

    // 1) bar + pill + icons — recorded into a node so the lens can refract them too
    var contentNode: RenderNode? = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated) {
      val rn = (content as? RenderNode) ?: RenderNode("liquid-glass-tabs-content").also { content = it }
      rn.setPosition(0, 0, width, height)
      val c = rn.beginRecording()
      try { drawBar(c, glassOk) } finally { rn.endRecording() }
      canvas.drawRenderNode(rn)
      contentNode = rn
    } else {
      drawBar(canvas, false)
    }

    // 2) the liquid lens (nothing at rest)
    val l = lift.value.coerceIn(0f, 1.2f)
    if (l > 0.01f && (shown >= 0 || touching)) {
      val s = stretch.value
      val w = tabW * (1f + 0.18f * l) * (1f + s)
      val h = barH * (1f + 0.28f * l) / (1f + s * 0.5f)
      val cx = selX.value; val cy = (barT + barB) / 2f
      if (glassOk && ready && contentNode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val src = (lensSource as? RenderNode) ?: RenderNode("liquid-glass-tabs-lens-src").also { lensSource = it }
        src.setPosition(0, 0, width, height)
        val c = src.beginRecording()
        try { c.drawRenderNode(source as RenderNode); c.drawRenderNode(contentNode) } finally { src.endRecording() }
        lensRenderer.draw(canvas, src, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, h / 2f, 0f, 0f, lensLook,
          zoom = 1f + 0.3f * l, alpha = l.coerceAtMost(1f))
      } else {
        // low quality / no shader: a simple light lens with the same motion
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        paint.style = Paint.Style.FILL
        paint.color = if (dark) Color.argb((46 * l.coerceAtMost(1f)).toInt(), 255, 255, 255) else Color.argb((70 * l.coerceAtMost(1f)).toInt(), 255, 255, 255)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
      }
    }
  }

  private fun drawBar(c: Canvas, glassOk: Boolean) {
    val radius = barH / 2f
    val a = if (glassOk && ready) appear.value else 0f
    // Frosted base: visible from the very first frame (navigation must never be invisible),
    // cross-fades into real glass as soon as the backdrop is captured.
    if (a < 1f) {
      rect.set(barL, barT, barR, barB)
      paint.style = Paint.Style.FILL
      val base = if (dark) Color.argb(235, 30, 30, 34) else Color.argb(240, 248, 248, 250)
      paint.color = base; paint.alpha = (Color.alpha(base) * (1f - a)).toInt()
      c.drawRoundRect(rect, radius, radius, paint)
      paint.style = Paint.Style.STROKE; paint.strokeWidth = d
      paint.color = if (dark) Color.argb((40 * (1f - a)).toInt(), 255, 255, 255) else Color.argb((22 * (1f - a)).toInt(), 0, 0, 0)
      c.drawRoundRect(rect, radius, radius, paint)
    }
    if (a > 0f) {
      barRenderer.draw(c, source as? RenderNode, barL, barT, barR, barB, radius, 0f, 0f, barLook, alpha = a)
    }

    // resting selection pill (fades out while the lens is up)
    val pa = 1f - lift.value.coerceIn(0f, 1f)
    if (pa > 0.01f && shown >= 0) {
      val hh = barH - barInset * 2
      rect.set(selX.value - tabW / 2f, barT + barInset, selX.value + tabW / 2f, barT + barInset + hh)
      paint.style = Paint.Style.FILL
      paint.color = if (dark) Color.argb((26 * pa).toInt(), 255, 255, 255) else Color.argb((26 * pa).toInt(), 0, 0, 0)
      c.drawRoundRect(rect, hh / 2f, hh / 2f, paint)
    }

    // icons + labels; the accent colour flows with the lens / pill
    val ink = inactiveColor ?: if (dark) Color.WHITE else Color.rgb(17, 17, 17)
    val iconSize = 24f * d
    val textH = labelPaint.descent() - labelPaint.ascent()
    val contentH = iconSize + 2f * d + textH
    val iconTop = barT + (barH - contentH) / 2f
    val baseline = iconTop + iconSize + 2f * d - labelPaint.ascent()
    for (i in tabs.indices) {
      val cx = tabCenter(i)
      val t = if (shown < 0 && !touching) 0f else (1f - abs(selX.value - cx) / tabW).coerceIn(0f, 1f)
      val col = blend(ink, accent, t)
      val path: Path? = Icons.path(tabs[i].icon, tabs[i].iconPath)
      if (path != null) {
        paint.style = Paint.Style.FILL; paint.color = col
        val count = c.save()
        c.translate(cx - iconSize / 2f, iconTop)
        c.scale(d, d)
        c.drawPath(path, paint)
        c.restoreToCount(count)
      }
      labelPaint.color = col
      if (i < labels.size) c.drawText(labels[i], cx, baseline, labelPaint)
      tabs[i].badge?.let { drawBadge(c, it, cx + iconSize / 2f - 2f * d, iconTop + 2f * d) }
    }
  }

  private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER; color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD
  }

  private fun drawBadge(c: Canvas, text: String, x: Float, y: Float) {
    paint.style = Paint.Style.FILL; paint.color = Color.rgb(255, 59, 48)
    if (text.isEmpty()) { c.drawCircle(x, y, 4.5f * d, paint); return }
    badgePaint.textSize = 10f * d
    val label = if (text.length > 3) text.take(3) else text
    val w = max(16f * d, badgePaint.measureText(label) + 9f * d)
    val h = 16f * d
    rect.set(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f)
    c.drawRoundRect(rect, h / 2f, h / 2f, paint)
    badgePaint.getTextBounds(label, 0, label.length, iconBounds)
    c.drawText(label, x, y + iconBounds.height() / 2f, badgePaint)
  }

  private fun blend(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    return Color.argb(
      (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
      (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
      (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
      (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
    )
  }

  // ───────────────────────── accessibility (TalkBack: each tab is a real, selectable tab) ─────────────────────────
  private val a11y = object : ExploreByTouchHelper(this@LiquidTabBarView) {
    override fun getVirtualViewAt(x: Float, y: Float): Int =
      if (layoutBar() && inBar(x, y)) nearestTab(x) else ExploreByTouchHelper.INVALID_ID

    override fun getVisibleVirtualViews(ids: MutableList<Int>) {
      for (i in tabs.indices) ids.add(i)
    }

    override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
      val tab = tabs.getOrNull(id)
      node.contentDescription = tab?.accessibilityLabel ?: ""
      node.roleDescription = "Tab"
      node.isClickable = true
      node.isSelected = id == shown
      node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
      layoutBar()
      val l = (barL + barInset + tabW * id).toInt()
      @Suppress("DEPRECATION")
      node.setBoundsInParent(Rect(l, barT.toInt(), (l + tabW).toInt(), barB.toInt()))
    }

    override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
      if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || id !in tabs.indices) return false
      select(id)
      invalidateVirtualView(id)
      sendEventForVirtualView(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
      return true
    }
  }

  init {
    ViewCompat.setAccessibilityDelegate(this, a11y)
  }

  override fun dispatchHoverEvent(event: MotionEvent): Boolean =
    a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

  private companion object {
    val DEFAULT_ACCENT = Color.rgb(10, 132, 255)
  }
}
