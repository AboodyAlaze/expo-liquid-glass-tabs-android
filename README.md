<div align="center">

# expo-liquid-glass-tabs-android

**A native Liquid Glass tab bar for Android — made for Expo Router.**

Real refraction, a liquid lens you can drag between tabs, and springs that feel like iOS 26 —
running natively at full frame rate, even on low-end phones.

[![npm](https://img.shields.io/npm/v/expo-liquid-glass-tabs-android?color=0A84FF)](https://www.npmjs.com/package/expo-liquid-glass-tabs-android)
[![license](https://img.shields.io/npm/l/expo-liquid-glass-tabs-android?color=34C759)](./LICENSE)
![platform](https://img.shields.io/badge/platform-Android-3DDC84)
![expo router](https://img.shields.io/badge/Expo%20Router-ready-000020)

<img src="https://raw.githubusercontent.com/AboodyAlaze/expo-liquid-glass-tabs-android/main/media/demo.gif" alt="Liquid Glass tab bar demo" width="720" />

</div>

---

## ✨ Features

- **Real Liquid Glass** — refraction concentrated at the edges, specular rim light, chromatic fringe, vibrancy.
- **Liquid lens** — press and drag across the bar; the lens lifts, magnifies what's under it, stretches with your finger and springs onto the nearest tab.
- **Expo Router ready** — one prop: `tabBar`. Also works with React Navigation bottom tabs.
- **Fast by design** — see [Performance](#-performance).
- **Adapts to the device** — automatic quality levels, battery-saver aware.
- **Accessible** — every tab is exposed to TalkBack as a real tab with its selected state.
- **Dark & light** — follows the system, or force it.

## 📦 Install

```bash
npx expo install expo-liquid-glass-tabs-android react-native-safe-area-context
```

> This package contains native code, so it needs a **development build** (`npx expo run:android`) — it does not run in Expo Go.

## 🚀 Usage with Expo Router

`app/(tabs)/_layout.tsx`

```tsx
import { Tabs } from 'expo-router';
import { LiquidTabBar } from 'expo-liquid-glass-tabs-android';

export default function TabLayout() {
  return (
    <Tabs
      tabBar={(props) => (
        <LiquidTabBar
          {...props}
          icons={{ index: 'home', explore: 'search', inbox: 'mail', settings: 'settings' }}
        />
      )}
      screenOptions={{
        headerShown: false,
        animation: 'fade',
        sceneStyle: { backgroundColor: '#000' }, // match your app background (see Tips)
      }}
    >
      <Tabs.Screen name="index" options={{ title: 'Home' }} />
      <Tabs.Screen name="explore" options={{ title: 'Explore' }} />
      <Tabs.Screen name="inbox" options={{ title: 'Inbox' }} />
      <Tabs.Screen name="settings" options={{ title: 'Settings' }} />
    </Tabs>
  );
}
```

The bar **floats over your screens** (that's what lets the glass refract them), so leave room at the bottom of scrollable content:

```tsx
import { ScrollView } from 'react-native';
import { useLiquidTabBarHeight } from 'expo-liquid-glass-tabs-android';

export default function Home() {
  const tabBarHeight = useLiquidTabBarHeight();
  return <ScrollView contentContainerStyle={{ paddingBottom: tabBarHeight }}>{/* … */}</ScrollView>;
}
```

### Android glass + iOS native glass

On iOS you'll usually want Apple's own Liquid Glass. Use the `fallback` prop, or branch by platform:

```tsx
<Tabs tabBar={(props) => <LiquidTabBar {...props} fallback={<MyIOSTabBar {...props} />} />} />
```

## ⚡ Performance

Built so the glass never costs you frames:

| | |
|---|---|
| **Native animations** | The lens, springs and jelly stretch run in Kotlin. JS only receives *"tab N was pressed"* — a busy JS thread can't make the bar stutter. |
| **Zero recompositions** | While you drag, every value is read in the draw phase. Nothing is rebuilt. |
| **Only where the glass is** | Blur and shader run on a small layer the size of the bar, not the whole screen. |
| **Idle = free** | The screens behind are captured only on frames Android is already drawing. When nothing moves, no work is done. The lens isn't rendered at all at rest. |
| **Cheap shader** | The inside of the glass costs one texture sample; the heavy math runs only on the thin edge. |

### Quality levels

`quality="auto"` (default) picks the best level for each device:

| Level | When | What you get |
|---|---|---|
| `high` | Performance-class devices, or 8+ cores with a large heap | Everything: refraction, blur, chromatic fringe, tilt-reactive light |
| `medium` | Other Android 13+ devices | Same glass without the costly extras (no blur, no fringe, no gyroscope) |
| `low` | Android below 13, low-RAM devices | No shader — clean frosted glass at near-zero cost. Animations stay identical |

Battery saver steps down one level automatically. Force a level with `quality="high" | "medium" | "low"`.

## 🎛 Props

`LiquidTabBar` accepts the tab bar props from Expo Router / React Navigation, plus:

| Prop | Type | Default | Description |
|---|---|---|---|
| `icons` | `Record<string, LiquidTabIcon>` | `'home'` | Icon per **route name**, e.g. `{ index: 'home', '(settings)': 'settings' }` |
| `quality` | `'auto' \| 'high' \| 'medium' \| 'low'` | `'auto'` | Rendering level (see above) |
| `accentColor` | `string` | `#0A84FF` | Color of the selected tab |
| `dark` | `boolean` | system | Force dark / light glass |
| `fallback` | `ReactNode` | `null` | Rendered on non-Android platforms |

**Icons:** `home` · `search` / `explore` · `person` / `profile` · `settings` · `mail` / `inbox` · `heart` · `star` · `bell` · `add` · `play` / `music` · `cart` · `map` · `phone` · `info` · `menu`

Tab titles come from each screen's `title` (or `tabBarLabel`) option.

### `useLiquidTabBarHeight()`

Returns the space (in dp) the floating bar covers at the bottom, including the safe-area inset.

## 💡 Tips

**White flash when switching tabs in dark mode?** That's the navigator's default white background showing through the `fade` animation. Give it your app's background color:

```tsx
// tabs layout
screenOptions={{ sceneStyle: { backgroundColor: isDark ? '#000' : '#fff' } }}
```

```tsx
// root layout (app/_layout.tsx)
import * as SystemUI from 'expo-system-ui';
import { DarkTheme, DefaultTheme, ThemeProvider } from 'expo-router';

useEffect(() => { SystemUI.setBackgroundColorAsync(isDark ? '#000' : '#fff'); }, [isDark]);

<ThemeProvider value={isDark ? DarkTheme : DefaultTheme}>{/* … */}</ThemeProvider>
```

**Route groups** like `(home)` are route names too — use them as keys in `icons`.

## 🧪 Example app

A complete Expo Router example (4 tabs, dark theme, no white flash) lives in [`example/`](./example):

```bash
git clone https://github.com/AboodyAlaze/expo-liquid-glass-tabs-android
cd expo-liquid-glass-tabs-android
npm install
cd example && npm install
npx expo run:android --device
```

## 📋 Requirements

- Expo SDK 57+ (development build)
- Android 7+ (full glass on Android 13+, frosted fallback below)
- `react-native-safe-area-context`

## License

MIT © [Abdullrahman Alaa](https://github.com/AboodyAlaze)
