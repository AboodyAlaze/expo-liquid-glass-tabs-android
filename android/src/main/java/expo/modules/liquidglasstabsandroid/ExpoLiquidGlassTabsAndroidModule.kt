package expo.modules.liquidglasstabsandroid

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class TabItem : Record {
  @Field val key: String = ""
  @Field val title: String = ""
  @Field val icon: String = "home"
  @Field val iconPath: String? = null
  @Field val badge: String? = null
  @Field val accessibilityLabel: String? = null
}

/** JS colours arrive as processColor() numbers (ARGB, possibly unsigned). */
private fun Double?.toColorInt(): Int? = this?.toLong()?.toInt()

class ExpoLiquidGlassTabsAndroidModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("LiquidGlass")

    View(LiquidTabBarView::class) {
      Events("onTabPress", "onTabLongPress")

      Prop("tabs") { view: LiquidTabBarView, tabs: List<TabItem> ->
        view.tabs = tabs.map {
          TabData(it.key, it.title, it.icon, it.iconPath, it.badge, it.accessibilityLabel ?: it.title)
        }
      }
      Prop("selectedIndex") { view: LiquidTabBarView, index: Int ->
        view.setSelectedFromJs(index)
      }
      Prop("bottomInset") { view: LiquidTabBarView, inset: Float ->
        view.bottomInsetDp = inset
      }
      Prop("dark") { view: LiquidTabBarView, dark: Boolean ->
        view.dark = dark
      }
      Prop("accentColor") { view: LiquidTabBarView, color: Double? ->
        view.accent = color.toColorInt() ?: android.graphics.Color.rgb(10, 132, 255)
      }
      Prop("inactiveColor") { view: LiquidTabBarView, color: Double? ->
        view.inactiveColor = color.toColorInt()
      }
      Prop("quality") { view: LiquidTabBarView, quality: String? ->
        view.quality = Quality.resolve(view.context, quality)
      }
      Prop("haptics") { view: LiquidTabBarView, enabled: Boolean ->
        view.haptics = enabled
      }
    }
  }
}
