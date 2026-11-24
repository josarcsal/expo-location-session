export type PermissionLevel = 'whenInUse' | 'always';
export type PermissionValue = 'granted' | 'denied' | 'undetermined';
export type Accuracy = 'high' | 'balanced' | 'low';

export type PermissionStatus = {
  foreground: PermissionValue;
  background: PermissionValue;
  precise: boolean;
  notifications: PermissionValue;
};

export type Location = {
  latitude: number;
  longitude: number;
  accuracy: number;
  altitude?: number;
  speed?: number;
  heading?: number;
  timestamp: number;
};

export type SessionConfig = {
  id: string;
  expiresAt: number;
  destination?: { latitude: number; longitude: number; radiusMeters: number };
  accuracy?: Accuracy;
  distanceFilterMeters?: number;
  upload: {
    url: string;
    headers?: Record<string, string>;
    extras?: Record<string, string | number | boolean>;
    minIntervalMs?: number;
    queue?: {
      mode: 'latest' | 'all';
      maxRecords?: number;
      maxAgeMs?: number;
    };
    batch?: { maxSize: number; rootProperty?: string };
    body?: { template: Record<string, unknown> };
    auth?: { refresh: { url: string; headers?: Record<string, string>; body?: Record<string, unknown> } };
  };
  android: {
    notification: { title: string; text: string; channelName: string };
  };
};

export type StopReason = 'stopped' | 'expired' | 'arrived' | 'unauthorized' | 'rejected' | 'permissionRevoked';

export type SessionState =
  | {
      status: 'idle';
      lastStop?: { reason: StopReason; at: number; sessionId: string };
    }
  | {
      status: 'running';
      sessionId: string;
      startedAt: number;
      expiresAt: number;
      lastLocation?: Location;
      lastUploadAt?: number;
      survivesProcessDeath: boolean;
    };

export type QueueStatus = { pending: number; oldestAt?: number; sessions: number };
export type UploadResult = { ok: boolean; status?: number; at: number; sessionId?: string; count?: number };
export type StopEvent = { reason: StopReason; sessionId: string };
export type ErrorEvent = { code: string; message: string; sessionId?: string };

export type DebugOptions = {
  enabled: boolean;
  url?: string;
  headers?: Record<string, string>;
  flushIntervalMs?: number;
  maxFileBytes?: number;
};

export type DebugLogLevel = 'debug' | 'info' | 'warn' | 'error';
export type DebugLogTag =
  | 'lifecycle'
  | 'permission'
  | 'location'
  | 'upload'
  | 'stop'
  | 'service'
  | 'gate'
  | 'region'
  | 'store'
  | 'debug'
  | 'queue'
  | 'auth'
  | 'sync';

export type DebugLogLine = {
  seq: number;
  t: number;
  pid: number;
  level: DebugLogLevel;
  tag: DebugLogTag;
  msg: string;
  sessionId?: string;
  data?: Record<string, unknown>;
};

export type LocationSessionEventMap = {
  location: Location;
  upload: UploadResult;
  stop: StopEvent;
  error: ErrorEvent;
  log: DebugLogLine;
};

export type CurrentPositionOptions = {
  accuracy?: Accuracy;
  timeoutMs?: number;
  maxAgeMs?: number;
};

export class LocationSessionError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'LocationSessionError';
    this.code = code;
  }
}
