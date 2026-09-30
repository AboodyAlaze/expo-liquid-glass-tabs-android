package expo.modules.liquidglasstabsandroid.engine

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MAX_SHAPES = 6      // total shapes per LiquidGlass
private const val MAX_PER_CLUSTER = 4  // shapes that can melt together
private val SHAPE_UNIFORMS = arrayOf("shape0", "shape1", "shape2", "shape3")

/**
 * A glass shape in the parent's coordinate space (pixels).
 * [zoom] > 1 magnifies what's under it (lens effect).
 */
data class GlassShape(
    val rect: Rect,
    val cornerRadius: Float,
    val zoom: Float = 1f,
    val glow: Float = 0f,             // 0..1 touch light strength
    val glowAt: Offset = Offset.Zero, // touch point, parent coordinates
    val opacity: Float = 1f,          // fade a lens in/out without touching the others
)

/** Visual tuning. Distances are in dp. */
data class GlassStyle(
    val blurRadius: Float = 3f,
    val refraction: Float = 14f,
    val bezel: Float = 16f,
    val chroma: Float = 0.10f,
    val highlight: Float = 1f,
    val saturation: Float = 1.4f,
    val tint: Color = Color.White.copy(alpha = 0.06f),
    val adaptive: Float = 1f,
    val merge: Float = 28f,
)

/** Put this on the content BEHIND the glass. Recording is a display list: nearly free. */
fun Modifier.glassBackdrop(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/**
 * Renders glass shapes over [backdrop].
 *
 * Performance design:
 *  - [shapes], [lightAngle], [opacity] are lambdas read ONLY in layout/draw → zero recomposition while animating.
 *  - Shapes are clustered: each cluster gets its own tiny layer sized to its bounds,
 *    so blur + shader never touch pixels outside the glass.
 *  - Shapes far apart are split into separate layers (exact, since they can't merge anyway).
 *  - Empty shape list → no effect is rendered at all.
 */
@Composable
fun LiquidGlass(
    backdrop: GraphicsLayer,
    shapes: () -> List<GlassShape>,
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle(),
    lightAngle: () -> Float = { -0.8f },
    opacity: () -> Float = { 1f },
    intensity: () -> Float = { 1f }, // live multiplier for refraction + chroma (read in draw phase)
    enabled: Boolean = true,          // false → lightweight fallback (weak devices)
) {
    val density = LocalDensity.current.density
    val currentShapes = rememberUpdatedState(shapes)
    val clusters = remember(style, density) {
        val pad = (style.refraction * 2f + style.blurRadius * 3f + 2f) * density // room for intensity up to 2x
        derivedStateOf { clusterize(currentShapes.value(), style.merge * density, pad) }
    }

    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Box(modifier.fillMaxSize()) {
            for (i in 0 until MAX_SHAPES) {
                GlassNode(i, clusters, backdrop, style, density, lightAngle, opacity, intensity)
            }
        }
    } else {
        GlassFallback(currentShapes, opacity, modifier)
    }
}

internal class Cluster(val ox: Int, val oy: Int, val w: Int, val h: Int, val members: List<GlassShape>)

private fun gap(a: Rect, b: Rect): Float {
    val gx = max(0f, max(a.left - b.right, b.left - a.right))
    val gy = max(0f, max(a.top - b.bottom, b.top - a.bottom))
    return hypot(gx, gy)
}

