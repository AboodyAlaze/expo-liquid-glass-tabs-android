import { Tabs } from 'expo-router';
import { LiquidTabBar } from 'expo-liquid-glass-tabs-android';

export default function Layout() {
  return (
    <Tabs
      tabBar={(props) => <LiquidTabBar {...props} icons={{ index: 'home', explore: 'search' }} />}
      screenOptions={{ headerShown: false, animation: 'fade' }}
    >
      <Tabs.Screen name="index" options={{ title: 'Home' }} />
      <Tabs.Screen name="explore" options={{ title: 'Explore' }} />
    </Tabs>
  );
}
