import { ScrollView, Text, View } from 'react-native';
import { useLiquidTabBarHeight } from 'expo-liquid-glass-tabs-android';

const COLORS = ['#FF5F6D', '#FFC371', '#47CACC', '#5B5BD6', '#63E6BE', '#F783AC'];

export default function Home() {
  const pad = useLiquidTabBarHeight();
  return (
    <ScrollView
      style={{ backgroundColor: '#0B0B10' }}
      contentContainerStyle={{ padding: 20, paddingTop: 70, paddingBottom: pad + 20 }}
    >
      <Text style={{ color: 'white', fontSize: 40, fontWeight: '900', marginBottom: 16 }}>Liquid Glass</Text>
      {Array.from({ length: 12 }).map((_, i) => (
        <View
          key={i}
          style={{
            height: 150, borderRadius: 28, marginVertical: 8, backgroundColor: COLORS[i % 6],
            justifyContent: 'flex-end', padding: 20,
          }}
        >
          <Text style={{ color: 'white', fontSize: 26, fontWeight: '700' }}>Card {i + 1}</Text>
        </View>
      ))}
    </ScrollView>
  );
}
