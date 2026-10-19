package expo.modules.liquidglasstabsandroid

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Look of the glass. Sizes are in dp. */
data class GlassLook(
  var blur: Float = 3f,
  var refraction: Float = 14f,
  var bezel: Float = 18f,
  var chroma: Float = 0.10f,
  var highlight: Float = 1f,
  var saturation: Float = 1.4f,
  var tint: Int = Color.argb(16, 255, 255, 255),
)

/**
 * Draws liquid glass for 1–8 rounded shapes (they melt together when close) refracting a
 * backdrop RenderNode.
 *  - Android 13+: AGSL shader (refraction, chromatic edge, specular rim, smooth union) + light blur
 *  - Android 12: frosted glass per shape (blur + tint)
 *  - older / software canvas / no backdrop yet: a light translucent fill
 */
class GlassRenderer(private val density: Float) {
  private var node: Any? = null      // RenderNode
  private var shader: Any? = null    // RuntimeShader, compiled lazily on first real use
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val rect = RectF()
  private val one = FloatArray(5)
  private val ra = FloatArray(4)
  private val rb = FloatArray(4)

  /** One shape in the view's local coordinates (may extend outside the view). */
  fun draw(
    canvas: Canvas, backdrop: RenderNode?,
    left: Float, top: Float, right: Float, bottom: Float, radius: Float,
    winX: Float, winY: Float, look: GlassLook,
    lightAngle: Float = -0.8f, zoom: Float = 1f,
    glowX: Float = 0f, glowY: Float = 0f, glow: Float = 0f,
    alpha: Float = 1f,
  ) {
    one[0] = left; one[1] = top; one[2] = right; one[3] = bottom; one[4] = radius
    drawShapes(canvas, backdrop, one, 1, winX, winY, look, 0f, lightAngle, zoom, (left + right) / 2f, (top + bottom) / 2f, glowX, glowY, glow, alpha)
  }

  /**
   * @param shapes [l, t, r, b, radius] × count, local coordinates
   * @param merge  distance (px) under which shapes melt together
   */
  fun drawShapes(
    canvas: Canvas, backdrop: RenderNode?,
    shapes: FloatArray, count: Int,
    winX: Float, winY: Float, look: GlassLook, merge: Float,
    lightAngle: Float = -0.8f, zoom: Float = 1f, zoomX: Float = 0f, zoomY: Float = 0f,
    glowX: Float = 0f, glowY: Float = 0f, glow: Float = 0f,
    alpha: Float = 1f,
  ) {
    val n = min(count, 8)
    if (n <= 0 || alpha <= 0.001f) return
    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
    var minHalf = Float.MAX_VALUE
    for (i in 0 until n) {
      val o = i * 5
      if (shapes[o + 2] - shapes[o] <= 1f || shapes[o + 3] - shapes[o + 1] <= 1f) continue
      l = min(l, shapes[o]); t = min(t, shapes[o + 1]); r = max(r, shapes[o + 2]); b = max(b, shapes[o + 3])
      minHalf = min(minHalf, min(shapes[o + 2] - shapes[o], shapes[o + 3] - shapes[o + 1]) / 2f)
    }
    if (l >= r || t >= b) return

    if (backdrop == null || !canvas.isHardwareAccelerated || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      for (i in 0 until n) drawFallback(canvas, shapes, i, look); return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      for (i in 0 until n) drawFrosted(canvas, backdrop, shapes, i, winX, winY, look); return
    }

    val pad = (look.refraction * 2f + look.blur * 3f + 2f) * density + merge
    val ox = (l - pad).roundToInt().toFloat(); val oy = (t - pad).roundToInt().toFloat()
    val rn = (node as? RenderNode) ?: RenderNode("liquid-glass").also { node = it }
    rn.setPosition(ox.toInt(), oy.toInt(), (r + pad).roundToInt(), (b + pad).roundToInt())
    val rc = rn.beginRecording()
    try {
      rc.translate(-(winX + ox), -(winY + oy))
      rc.drawRenderNode(backdrop)
    } finally { rn.endRecording() }

    val sh = (shader as? RuntimeShader) ?: RuntimeShader(GLASS_AGSL).also { shader = it }
    for (i in 0 until 8) {
      val name = SHAPE_NAMES[i]
      if (i < n) {
        val o = i * 5
        sh.setFloatUniform(name, shapes[o] - ox, shapes[o + 1] - oy, shapes[o + 2] - ox, shapes[o + 3] - oy)
        if (i < 4) ra[i] = shapes[o + 4] else rb[i - 4] = shapes[o + 4]
      } else sh.setFloatUniform(name, -1e5f, -1e5f, -1e5f + 1f, -1e5f + 1f)
    }
    val bezelPx = min(look.bezel * density, minHalf * 0.5f).coerceAtLeast(1f)
    val k = bezelPx / (look.bezel * density).coerceAtLeast(1f)
    sh.setFloatUniform("radiiA", ra[0], ra[1], ra[2], ra[3])
    sh.setFloatUniform("radiiB", rb[0], rb[1], rb[2], rb[3])
    sh.setFloatUniform("count", n.toFloat())
    sh.setFloatUniform("merge", merge)
    sh.setFloatUniform("bezel", bezelPx)
    sh.setFloatUniform("refraction", look.refraction * density * k)
    sh.setFloatUniform("chroma", look.chroma)
    sh.setFloatUniform("lightAngle", lightAngle)
    sh.setFloatUniform("highlight", look.highlight)
    sh.setFloatUniform("saturation", look.saturation)
    sh.setFloatUniform("tint", Color.red(look.tint) / 255f, Color.green(look.tint) / 255f, Color.blue(look.tint) / 255f, Color.alpha(look.tint) / 255f)
    sh.setFloatUniform("zoom", zoom)
    sh.setFloatUniform("zoomCenter", zoomX - ox, zoomY - oy)
    sh.setFloatUniform("glowPos", glowX - ox, glowY - oy)
    sh.setFloatUniform("glowStrength", glow)
    sh.setFloatUniform("glowRadius", 34f * density)
    val glass = RenderEffect.createRuntimeShaderEffect(sh, "content")
    val blur = if (look.blur > 0.1f) RenderEffect.createBlurEffect(look.blur * density, look.blur * density, Shader.TileMode.CLAMP) else null
    rn.setRenderEffect(if (blur != null) RenderEffect.createChainEffect(glass, blur) else glass)
    rn.setClipToOutline(false)
    rn.setAlpha(alpha.coerceIn(0f, 1f))
    canvas.drawRenderNode(rn)
  }

