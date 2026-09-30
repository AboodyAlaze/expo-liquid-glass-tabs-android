package expo.modules.liquidglasstabsandroid

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class TabItem : Record {
  @Field val key: String = ""
  @Field val title: String = ""
  @Field val icon: String = "home"
}

class ExpoLiquidGlassTabsAndroidModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("LiquidGlass")

    View(LiquidTabBarView::class) {
      Events("onTabPress")

      Prop("tabs") { view: LiquidTabBarView, tabs: List<TabItem> ->
        view.tabs.value = tabs.map { TabData(it.key, it.title, it.icon) }
      }
      Prop("selectedIndex") { view: LiquidTabBarView, index: Int ->
        view.selected.intValue = index
      }
      Prop("bottomInset") { view: LiquidTabBarView, inset: Float ->
        view.bottomInset.floatValue = inset
      }
      Prop("dark") { view: LiquidTabBarView, dark: Boolean ->
        view.dark.value = dark
      }
      Prop("accentColor") { view: LiquidTabBarView, color: String? ->
        view.setAccent(color)
      }
      Prop("quality") { view: LiquidTabBarView, quality: String? ->
        view.quality.value = Quality.resolve(view.context, quality)
      }
    }
  }
}
