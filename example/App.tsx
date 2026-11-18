import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { Button, StyleSheet, Text, View } from 'react-native';
import { getState, LocationSessionError } from 'expo-location-session';

async function probeGetState(): Promise<string> {
  try {
    const state = await getState();
    return `state: ${JSON.stringify(state)}`;
  } catch (error) {
    const code = error instanceof LocationSessionError ? error.code : 'UNKNOWN';
    console.log(`getState rejected code=${code}`);
    return `getState rejected: ${code}`;
  }
}

export default function App() {
  const [result, setResult] = useState('');

  useEffect(() => {
    console.log('JS_RUNNING');
    probeGetState().then(setResult);
  }, []);

  return (
    <View style={styles.container}>
      <Button title="Get state" onPress={() => probeGetState().then(setResult)} />
      <Text>{result}</Text>
      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
    alignItems: 'center',
    justifyContent: 'center',
  },
});
