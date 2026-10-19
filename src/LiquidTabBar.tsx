import { requireNativeView } from 'expo';
import { useCallback, useMemo, useRef, type ReactNode } from 'react';
import {
  Platform,
  StyleSheet,
  processColor,
  useColorScheme,
  type ColorValue,
  type ViewProps,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

export type LiquidTabIcon =
  | 'home' | 'search' | 'explore' | 'person' | 'profile' | 'account' | 'user'
  | 'settings' | 'gear' | 'mail' | 'email' | 'inbox' | 'heart' | 'favorite' | 'star'
  | 'bell' | 'notifications' | 'add' | 'plus' | 'play' | 'music' | 'cart'
  | 'shop' | 'map' | 'place' | 'phone' | 'call' | 'info' | 'menu'
  | 'wallet' | 'finance' | 'payments' | 'money' | 'receipt' | 'orders'
  | 'calendar' | 'date' | 'chat' | 'message' | 'camera' | 'photo' | 'list'
  | 'grid' | 'apps' | 'bookmark' | 'history' | 'dashboard' | 'chart' | 'stats'
  | 'delivery' | 'truck' | 'shipping' | 'bike' | 'motorcycle' | 'work' | 'job'
  | 'document' | 'file' | 'help' | 'question';

/** A built-in icon name, or any icon as SVG path data drawn in a 24 × 24 box. */
export type LiquidTabIconSource = LiquidTabIcon | { path: string };

export type LiquidGlassQuality = 'auto' | 'high' | 'medium' | 'low';

type TabRoute = { key: string; name: string; params?: object };
type TabOptions = {
  title?: string;
  tabBarLabel?: unknown;
  tabBarBadge?: string | number;
  tabBarAccessibilityLabel?: string;
  /** Expo Router: `href: null` hides the route from the tab bar. */
  href?: unknown;
  tabBarStyle?: unknown;
};
type TabPressEvent = { type: 'tabPress'; target: string; canPreventDefault: true };
type TabLongPressEvent = { type: 'tabLongPress'; target: string };

/**
 * Only the parts of the tab bar props we use — structural, so it accepts the props of
 * Expo Router 58+ (`emitter` + `navigateToTab`) AND older Expo Router / React Navigation 7
 * (`navigation`) without depending on either.
 */
export type TabBarPropsLike = {
  state: { index: number; routes: TabRoute[] };
  descriptors: Record<string, { options: TabOptions }>;
  insets: { bottom: number };
  /** Expo Router 58+ */
  emitter?: { emit(event: any): any };
  /** Expo Router 58+ */
  navigateToTab?: (routeKey: string) => void;
  /** Expo Router ≤ 57 / React Navigation 7 */
  navigation?: { emit(event: any): any; navigate(...args: any[]): void };
};

export type LiquidTabBarProps = TabBarPropsLike & {
  /** Icon per route name, e.g. { index: 'home', settings: 'settings' } or { index: { path: 'M…' } } */
  icons?: Record<string, LiquidTabIconSource>;
  /** 'auto' (default) picks the best level for the device. */
  quality?: LiquidGlassQuality;
  /** Selected tab colour. Default iOS blue. */
  accentColor?: ColorValue;
  /** Colour of the other tabs. Default: white (dark) / near-black (light). */
  inactiveColor?: ColorValue;
  /** Force dark / light glass. Default follows the system. */
  dark?: boolean;
  /** Haptic ticks on press / drag. Default true. */
  haptics?: boolean;
  /** What to render on non-Android platforms (e.g. your iOS tab bar). Default: nothing. */
  fallback?: ReactNode;
};

type NativeTab = {
  key: string;
  title: string;
  icon: string;
  iconPath?: string;
  badge?: string;
  accessibilityLabel?: string;
};

type NativeProps = ViewProps & {
  tabs: NativeTab[];
  selectedIndex: number;
  bottomInset: number;
  dark: boolean;
  accentColor?: number | null;
  inactiveColor?: number | null;
  quality: LiquidGlassQuality;
  haptics: boolean;
  onTabPress: (e: { nativeEvent: { index: number } }) => void;
  onTabLongPress: (e: { nativeEvent: { index: number } }) => void;
};

const NativeLiquidTabBar =
  Platform.OS === 'android' ? requireNativeView<NativeProps>('LiquidGlass') : null;

// Must match the native layout: bar 66 + bottom margin 12 + room for the lens 14
const BAR_HEIGHT = 66;
const BAR_MARGIN = 12;
const LENS_ROOM = 14;

/** Space to leave at the bottom of your screens (the bar floats over them). */
export function useLiquidTabBarHeight(): number {
  const insets = useSafeAreaInsets();
  return BAR_HEIGHT + BAR_MARGIN + insets.bottom;
}

const toColor = (c?: ColorValue) => (c == null ? null : ((processColor(c) as number | null | undefined) ?? null));

function labelOf(options: TabOptions, route: TabRoute) {
  if (typeof options.tabBarLabel === 'string') return options.tabBarLabel;
  return options.title ?? route.name;
}

export function LiquidTabBar(props: LiquidTabBarProps) {
  const {
    state,
    descriptors,
    insets,
    icons,
    quality = 'auto',
    accentColor,
    inactiveColor,
    dark,
    haptics = true,
    fallback = null,
  } = props;
  const scheme = useColorScheme();

  // Routes shown in the bar (Expo Router's `href: null` hides one) + their index in state.routes
  const visible = useMemo(
    () =>
      state.routes
        .map((route, routeIndex) => ({ route, routeIndex }))
        .filter(({ route }) => descriptors[route.key]?.options?.href !== null),
    [state.routes, descriptors]
  );

  const tabs = useMemo<NativeTab[]>(
    () =>
      visible.map(({ route }) => {
        const options = descriptors[route.key]?.options ?? {};
        const title = labelOf(options, route);
        const source = icons?.[route.name] ?? 'home';
        const badge = options.tabBarBadge;
        return {
          key: route.key,
          title,
          icon: typeof source === 'string' ? source : 'custom',
          iconPath: typeof source === 'string' ? undefined : source.path,
          badge: badge == null ? undefined : String(badge),
          accessibilityLabel: options.tabBarAccessibilityLabel ?? title,
        };
      }),
    [visible, descriptors, icons]
  );

  const selectedIndex = visible.findIndex((v) => v.routeIndex === state.index);

  // Latest props for the native callbacks (stable functions → no needless native updates)
  const latest = useRef(props);
  latest.current = props;
  const visibleRef = useRef(visible);
  visibleRef.current = visible;

  // Same behaviour as Expo's own tab bar — on every Expo Router version
  const onTabPress = useCallback((e: { nativeEvent: { index: number } }) => {
    const p = latest.current;
    const entry = visibleRef.current[e.nativeEvent.index];
    if (!entry) return;
    const { route, routeIndex } = entry;
    const event: TabPressEvent = { type: 'tabPress', target: route.key, canPreventDefault: true };
    const result = p.emitter ? p.emitter.emit(event) : p.navigation?.emit(event);
    if (p.state.index === routeIndex || result?.defaultPrevented) return;
    if (p.navigateToTab) p.navigateToTab(route.key); // Expo Router 58+
    else p.navigation?.navigate(route.name, route.params); // Expo Router ≤ 57 / React Navigation 7
  }, []);

  const onTabLongPress = useCallback((e: { nativeEvent: { index: number } }) => {
    const p = latest.current;
    const entry = visibleRef.current[e.nativeEvent.index];
    if (!entry) return;
    const event: TabLongPressEvent = { type: 'tabLongPress', target: entry.route.key };
    if (p.emitter) p.emitter.emit(event);
    else p.navigation?.emit(event);
  }, []);

  if (!NativeLiquidTabBar) return <>{fallback}</>;

  // `tabBarStyle: { display: 'none' }` on the focused screen hides the bar (like the default bar)
  const focused = state.routes[state.index];
  const focusedStyle = focused ? StyleSheet.flatten(descriptors[focused.key]?.options?.tabBarStyle as never) : null;
  if ((focusedStyle as { display?: string } | null)?.display === 'none') return null;

  return (
    <NativeLiquidTabBar
      style={[styles.bar, { height: BAR_HEIGHT + BAR_MARGIN + LENS_ROOM + insets.bottom }]}
      tabs={tabs}
      selectedIndex={selectedIndex}
      bottomInset={insets.bottom}
      dark={dark ?? scheme === 'dark'}
      accentColor={toColor(accentColor)}
      inactiveColor={toColor(inactiveColor)}
      quality={quality}
      haptics={haptics}
      onTabPress={onTabPress}
      onTabLongPress={onTabLongPress}
    />
  );
}

const styles = StyleSheet.create({
  bar: { position: 'absolute', left: 0, right: 0, bottom: 0 },
});
