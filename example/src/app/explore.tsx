import { ScrollView, Text, View } from 'react-native';
import { useLiquidTabBarHeight } from 'expo-liquid-glass-tabs-android';

const COLORS = ['#5B5BD6', '#F783AC', '#47CACC'];

export default function Explore() {
  const pad = useLiquidTabBarHeight();
  return (
    <ScrollView
      style={{ backgroundColor: '#0B0B10' }}
      contentContainerStyle={{ padding: 20, paddingTop: 70, paddingBottom: pad + 20 }}
    >
      <Text style={{ color: 'white', fontSize: 40, fontWeight: '900', marginBottom: 16 }}>Explore</Text>
      {Array.from({ length: 16 }).map((_, i) => (
        <View key={i} style={{ flexDirection: 'row', alignItems: 'center', marginVertical: 10 }}>
          <View style={{ width: 48, height: 48, borderRadius: 24, backgroundColor: COLORS[i % 3] }} />
          <Text style={{ color: 'white', fontSize: 17, marginLeft: 14 }}>Item {i + 1}</Text>
        </View>
      ))}
    </ScrollView>
  );
}
