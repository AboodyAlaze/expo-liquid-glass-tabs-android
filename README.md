# expo-liquid-glass-tabs-android

A native **Liquid Glass** tab bar for Expo Router on Android (iOS 26 style): real refraction of the
screens behind it, and a liquid lens you can drag between tabs.

**v1.0 — Expo SDK 58 ready.** Rewritten without Jetpack Compose: a plain Android View drawn with
`RenderNode` + an AGSL shader. It starts instantly, can't hit Compose's attach-time crash, and has no
Compose versions to clash with your Expo / Kotlin setup.

![demo](media/demo.gif)

## Install

```bash
npx expo install expo-liquid-glass-tabs-android
```

It's a native module: use a development build or EAS build (not Expo Go).

## Use

```tsx
import { Tabs } from 'expo-router';
import { LiquidTabBar } from 'expo-liquid-glass-tabs-android';

export default function Layout() {
  return (
    <Tabs
      tabBar={(props) => (
        <LiquidTabBar {...props} icons={{ index: 'home', finance: 'wallet', settings: 'settings' }} />
      )}
      screenOptions={{ headerShown: false }}
    >
      <Tabs.Screen name="index" options={{ title: 'Home' }} />
      <Tabs.Screen name="finance" options={{ title: 'Finance', tabBarBadge: 2 }} />
      <Tabs.Screen name="settings" options={{ title: 'Settings' }} />
    </Tabs>
  );
}
```

Works with **Expo Router 58+** (`emitter` / `navigateToTab`) and older versions / React Navigation 7
(`navigation`) — same code.

The bar floats over your screens. Leave room for it:

```tsx
import { useLiquidTabBarHeight } from 'expo-liquid-glass-tabs-android';

const tabBarHeight = useLiquidTabBarHeight(); // bar + margin + safe area
<ScrollView contentContainerStyle={{ paddingBottom: tabBarHeight + 16 }} />
```

## Props

| prop | |
|---|---|
| `icons` | per route name: a built-in icon, or `{ path: 'M…' }` (any 24 × 24 SVG path) |
| `accentColor` | selected tab colour (any React Native colour). Default iOS blue |
| `inactiveColor` | other tabs. Default white (dark) / near-black (light) |
| `dark` | force dark / light glass. Default: system |
| `quality` | `'auto'` (default) · `'high'` · `'medium'` · `'low'` |
| `haptics` | haptic ticks on press / drag. Default `true` |
| `fallback` | what to render on iOS / web. Default nothing |

From the screen options it also reads: `title` / `tabBarLabel`, `tabBarBadge` (number, text, or `''`
for a dot), `tabBarAccessibilityLabel`, `href: null` (hides that route from the bar), and
`tabBarStyle: { display: 'none' }` on the focused screen (hides the bar).

Events: `tabPress` (preventable, like the default bar) and `tabLongPress`.

### Built-in icons

`home` `search` `person` `settings` `mail` `favorite` `star` `notifications` `add` `play` `cart`
`place` `phone` `info` `menu` `wallet` `payments` `receipt` `calendar` `chat` `camera` `list` `grid`
`bookmark` `history` `dashboard` `chart` `delivery` `bike` `work` `document` `help`
(+ aliases such as `explore`, `profile`, `gear`, `heart`, `bell`, `finance`, `truck`…).
Material Icons, Apache 2.0.

## Interaction

- **Tap**: the lens swells under your finger on the very first frame, then slides to the tab.
- **Drag**: the lens follows your finger with zero latency, stretches with speed, ticks on each tab.
- **Long press**: emits `tabLongPress`.
- The accent colour flows with the lens between tabs.
- TalkBack: every tab is a real, selectable tab.

## Quality

| level | |
|---|---|
| `high` | refraction, light blur, chromatic rim, lens |
| `medium` | same glass without blur / chromatic rim |
| `low` | frosted bar, no shader, near-zero cost — same animations |

`auto` picks by device (Android 13+ needed for the shader; low-RAM and battery saver step down).

## Performance & stability

- Nothing runs while idle: the screens behind are captured only on frames Android draws anyway, as
  references to their display lists (not pixels).
- The bar is visible from the first frame (frosted) and fades into glass once the backdrop is ready.
- The capture never re-enters, skips hidden states, pauses itself for 100 ms on any error instead of
  breaking the app, and backs off on slow devices.

## Changelog

**1.0.0**
- Expo SDK 58 support (new tab bar props `emitter` / `navigateToTab`), still works on older versions
- Rewritten without Jetpack Compose: faster start-up, no attach-time crash, no Compose version pins
- Instant touch response; colour flows with the lens; haptic ticks
- New: `tabBarBadge`, `tabLongPress`, `href: null`, `tabBarStyle display: none`, `inactiveColor`,
  `haptics`, 16 more icons + custom SVG path icons

## License

MIT
