package expo.modules.liquidglasstabsandroid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import expo.modules.liquidglasstabsandroid.engine.GlassShape
import expo.modules.liquidglasstabsandroid.engine.GlassStyle
import expo.modules.liquidglasstabsandroid.engine.LiquidGlass
import expo.modules.liquidglasstabsandroid.engine.glassBackdrop
import expo.modules.liquidglasstabsandroid.engine.rememberTiltLightAngle
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun LiquidTabBarContent(host: LiquidTabBarView) {
  var size by remember { mutableStateOf(IntSize.Zero) }
  Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
    val tabs = host.tabs.value
    if (size.width > 0 && size.height > 0 && tabs.isNotEmpty()) TabBarScene(host, size, tabs)
  }
}

private class BarGeo(size: IntSize, val count: Int, insetDp: Float, d: Float) {
  val w = size.width.toFloat()
  val h = size.height.toFloat()
  val barH = 66f * d
  private val barW = min(w - 32f * d, (90f * count).coerceIn(280f, 420f) * d)
  private val left = (w - barW) / 2f // centered
  private val bottom = h - (insetDp + 12f) * d
  val bar = Rect(left, bottom - barH, left + barW, bottom)
  val inset = 5f * d
  val tabW = (bar.width - inset * 2) / count
  fun tabCenter(i: Int) = bar.left + inset + tabW * (i.coerceIn(0, count - 1) + 0.5f)
  fun nearestTab(x: Float) = ((x - bar.left - inset) / tabW).toInt().coerceIn(0, count - 1)
  val minX = tabCenter(0)
  val maxX = tabCenter(count - 1)
}

