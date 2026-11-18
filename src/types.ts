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

export type UploadResult = { ok: boolean; status?: number; at: number };
export type StopEvent = { reason: StopReason; sessionId: string };
export type ErrorEvent = { code: string; message: string };

export type LocationSessionEventMap = {
  location: Location;
  upload: UploadResult;
  stop: StopEvent;
  error: ErrorEvent;
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