  /** Android 12: frosted glass, one shape. */
  private fun drawFrosted(canvas: Canvas, backdrop: RenderNode, s: FloatArray, i: Int, winX: Float, winY: Float, look: GlassLook) {
    val o = i * 5
    val l = s[o]; val t = s[o + 1]; val r = s[o + 2]; val b = s[o + 3]
    val rad = min(s[o + 4], min(r - l, b - t) / 2f)
    val pad = 2f
    val rn = RenderNode("liquid-glass-frost")
    rn.setPosition((l - pad).roundToInt(), (t - pad).roundToInt(), (r + pad).roundToInt(), (b + pad).roundToInt())
    val ox = (l - pad).roundToInt().toFloat(); val oy = (t - pad).roundToInt().toFloat()
    val rc = rn.beginRecording()
    try { rc.translate(-(winX + ox), -(winY + oy)); rc.drawRenderNode(backdrop) } finally { rn.endRecording() }
    rn.setRenderEffect(RenderEffect.createBlurEffect(max(look.blur, 8f) * density, max(look.blur, 8f) * density, Shader.TileMode.CLAMP))
    val outline = Outline(); outline.setRoundRect((l - ox).roundToInt(), (t - oy).roundToInt(), (r - ox).roundToInt(), (b - oy).roundToInt(), rad)
    rn.setOutline(outline); rn.setClipToOutline(true)
    canvas.drawRenderNode(rn)
    fill.color = look.tint; fill.alpha = max(Color.alpha(look.tint), 60)
    rect.set(l, t, r, b); canvas.drawRoundRect(rect, rad, rad, fill)
  }

  /** No backdrop (yet) / old devices: a light, glass-like translucent fill — never a dark blob. */
  private fun drawFallback(canvas: Canvas, s: FloatArray, i: Int, look: GlassLook) {
    val o = i * 5
    rect.set(s[o], s[o + 1], s[o + 2], s[o + 3])
    val rad = min(s[o + 4], min(rect.width(), rect.height()) / 2f)
    fill.color = Color.argb(34, 255, 255, 255)
    canvas.drawRoundRect(rect, rad, rad, fill)
    fill.color = look.tint; fill.alpha = min(Color.alpha(look.tint), 60)
    canvas.drawRoundRect(rect, rad, rad, fill)
  }

  private companion object {
    val SHAPE_NAMES = arrayOf("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7")
  }
}

/** Critically-damped-ish spring used by all the interactions (no allocation, no Animator). */
internal class Spring(var value: Float, var stiffness: Float = 420f, var dampingRatio: Float = 0.62f) {
  var target = value
  var velocity = 0f
  /** Advances by [dt] seconds; returns true while still moving. */
  fun step(dt: Float): Boolean {
    val k = stiffness; val c = 2f * dampingRatio * kotlin.math.sqrt(k)
    val a = -k * (value - target) - c * velocity
    velocity += a * dt; value += velocity * dt
    val moving = kotlin.math.abs(velocity) > 0.001f || kotlin.math.abs(value - target) > 0.0005f
    if (!moving) { value = target; velocity = 0f }
    return moving
  }
  fun snap(v: Float) { value = v; target = v; velocity = 0f }
}