internal fun clusterize(all: List<GlassShape>, merge: Float, pad: Float): List<Cluster> {
    val shapes = if (all.size > MAX_SHAPES) all.subList(0, MAX_SHAPES) else all
    if (shapes.isEmpty()) return emptyList()
    val parent = IntArray(shapes.size) { it }
    fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
    for (i in shapes.indices) for (j in i + 1 until shapes.size) {
        if (gap(shapes[i].rect, shapes[j].rect) < merge) parent[find(j)] = find(i)
    }
    return shapes.indices.groupBy { find(it) }.values.map { idx ->
        val members = idx.take(MAX_PER_CLUSTER).map { shapes[it] }
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (m in members) {
            l = min(l, m.rect.left); t = min(t, m.rect.top)
            r = max(r, m.rect.right); b = max(b, m.rect.bottom)
        }
        val ox = floor(l - pad).toInt()
        val oy = floor(t - pad).toInt()
        Cluster(ox, oy, ceil(r + pad).toInt() - ox, ceil(b + pad).toInt() - oy, members)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun GlassNode(
    index: Int,
    clusters: State<List<Cluster>>,
    backdrop: GraphicsLayer,
    style: GlassStyle,
    density: Float,
    lightAngle: () -> Float,
    opacity: () -> Float,
    intensity: () -> Float,
) {
    val shader = remember { RuntimeShader(LIQUID_GLASS_AGSL) }
    // Blur effect is immutable → build once and reuse every frame
    val blur = remember(style.blurRadius, density) {
        val px = style.blurRadius * density
        if (px > 0.5f) RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP) else null
    }
    Box(
        Modifier
            .layout { measurable, constraints ->
                val c = clusters.value.getOrNull(index)
                val placeable = measurable.measure(Constraints.fixed(c?.w ?: 0, c?.h ?: 0))
                // IMPORTANT: report the parent's MIN size, not the cluster size.
                // A cluster wider than the screen (bar + refraction padding) would otherwise be
                // silently re-centered by Compose → the glass drifted left of its content.
                layout(constraints.minWidth, constraints.minHeight) {
                    placeable.place(c?.ox ?: 0, c?.oy ?: 0)
                }
            }
            .graphicsLayer {
                val c = clusters.value.getOrNull(index)
                if (c == null) {
                    renderEffect = null
                    return@graphicsLayer
                }
                clip = true
                alpha = (opacity() * c.members.maxOf { it.opacity }).coerceIn(0f, 1f)
                shader.applyUniforms(c, style, lightAngle(), density, intensity())
                val glass = RenderEffect.createRuntimeShaderEffect(shader, "content")
                renderEffect = (if (blur != null) RenderEffect.createChainEffect(glass, blur) else glass)
                    .asComposeRenderEffect()
            }
            .drawBehind {
                val c = clusters.value.getOrNull(index) ?: return@drawBehind
                translate(-c.ox.toFloat(), -c.oy.toFloat()) { drawLayer(backdrop) }
            }
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RuntimeShader.applyUniforms(c: Cluster, s: GlassStyle, lightAngle: Float, density: Float, intensity: Float) {
    val radii = FloatArray(4)
    val dx = c.ox.toFloat()
    val dy = c.oy.toFloat()
    var zoom = 1f
    var zx = 0f
    var zy = 0f
    var glow = 0f
    var gx = 0f
    var gy = 0f
    for (i in 0 until MAX_PER_CLUSTER) {
        val m = c.members.getOrNull(i)
        if (m != null) {
            val r = m.rect
            setFloatUniform(SHAPE_UNIFORMS[i], r.left - dx, r.top - dy, r.right - dx, r.bottom - dy)
            radii[i] = m.cornerRadius
            if (m.zoom > zoom) {
                zoom = m.zoom; zx = r.center.x - dx; zy = r.center.y - dy
            }
            if (m.glow > glow) {
                glow = m.glow; gx = m.glowAt.x - dx; gy = m.glowAt.y - dy
            }
        } else {
            setFloatUniform(SHAPE_UNIFORMS[i], -100000f, -100000f, -99999f, -99999f)
        }
    }
    setFloatUniform("radii", radii[0], radii[1], radii[2], radii[3])
    setFloatUniform("shapeCount", c.members.size.toFloat())
    setFloatUniform("merge", s.merge * density)
    // Bezel can't be thicker than ~half the smallest shape's radius, otherwise small knobs
    // become "all edge" and bend everything. Refraction scales down with it.
    val minHalf = c.members.minOf { min(it.rect.width, it.rect.height) / 2f }
    val bezelPx = min(s.bezel * density, minHalf * 0.5f).coerceAtLeast(1f)
    val scale = bezelPx / (s.bezel * density).coerceAtLeast(1f)
    setFloatUniform("bezel", bezelPx)
    setFloatUniform("refraction", s.refraction * density * intensity * scale)
    setFloatUniform("chroma", s.chroma * intensity)
    setFloatUniform("lightAngle", lightAngle)
    setFloatUniform("highlight", s.highlight)
    setFloatUniform("saturation", s.saturation)
    setFloatUniform("tint", s.tint.red, s.tint.green, s.tint.blue, s.tint.alpha)
    setFloatUniform("adaptive", s.adaptive)
    setFloatUniform("zoom", zoom)
    setFloatUniform("zoomCenter", zx, zy)
    setFloatUniform("glowPos", gx, gy)
    setFloatUniform("glowStrength", glow)
    setFloatUniform("glowRadius", 34f * density)
}

/** Android 12 and below: no AGSL → light frosted shapes, drawn in the draw phase only. */
@Composable
private fun GlassFallback(shapes: State<() -> List<GlassShape>>, opacity: () -> Float, modifier: Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val o = opacity()
        shapes.value().forEach { s ->
            val a = (o * s.opacity).coerceIn(0f, 1f)
            val cr = CornerRadius(s.cornerRadius)
            drawRoundRect(Color.White.copy(alpha = 0.22f * a), s.rect.topLeft, s.rect.size, cr)
            drawRoundRect(
                Color.White.copy(alpha = 0.5f * a), s.rect.topLeft, s.rect.size, cr,
                style = Stroke(1.dp.toPx())
            )
        }
    }
}

/**
 * Light direction that follows device tilt.
 * - Paused when the app isn't visible (lifecycle-aware) → no battery drain.
 * - Only publishes changes above a threshold → the glass doesn't redraw while the phone sits still.
 */
@Composable
fun rememberTiltLightAngle(base: Float = -0.8f): State<Float> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val angle = remember { mutableFloatStateOf(base) }
    DisposableEffect(lifecycle) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smoothed = base
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val target = base + (e.values[0] / SensorManager.GRAVITY_EARTH) * 1.2f
                smoothed += (target - smoothed) * 0.2f
                if (abs(smoothed - angle.floatValue) > 0.01f) angle.floatValue = smoothed
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                Lifecycle.Event.ON_PAUSE -> sm.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sm.unregisterListener(listener)
        }
    }
    return angle
}
