import { requireNativeView } from 'expo';
import { useCallback, useMemo, type ReactNode } from 'react';
import { Platform, StyleSheet, useColorScheme, type ViewProps } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

export type LiquidTabIcon =
  | 'home' | 'search' | 'explore' | 'person' | 'profile' | 'account'
  | 'settings' | 'mail' | 'inbox' | 'heart' | 'favorite' | 'star'
  | 'bell' | 'notifications' | 'add' | 'play' | 'music' | 'cart'
  | 'shop' | 'map' | 'place' | 'phone' | 'info' | 'menu';

export type LiquidGlassQuality = 'auto' | 'high' | 'medium' | 'low';

/**
 * Only the parts of the tab bar props we use. Structural, so it accepts the props from
 * Expo Router (any version) AND @react-navigation/bottom-tabs without depending on either.
 */
type TabRoute = { key: string; name: string; params?: object };
export type TabBarPropsLike = {
  state: { index: number; routes: TabRoute[] };
  descriptors: Record<string, { options: { title?: string; tabBarLabel?: unknown } }>;
  navigation: {
    emit(event: { type: 'tabPress'; target: string; canPreventDefault: true }): { defaultPrevented: boolean };
    navigate(name: string, params?: object): void;
  };
  insets: { bottom: number };
};

export type LiquidTabBarProps = TabBarPropsLike & {
  /** Icon per route name, e.g. { index: 'home', settings: 'settings' } */
  icons?: Record<string, LiquidTabIcon>;
  /** 'auto' (default) picks the best level for the device. */
  quality?: LiquidGlassQuality;
  /** Selected tab color. Default iOS blue. */
  accentColor?: string;
  /** Force dark / light glass. Default follows the system. */
  dark?: boolean;
  /** What to render on non-Android platforms (e.g. your iOS tab bar). Default: nothing. */
  fallback?: ReactNode;
};

type NativeProps = ViewProps & {
  tabs: { key: string; title: string; icon: string }[];
  selectedIndex: number;
  bottomInset: number;
  dark: boolean;
  accentColor?: string;
  quality: LiquidGlassQuality;
  onTabPress: (e: { nativeEvent: { index: number } }) => void;
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

export function LiquidTabBar(props: LiquidTabBarProps) {
  const { state, descriptors, navigation, insets, icons, quality = 'auto', accentColor, dark, fallback = null } = props;
  const scheme = useColorScheme();

  const tabs = useMemo(
    () =>
      state.routes.map((route) => {
        const options = descriptors[route.key]?.options ?? {};
        const title = typeof options.tabBarLabel === 'string' ? options.tabBarLabel : options.title ?? route.name;
        return { key: route.key, title, icon: icons?.[route.name] ?? 'home' };
      }),
    [state.routes, descriptors, icons]
  );

  // Same behaviour as the default tab bar
  const onTabPress = useCallback(
    (e: { nativeEvent: { index: number } }) => {
      const route = state.routes[e.nativeEvent.index];
      if (!route) return;
      const event = navigation.emit({ type: 'tabPress', target: route.key, canPreventDefault: true });
      if (state.index !== e.nativeEvent.index && !event.defaultPrevented) {
        navigation.navigate(route.name, route.params);
      }
    },
    [state, navigation]
  );

  if (!NativeLiquidTabBar) return <>{fallback}</>;

  return (
    <NativeLiquidTabBar
      style={[styles.bar, { height: BAR_HEIGHT + BAR_MARGIN + LENS_ROOM + insets.bottom }]}
      tabs={tabs}
      selectedIndex={state.index}
      bottomInset={insets.bottom}
      dark={dark ?? scheme === 'dark'}
      accentColor={accentColor}
      quality={quality}
      onTabPress={onTabPress}
    />
  );
}

const styles = StyleSheet.create({
  bar: { position: 'absolute', left: 0, right: 0, bottom: 0 },
});
