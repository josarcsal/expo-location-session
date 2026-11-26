import { AppState, Button, Platform, ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { useCallback, useEffect, useState } from 'react';
import {
  addListener,
  clearQueue,
  configureDebug,
  flushDebugLog,
  getCurrentPosition,
  getDebugInstallId,
  getDebugLog,
  getPermissionStatus,
  getQueueStatus,
  getState,
  LocationSessionError,
  requestPermission,
  start,
  stop,
  syncQueue,
  type PermissionLevel,
  type PermissionStatus,
  type SessionState,
} from 'expo-location-session';
import { StatusBar } from 'expo-status-bar';

const DEFAULT_URL = 'https://localhost:8443/u';
const DEFAULT_HEADERS = '{ "Authorization": "Bearer demo" }';
const DEFAULT_DEBUG_URL = 'https://localhost:8443/logs';
const DEBUG_FLUSH_INTERVAL_MS = 10_000;
const DEBUG_MAX_FILE_BYTES = 100_000;
const DESTINATION_RADIUS_METERS = 100;
const MAX_LOG_LINES = 100;
const BACKGROUND_COLOR = '#fff';
const BORDER_COLOR = '#999';

type Destination = { latitude: number; longitude: number; radiusMeters: number };

function describe(error: unknown): string {
  if (error instanceof LocationSessionError) return `${error.code}: ${error.message}`;
  return String(error);
}

export default function App() {
  const [permission, setPermission] = useState<PermissionStatus | null>(null);
  const [session, setSession] = useState<SessionState | null>(null);
  const [url, setUrl] = useState(DEFAULT_URL);
  const [headers, setHeaders] = useState(DEFAULT_HEADERS);
  const [expiryMinutes, setExpiryMinutes] = useState('30');
  const [destination, setDestination] = useState<Destination | null>(null);
  const [log, setLog] = useState<string[]>([]);
  const [debugUrl, setDebugUrl] = useState(DEFAULT_DEBUG_URL);
  const [debugEnabled, setDebugEnabled] = useState(false);
  const [debugEvents, setDebugEvents] = useState(0);
  const [trail, setTrail] = useState(false);
  const [refreshUrl, setRefreshUrl] = useState('');

  const append = useCallback((line: string) => {
    console.log(`ELS ${line}`);
    const time = new Date().toLocaleTimeString();
    setLog((lines) => [`${time} ${line}`, ...lines].slice(0, MAX_LOG_LINES));
  }, []);

  const refresh = useCallback(async () => {
    try {
      setPermission(await getPermissionStatus());
      setSession(await getState());
    } catch (error) {
      append(`refresh failed ${describe(error)}`);
    }
  }, [append]);

  const request = async (level: PermissionLevel) => {
    try {
      const status = await requestPermission(level);
      setPermission(status);
      append(`permission ${JSON.stringify(status)}`);
    } catch (error) {
      append(`requestPermission rejected ${describe(error)}`);
    }
  };

  const toggleDestination = async (enabled: boolean) => {
    if (!enabled) return setDestination(null);
    try {
      const position = await getCurrentPosition({ accuracy: 'high' });
      setDestination({
        latitude: position.latitude,
        longitude: position.longitude,
        radiusMeters: DESTINATION_RADIUS_METERS,
      });
      append(`destination ${position.latitude.toFixed(5)}, ${position.longitude.toFixed(5)}`);
    } catch (error) {
      append(`getCurrentPosition rejected ${describe(error)}`);
    }
  };

  const toggleDebug = async (enabled: boolean) => {
    try {
      await configureDebug({
        enabled,
        url: debugUrl || undefined,
        headers: JSON.parse(headers || '{}'),
        flushIntervalMs: DEBUG_FLUSH_INTERVAL_MS,
        maxFileBytes: DEBUG_MAX_FILE_BYTES,
      });
      setDebugEnabled(enabled);
      append(`debug ${enabled ? 'on' : 'off'}, install ${await getDebugInstallId()}`);
    } catch (error) {
      append(`configureDebug rejected ${describe(error)}`);
    }
  };

  const flushDebug = async () => {
    try {
      const { sent } = await flushDebugLog();
      const lines = await getDebugLog();
      append(`debug flush sent ${sent}, ${lines.length} lines, last seq ${lines.at(-1)?.seq ?? '-'}`);
    } catch (error) {
      append(`flushDebugLog rejected ${describe(error)}`);
    }
  };

  const startSession = async () => {
    const id = `demo-${Date.now()}`;
    try {
      await start({
        id,
        expiresAt: Date.now() + Number(expiryMinutes) * 60_000,
        destination: destination ?? undefined,
        accuracy: 'high',
        distanceFilterMeters: 10,
        upload: {
          url,
          headers: JSON.parse(headers || '{}'),
          extras: { app: 'example' },
          queue: trail ? { mode: 'all' } : undefined,
          batch: trail ? { maxSize: 50 } : undefined,
          auth: refreshUrl ? { refresh: { url: refreshUrl } } : undefined,
        },
        android: {
          notification: {
            title: 'Sharing location',
            text: 'Session active',
            channelName: 'Location sharing',
          },
        },
      });
      append(`started ${id}`);
    } catch (error) {
      append(`start rejected ${describe(error)}`);
    }
    refresh();
  };

  const stopSession = async () => {
    try {
      await stop();
      append('stop requested');
    } catch (error) {
      append(`stop rejected ${describe(error)}`);
    }
    refresh();
  };

  const showQueueStatus = async () => {
    try {
      const status = await getQueueStatus();
      append(`queue ${status.pending} pending, ${status.sessions} sessions, oldest ${status.oldestAt ?? '-'}`);
    } catch (error) {
      append(`getQueueStatus rejected ${describe(error)}`);
    }
  };

  const sync = async () => {
    try {
      const { sent } = await syncQueue();
      append(`sync sent ${sent}`);
    } catch (error) {
      append(`syncQueue rejected ${describe(error)}`);
    }
  };

  const clear = async () => {
    try {
      await clearQueue();
      append('queue cleared');
    } catch (error) {
      append(`clearQueue rejected ${describe(error)}`);
    }
  };

  useEffect(() => {
    const subscriptions = [
      addListener('location', (p) => {
        append(`location ${p.latitude.toFixed(5)}, ${p.longitude.toFixed(5)} ±${Math.round(p.accuracy)}m`);
        refresh();
      }),
      addListener('upload', (p) => {
        append(`upload ${p.ok ? 'ok' : 'failed'} ${p.status ?? '-'} ${p.sessionId ?? '-'} ×${p.count ?? '-'}`);
        refresh();
      }),
      addListener('stop', (p) => {
        append(`stop ${p.reason} ${p.sessionId}`);
        refresh();
      }),
      addListener('error', (p) => append(`error ${p.code}: ${p.message} ${p.sessionId ?? '-'}`)),
      addListener('log', () => setDebugEvents((n) => n + 1)),
    ];
    const appState = AppState.addEventListener('change', (next) => {
      if (next === 'active') refresh();
    });
    refresh();
    return () => {
      subscriptions.forEach((unsubscribe) => unsubscribe());
      appState.remove();
    };
  }, [append, refresh]);

  return (
    <View style={styles.container}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={styles.heading}>Permission</Text>
        <Text>
          {permission
            ? `foreground ${permission.foreground} · background ${permission.background} · precise ${permission.precise} · notifications ${permission.notifications}`
            : '…'}
        </Text>
        <View style={styles.row}>
          <Button title="When in use" onPress={() => request('whenInUse')} testID="permission-when-in-use" />
          <Button title="Always" onPress={() => request('always')} testID="permission-always" />
        </View>

        <Text style={styles.heading}>Upload</Text>
        <TextInput
          style={styles.input}
          value={url}
          onChangeText={setUrl}
          autoCapitalize="none"
          autoCorrect={false}
          placeholder="Upload URL"
          testID="url"
        />
        <TextInput
          style={styles.input}
          value={headers}
          onChangeText={setHeaders}
          autoCapitalize="none"
          autoCorrect={false}
          placeholder="Headers (JSON)"
          testID="headers"
        />
        <TextInput
          style={styles.input}
          value={refreshUrl}
          onChangeText={setRefreshUrl}
          autoCapitalize="none"
          autoCorrect={false}
          placeholder="Refresh URL (optional)"
          testID="refresh-url"
        />
        <View style={styles.row}>
          <Text>Trail (queue all, batch 50)</Text>
          <Switch value={trail} onValueChange={setTrail} testID="trail" />
        </View>

        <Text style={styles.heading}>Session</Text>
        <View style={styles.row}>
          <Text>Destination at current position</Text>
          <Switch value={destination != null} onValueChange={toggleDestination} testID="destination" />
        </View>
        {destination && (
          <Text>
            {destination.latitude.toFixed(5)}, {destination.longitude.toFixed(5)} · {destination.radiusMeters} m
          </Text>
        )}
        <View style={styles.row}>
          <Text>Expires in (minutes)</Text>
          <TextInput
            style={[styles.input, styles.short]}
            value={expiryMinutes}
            onChangeText={setExpiryMinutes}
            keyboardType="number-pad"
            testID="expiry-minutes"
          />
        </View>
        <View style={styles.row}>
          <Button title="Start" onPress={startSession} testID="start" />
          <Button title="Stop" onPress={stopSession} testID="stop" />
        </View>

        <Text style={styles.heading}>Queue</Text>
        <View style={styles.row}>
          <Button title="Queue status" onPress={showQueueStatus} testID="queue-status" />
          <Button title="Sync queue" onPress={sync} testID="queue-sync" />
          <Button title="Clear queue" onPress={clear} testID="queue-clear" />
        </View>

        <Text style={styles.heading}>Debug log ({debugEvents} events)</Text>
        <TextInput
          style={styles.input}
          value={debugUrl}
          onChangeText={setDebugUrl}
          autoCapitalize="none"
          autoCorrect={false}
          placeholder="Debug URL"
          selectTextOnFocus
          testID="debug-url"
        />
        <View style={styles.row}>
          <Switch value={debugEnabled} onValueChange={toggleDebug} testID="debug-toggle" />
          <Button title="Flush" onPress={flushDebug} testID="debug-flush" />
        </View>

        <Text style={styles.heading}>State</Text>
        <Text testID="state">{session ? JSON.stringify(session, null, 2) : '…'}</Text>

        <Text style={styles.heading}>Events</Text>
        {log.map((line, index) => (
          <Text key={index} style={styles.log}>
            {line}
          </Text>
        ))}
      </ScrollView>
      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: BACKGROUND_COLOR,
  },
  content: {
    paddingTop: 64,
    paddingBottom: 32,
    paddingHorizontal: 16,
    gap: 8,
  },
  heading: {
    marginTop: 12,
    fontSize: 17,
    fontWeight: '600',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
  },
  input: {
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: BORDER_COLOR,
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  short: {
    minWidth: 64,
    textAlign: 'right',
  },
  log: {
    fontFamily: Platform.select({ ios: 'Menlo', default: 'monospace' }),
    fontSize: 12,
  },
});
