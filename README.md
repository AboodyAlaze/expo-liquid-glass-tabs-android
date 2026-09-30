# expo-liquid-glass-tabs-android

Native **Liquid Glass tab bar** for **Expo Router** on Android. Glass, lens and all animations run
natively (Kotlin + Jetpack Compose + AGSL); JS only receives "tab N was pressed".
Automatic quality levels keep it smooth on low-end devices.

## Install

    npx expo install expo-liquid-glass-tabs-android react-native-safe-area-context

Needs a development build (not Expo Go).

## Use

    import { Tabs } from 'expo-router';
    import { LiquidTabBar } from 'expo-liquid-glass-tabs-android';

    <Tabs tabBar={(props) => <LiquidTabBar {...props} icons={{ index: 'home', explore: 'search' }} />}>

The bar floats over your screens; leave room at the bottom of lists:

    const pad = useLiquidTabBarHeight();

## Props

| prop        | default  | |
|-------------|----------|-|
| icons       | 'home'   | route name → icon (home, search, person, settings, mail, heart, star, bell, add, play, cart, map, phone, info, menu) |
| quality     | 'auto'   | 'auto' / 'high' / 'medium' / 'low' |
| accentColor | #0A84FF  | selected tab color |
| dark        | system   | force dark / light glass |
| fallback    | null     | what to render on iOS / web |

## Develop

    npm install
    npm run build
    cd example && npm install
    npx expo run:android --device
