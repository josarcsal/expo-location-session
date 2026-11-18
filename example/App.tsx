import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { Button, Linking, ScrollView, StyleSheet, Text, View } from 'react-native';
import {
  addListener,
  getCurrentPosition,
  getState,
  LocationSessionError,
  requestPermission,
  start,
  stop,
} from 'expo-location-session';

const UPLOAD_BASE = 'https://localhost:8443/u';

function log(line: string) {
  console.log(`ELS ${line}`);
}

type Params = { get(key: string): string | null };

function parseParams(query: string): Params {
  const map = new Map<string, string>();
  for (const pair of query.split('&')) {
    if (!pair) continue;
    const [key, value = ''] = pair.split('=');
    map.set(decodeURIComponent(key!), decodeURIComponent(value));
  }
  return { get: (key) => map.get(key) ?? null };
}

function describe(error: unknown): string {
  if (error instanceof LocationSessionError) return `${error.code}: ${error.message}`;
  return String(error);
}

async function run(action: string, params: Params): Promise<string> {
  try {
    switch (action) {
      case 'request': {
        const level = params.get('level') === 'whenInUse' ? 'whenInUse' : 'always';
        return `permission ${JSON.stringify(await requestPermission(level))}`;
      }
      case 'position':
        return `position ${JSON.stringify(await getCurrentPosition({ accuracy: 'high' }))}`;
      case 'start': {
        const status = params.get('status') ?? '204';
        const dest = params.get('dest')?.split(',').map(Number);
        const expiresIn = Number(params.get('expiresIn') ?? 1800);
        await start({
          id: params.get('id') ?? `demo-${Date.now()}`,
          expiresAt: Date.now() + expiresIn * 1000,
          destination: dest ? { latitude: dest[0]!, longitude: dest[1]!, radiusMeters: dest[2] ?? 100 } : undefined,
          accuracy: 'high',
          distanceFilterMeters: Number(params.get('distance') ?? 10),
          upload: {
            url: params.get('url') ?? `${UPLOAD_BASE}?status=${status}`,
            headers: { Authorization: 'Bearer demo' },
            extras: { app: 'example' },
            minIntervalMs: Number(params.get('minInterval') ?? 15000),
          },
          android: { notification: { title: 'Sharing location', text: 'Session active', channelName: 'Location' } },
        });
        return 'started';
      }
      case 'stop':
        await stop();
        return 'stopped';
      default:
        return `state ${JSON.stringify(await getState())}`;
    }
  } catch (error) {
    return `${action} rejected ${describe(error)}`;
  }
}

export default function App() {
  const [result, setResult] = useState('');

  const perform = (action: string, params: Params = parseParams('')) =>
    run(action, params).then((line) => {
      log(line);
      setResult(line);
    });

  useEffect(() => {
    const subs = [
      addListener('location', (p) => log(`event location ${JSON.stringify(p)}`)),
      addListener('upload', (p) => log(`event upload ${JSON.stringify(p)}`)),
      addListener('stop', (p) => log(`event stop ${JSON.stringify(p)}`)),
      addListener('error', (p) => log(`event error ${JSON.stringify(p)}`)),
    ];
    const handleUrl = (url: string | null | undefined) => {
      if (!url) return;
      const [path = '', query = ''] = url.replace(/^[a-z+]+:\/\//, '').split('?');
      perform(path.replace(/\/+$/, ''), parseParams(query));
    };
    const linkSub = Linking.addEventListener('url', ({ url }) => handleUrl(url));
    Linking.getInitialURL().then(handleUrl);
    perform('state');
    const poll = setInterval(async () => {
      try {
        const res = await fetch(`${UPLOAD_BASE.replace('/u', '/cmd')}`);
        if (res.status === 200) handleUrl(`locationsession://${await res.text()}`);
      } catch {}
    }, 1000);
    return () => {
      clearInterval(poll);
      subs.forEach((unsubscribe) => unsubscribe());
      linkSub.remove();
    };
  }, []);

  return (
    <View style={styles.container}>
      <Button title="Request Always" onPress={() => perform('request')} />
      <Button title="Current position" onPress={() => perform('position')} />
      <Button title="Start (204)" onPress={() => perform('start')} />
      <Button title="Start (410)" onPress={() => perform('start', parseParams('status=410'))} />
      <Button title="Stop" onPress={() => perform('stop')} />
      <Button title="Get state" onPress={() => perform('state')} />
      <ScrollView style={styles.result}>
        <Text>{result}</Text>
      </ScrollView>
      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
    paddingTop: 80,
    paddingHorizontal: 16,
  },
  result: {
    marginTop: 16,
  },
});
