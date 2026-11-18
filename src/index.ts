import { NitroModules } from 'react-native-nitro-modules';
import type {
  LocationSession as LocationSessionSpec,
  NativeLocation,
  NativePermissionStatus,
  NativeSessionState,
} from './specs/location-session.nitro';
import {
  LocationSessionError,
  type CurrentPositionOptions,
  type Location,
  type LocationSessionEventMap,
  type PermissionLevel,
  type PermissionStatus,
  type SessionConfig,
  type SessionState,
  type StopReason,
} from './types';

export * from './types';

const DEFAULT_ACCURACY = 'balanced';
const DEFAULT_DISTANCE_FILTER_METERS = 50;
const DEFAULT_MIN_INTERVAL_MS = 15000;
const DEFAULT_TIMEOUT_MS = 15000;
const DEFAULT_MAX_AGE_MS = 0;
const ERROR_PREFIX = /(?:^|\s)([A-Z][A-Z0-9_]+): ?([\s\S]*)$/;

let native: LocationSessionSpec | undefined;

function getNative(): LocationSessionSpec {
  native ??= NitroModules.createHybridObject<LocationSessionSpec>('LocationSession');
  return native;
}

function toLocationSessionError(error: unknown): unknown {
  if (error instanceof LocationSessionError) return error;
  const message = error instanceof Error ? error.message : String(error);
  const match = ERROR_PREFIX.exec(message);
  if (!match) return error;
  return new LocationSessionError(match[1] as string, match[2] as string);
}

async function call<T>(run: (session: LocationSessionSpec) => Promise<T>): Promise<T> {
  try {
    return await run(getNative());
  } catch (error) {
    throw toLocationSessionError(error);
  }
}

function toLocation(location: NativeLocation): Location {
  const result: Location = {
    latitude: location.latitude,
    longitude: location.longitude,
    accuracy: location.accuracy,
    timestamp: location.timestamp,
  };
  if (location.altitude != null) result.altitude = location.altitude;
  if (location.speed != null) result.speed = location.speed;
  if (location.heading != null) result.heading = location.heading;
  return result;
}

function toPermissionStatus(status: NativePermissionStatus): PermissionStatus {
  return {
    foreground: status.foreground,
    background: status.background,
    precise: status.precise,
    notifications: status.notifications,
  };
}

function toSessionState(state: NativeSessionState): SessionState {
  if (state.status === 'running') {
    const running: Extract<SessionState, { status: 'running' }> = {
      status: 'running',
      sessionId: state.sessionId ?? '',
      startedAt: state.startedAt ?? 0,
      expiresAt: state.expiresAt ?? 0,
      survivesProcessDeath: state.survivesProcessDeath ?? false,
    };
    if (state.lastLocation) running.lastLocation = toLocation(state.lastLocation);
    if (state.lastUploadAt != null) running.lastUploadAt = state.lastUploadAt;
    return running;
  }
  if (state.lastStopReason != null && state.lastStopAt != null && state.lastStopSessionId != null)
    return {
      status: 'idle',
      lastStop: {
        reason: state.lastStopReason as StopReason,
        at: state.lastStopAt,
        sessionId: state.lastStopSessionId,
      },
    };

  return { status: 'idle' };
}

export function getPermissionStatus(): Promise<PermissionStatus> {
  return call(async (s) => toPermissionStatus(await s.getPermissionStatus()));
}

export function requestPermission(level: PermissionLevel): Promise<PermissionStatus> {
  return call(async (s) => toPermissionStatus(await s.requestPermission(level)));
}

export function getCurrentPosition(opts: CurrentPositionOptions = {}): Promise<Location> {
  return call(async (s) =>
    toLocation(
      await s.getCurrentPosition(
        opts.accuracy ?? DEFAULT_ACCURACY,
        opts.timeoutMs ?? DEFAULT_TIMEOUT_MS,
        opts.maxAgeMs ?? DEFAULT_MAX_AGE_MS,
      ),
    ),
  );
}

export function start(config: SessionConfig): Promise<void> {
  return call((s) =>
    s.start({
      id: config.id,
      expiresAt: config.expiresAt,
      destination: config.destination,
      accuracy: config.accuracy ?? DEFAULT_ACCURACY,
      distanceFilterMeters: config.distanceFilterMeters ?? DEFAULT_DISTANCE_FILTER_METERS,
      uploadUrl: config.upload.url,
      uploadHeaders: config.upload.headers ?? {},
      uploadExtrasJson: JSON.stringify(config.upload.extras ?? {}),
      uploadMinIntervalMs: config.upload.minIntervalMs ?? DEFAULT_MIN_INTERVAL_MS,
      androidNotificationTitle: config.android.notification.title,
      androidNotificationText: config.android.notification.text,
      androidNotificationChannelName: config.android.notification.channelName,
    }),
  );
}

export function stop(): Promise<void> {
  return call((s) => s.stop());
}

export function getState(): Promise<SessionState> {
  return call(async (s) => toSessionState(await s.getState()));
}

export function addListener<E extends keyof LocationSessionEventMap>(
  event: E,
  callback: (payload: LocationSessionEventMap[E]) => void,
): () => void {
  let session: LocationSessionSpec;
  let id: number;
  try {
    session = getNative();
    id = session.addListener(event, (payloadJson) => {
      callback(JSON.parse(payloadJson) as LocationSessionEventMap[E]);
    });
  } catch (error) {
    throw toLocationSessionError(error);
  }
  let removed = false;
  return () => {
    if (removed) return;
    removed = true;
    session.removeListener(id);
  };
}
