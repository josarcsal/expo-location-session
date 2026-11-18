import type { HybridObject } from 'react-native-nitro-modules'

export type Accuracy = 'high' | 'balanced' | 'low'
export type PermissionLevel = 'whenInUse' | 'always'
export type PermissionValue = 'granted' | 'denied' | 'undetermined'
export type SessionStatus = 'idle' | 'running'
export type SessionEvent = 'location' | 'upload' | 'stop' | 'error'

export interface NativeDestination {
  latitude: number
  longitude: number
  radiusMeters: number
}

export interface NativeSessionConfig {
  id: string
  expiresAt: number
  destination?: NativeDestination
  accuracy: Accuracy
  distanceFilterMeters: number
  uploadUrl: string
  uploadHeaders: Record<string, string>
  uploadExtrasJson: string
  uploadMinIntervalMs: number
  androidNotificationTitle: string
  androidNotificationText: string
  androidNotificationChannelName: string
}

export interface NativeLocation {
  latitude: number
  longitude: number
  accuracy: number
  altitude?: number
  speed?: number
  heading?: number
  timestamp: number
}

export interface NativePermissionStatus {
  foreground: PermissionValue
  background: PermissionValue
  precise: boolean
  notifications: PermissionValue
}

export interface NativeSessionState {
  status: SessionStatus
  sessionId?: string
  startedAt?: number
  expiresAt?: number
  lastLocation?: NativeLocation
  lastUploadAt?: number
  survivesProcessDeath?: boolean
  lastStopReason?: string
  lastStopAt?: number
  lastStopSessionId?: string
}

export interface LocationSession
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  getPermissionStatus(): Promise<NativePermissionStatus>
  requestPermission(level: PermissionLevel): Promise<NativePermissionStatus>
  getCurrentPosition(
    accuracy: Accuracy,
    timeoutMs: number,
    maxAgeMs: number
  ): Promise<NativeLocation>
  start(config: NativeSessionConfig): Promise<void>
  stop(): Promise<void>
  getState(): Promise<NativeSessionState>
  addListener(
    event: SessionEvent,
    callback: (payloadJson: string) => void
  ): number
  removeListener(id: number): void
}