@Composable
private fun TabBarScene(host: LiquidTabBarView, size: IntSize, tabs: List<TabData>) {
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()
  val dark = host.dark.value
  val quality = host.quality.value
  val accent = host.accent.value
  val selected = host.selected.intValue
  val insetDp = host.bottomInset.floatValue
  val geo = remember(size, tabs.size, insetDp, density) { BarGeo(size, tabs.size, insetDp, density.density) }

  val tilt = if (quality == Quality.HIGH) rememberTiltLightAngle() else null
  val lightFn = { tilt?.value ?: -0.8f }

  val backdrop = rememberGraphicsLayer() // React Native screens behind us
  val scene = rememberGraphicsLayer()    // screens + bar + icons (what the lens refracts)

  val selX = remember(geo) { Animatable(geo.tabCenter(selected)) }
  val lift = remember { Animatable(0f) }
  val lensStretch = remember { Animatable(0f) }

  // Selection changed from JS (navigation, back button, deep link) → slide there
  LaunchedEffect(selected, geo) { selX.animateTo(geo.tabCenter(selected), spring(0.75f, 420f)) }

  fun select(i: Int, pulse: Boolean) {
    host.selected.intValue = i          // optimistic, JS confirms through the prop
    host.emitTabPress(i)
    scope.launch { selX.animateTo(geo.tabCenter(i), spring(0.75f, 420f)) }
    if (pulse) scope.launch {
      lift.animateTo(0.55f, spring(dampingRatio = 0.85f, stiffness = 1400f))
      lift.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f))
    }
  }

  val enabled = quality != Quality.LOW
  val glassStyle = remember(dark, quality) {
    val base = GlassStyle(tint = if (dark) Color.Black.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.10f))
    if (quality == Quality.HIGH) base else base.copy(blurRadius = 0f, chroma = 0f)
  }
  val lensStyle = remember(quality) {
    val base = GlassStyle(
      blurRadius = 0f, refraction = 18f, bezel = 22f, chroma = 0.16f,
      highlight = 1.3f, saturation = 1.25f, tint = Color.White.copy(alpha = 0.04f),
    )
    if (quality == Quality.HIGH) base else base.copy(chroma = 0f)
  }
  val fg = if (dark) Color.White else Color(0xFF111111)

  Box(Modifier.fillMaxSize()) {
    // ───────── Level 1: backdrop + bar glass + icons (recorded into `scene`) ─────────
    Box(Modifier.fillMaxSize().glassBackdrop(scene)) {
      // Draws the captured React Native screens (a reference to their display lists)
      Box(
        Modifier.fillMaxSize().drawWithContent {
          backdrop.record { drawIntoCanvas { host.drawSource(it.nativeCanvas) } }
          drawLayer(backdrop)
        }
      )

      LiquidGlass(
        backdrop = backdrop,
        style = glassStyle,
        lightAngle = lightFn,
        enabled = enabled,
        shapes = { listOf(GlassShape(geo.bar, geo.barH / 2)) },
      )

      Box(
        Modifier
          .offset { IntOffset(geo.bar.left.roundToInt(), geo.bar.top.roundToInt()) }
          .size(with(density) { geo.bar.width.toDp() }, with(density) { geo.bar.height.toDp() })
          // Nothing drawn for the bar content can ever leave the capsule
          .clip(RoundedCornerShape(percent = 50))
          .drawBehind {
            val a = 1f - lift.value.coerceIn(0f, 1f)
            if (a > 0.01f) {
              // Exact size from the bar geometry (not the node size) → always a pill inside the bar
              val hh = geo.barH - geo.inset * 2
              drawRoundRect(
                color = (if (dark) Color.White else Color.Black).copy(alpha = 0.10f * a),
                topLeft = Offset(selX.value - geo.bar.left - geo.tabW / 2, geo.inset),
                size = Size(geo.tabW, hh),
                cornerRadius = CornerRadius(hh / 2),
              )
            }
          }
          .pointerInput(geo) {
            detectTapGestures { pos -> select(geo.nearestTab(pos.x + geo.bar.left), pulse = true) }
          }
          .pointerInput(geo) {
            val k = 20.dp.toPx()
            fun settle() {
              select(geo.nearestTab(selX.value), pulse = false)
              scope.launch { lift.animateTo(0f, spring(0.75f, 260f)) }
              scope.launch { lensStretch.animateTo(0f, spring(0.35f, 250f)) }
            }
            detectDragGestures(
              onDragStart = { pos ->
                scope.launch { lift.animateTo(1f, spring(0.55f, 500f)) }
                scope.launch {
                  selX.animateTo((pos.x + geo.bar.left).coerceIn(geo.minX, geo.maxX), spring(0.8f, 900f))
                }
              },
              onDragEnd = { settle() },
              onDragCancel = { settle() },
            ) { change, drag ->
              change.consume()
              scope.launch { selX.snapTo((selX.value + drag.x).coerceIn(geo.minX, geo.maxX)) }
              scope.launch {
                lensStretch.animateTo((abs(drag.x) / k).coerceAtMost(1f) * 0.2f, spring(stiffness = 900f))
              }
            }
          },
      ) {
        val current = selected
        tabs.forEachIndexed { i, tab ->
          val c = if (i == current) accent else fg
          Column(
            Modifier
              .offset { IntOffset((geo.tabCenter(i) - geo.bar.left - geo.tabW / 2).roundToInt(), 0) }
              .width(with(density) { geo.tabW.toDp() })
              .fillMaxHeight()
              .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = i == current
                contentDescription = tab.title
                onClick { select(i, pulse = true); true }
              },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Image(
              imageVector = iconFor(tab.icon),
              contentDescription = null,
              colorFilter = ColorFilter.tint(c),
              modifier = Modifier.size(24.dp),
            )
            BasicText(
              tab.title,
              maxLines = 1,
              style = TextStyle(color = c, fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            )
          }
        }
      }
    }

    // ───────── Level 2: the liquid lens (nothing rendered at rest) ─────────
    LiquidGlass(
      backdrop = scene,
      style = lensStyle,
      lightAngle = lightFn,
      enabled = enabled,
      shapes = {
        val l = lift.value.coerceIn(0f, 1.2f)
        if (l < 0.01f) emptyList() else {
          val s = lensStretch.value
          val w = geo.tabW * (1f + 0.18f * l) * (1f + s)
          val h = geo.barH * (1f + 0.28f * l) / (1f + s * 0.5f)
          val cx = selX.value
          val cy = geo.bar.center.y
          listOf(
            GlassShape(
              Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), h / 2,
              zoom = 1f + 0.3f * l, opacity = l.coerceAtMost(1f),
            )
          )
        }
      },
    )
  }
}

private fun iconFor(name: String): ImageVector = when (name.lowercase()) {
  "home" -> Icons.Filled.Home
  "search", "explore" -> Icons.Filled.Search
  "person", "profile", "account" -> Icons.Filled.Person
  "settings", "gear" -> Icons.Filled.Settings
  "mail", "email", "inbox" -> Icons.Filled.Email
  "heart", "favorite" -> Icons.Filled.Favorite
  "star" -> Icons.Filled.Star
  "bell", "notifications" -> Icons.Filled.Notifications
  "add", "plus" -> Icons.Filled.Add
  "play", "music" -> Icons.Filled.PlayArrow
  "cart", "shop" -> Icons.Filled.ShoppingCart
  "place", "map" -> Icons.Filled.Place
  "phone", "call" -> Icons.Filled.Call
  "info" -> Icons.Filled.Info
  "menu" -> Icons.Filled.Menu
  else -> Icons.Filled.Home
}