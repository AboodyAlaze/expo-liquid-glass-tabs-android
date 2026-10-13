package expo.modules.liquidglasstabsandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView

data class TabData(val key: String, val title: String, val icon: String)

/**
 * The native tab bar.
 *
 * How the glass "sees" the React Native screens behind it:
 * the screens are a SIBLING drawn before this view. On every frame that Android is already
 * drawing (OnPreDrawListener — never when idle), we record those siblings into [source],
 * a RenderNode. Recording only stores references to their existing display lists → cheap.
 * The Compose glass then refracts [source]. The tab bar itself is never inside [source],
 * so there's no feedback loop.
 */
class LiquidTabBarView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {

  override val shouldUseAndroidLayout = true

  // ---- state driven by JS props (read by Compose) ----
  internal val tabs = mutableStateOf<List<TabData>>(emptyList())
  internal val selected = mutableIntStateOf(0)
  internal val bottomInset = mutableFloatStateOf(0f)
  internal val dark = mutableStateOf(false)
  internal val accent = mutableStateOf(Color(0xFF0A84FF))
  internal val quality = mutableStateOf(Quality.resolve(context, "auto"))

  private val onTabPress by EventDispatcher()

  internal fun emitTabPress(index: Int) = onTabPress(mapOf("index" to index))

  internal fun setAccent(hex: String?) {
    accent.value = try {
      if (hex.isNullOrBlank()) Color(0xFF0A84FF) else Color(android.graphics.Color.parseColor(hex))
    } catch (_: IllegalArgumentException) {
      Color(0xFF0A84FF)
    }
  }

  // ---- backdrop capture ----
  private val source: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    RenderNode("liquid-glass-backdrop").apply { setClipToBounds(false) }
  } else null

  private val loc = IntArray(2)
  private val parentLoc = IntArray(2)

  private val preDraw = ViewTreeObserver.OnPreDrawListener {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) capture()
    true // never cancels a frame
  }

  // ---- safety for the capture ----
  private var capturing = false      // no re-entrancy
  private var failed = false         // after any error we stop capturing for good (glass keeps its tint)
  private var attachedAt = 0L
  private var slowFrames = 0
  private var skip = 0

  /**
   * Safe wrapper around the backdrop capture:
   *  - waits a moment after the bar appears, so it never competes with a screen transition
   *    (e.g. Login → Tabs, when react-native-screens is mid-animation)
   *  - never runs twice at once, never runs while hidden or not laid out
   *  - any exception turns the capture off instead of breaking the app
   *  - if it gets slow on a weak phone, it captures every 3rd frame instead of every frame
   */
  @RequiresApi(Build.VERSION_CODES.Q)
  private fun capture() {
    if (failed || capturing || !isShown || width == 0 || height == 0) return
    if (SystemClock.uptimeMillis() - attachedAt < 400) return
    if (skip > 0) { skip--; return }
    capturing = true
    val t0 = System.nanoTime()
    try {
      captureUnsafe()
    } catch (t: Throwable) {
      failed = true
      Log.w("LiquidGlassTabs", "Backdrop capture disabled after an error", t)
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
  private fun captureUnsafe() {
    val node = source as RenderNode
    // Find the first ancestor that has something drawn before it (the screens)
    var anchor: View = this
    var parent = anchor.parent as? ViewGroup ?: return
    var index = parent.indexOfChild(anchor)
    var climbs = 0
    while (index <= 0) {
      if (++climbs > 4) return // the screens should be right next to the bar; never capture half the app
      anchor = parent
      parent = anchor.parent as? ViewGroup ?: return
      index = parent.indexOfChild(anchor)
    }

    getLocationInWindow(loc)
    parent.getLocationInWindow(parentLoc)
    val dx = (loc[0] - parentLoc[0]).toFloat()
    val dy = (loc[1] - parentLoc[1]).toFloat()

    node.setPosition(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
    val canvas = node.beginRecording()
    try {
      canvas.translate(-dx, -dy)
      for (i in 0 until index) {
        val child = parent.getChildAt(i)
        if (child.visibility != View.VISIBLE || child.alpha == 0f) continue
        if (isAncestorOfMe(child)) continue // a view that contains the bar would make a draw loop
        canvas.save()
        canvas.translate(child.left + child.translationX, child.top + child.translationY)
        child.draw(canvas)
        canvas.restore()
      }
    } finally {
      node.endRecording()
    }
  }

  /** Called from Compose while recording its backdrop layer. */
  internal fun drawSource(canvas: Canvas) {
    if (failed) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated) {
      canvas.drawRenderNode(source as RenderNode)
    }
  }

  // ---- Compose content ----
  // IMPORTANT: Compose can only run when the view is attached to a window.
  // React Native (Fabric) measures views BEFORE attaching them — e.g. when the Tabs screen is
  // prepared during the Login → Tabs transition. A ComposeView measured in that state throws
  // "Cannot locate windowRecomposer", which in release builds silently breaks the UI (black screen).
  // So the ComposeView only exists while we're attached: added after attach, removed on detach.
  private var composeView: ComposeView? = null

  private fun addCompose() {
    if (composeView != null || !isAttachedToWindow) return
    val cv = ComposeView(context).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
      setContent { LiquidTabBarContent(this@LiquidTabBarView) }
    }
    composeView = cv
    addView(cv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    attachedAt = SystemClock.uptimeMillis()
    viewTreeObserver.addOnPreDrawListener(preDraw)
    // posted: adding a child while the attach is still being dispatched would attach it twice
    post { addCompose() }
  }

  override fun onDetachedFromWindow() {
    viewTreeObserver.removeOnPreDrawListener(preDraw)
    composeView?.let { removeView(it) }
    composeView = null
    super.onDetachedFromWindow()
  }
}
