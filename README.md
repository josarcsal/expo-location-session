# expo-location-session

Background location sessions for React Native. A session sends the device position to your server from native code (Swift on iOS, Kotlin on Android). It keeps working when the app is in the background, when the screen is locked and, with the right permission, after the app has been killed. It stops itself on arrival at a destination or at an expiry time, even if JavaScript never runs.

Built with [Nitro Modules](https://nitro.margelo.com). Status: v0.1 beta. One session at a time, latest position only.

## Requirements

- React Native with the New Architecture, `react-native-nitro-modules` 0.37 or newer.
- iOS 15.1 or newer.
- Android minSdk 24 or newer, with Google Play services. Devices without Play services (for example Huawei) are not supported.
- Expo is optional. With Expo you need a development build; Expo Go does not include this native module.

## Install

```sh
yarn add expo-location-session react-native-nitro-modules
```

### Expo

Add the config plugin to `app.json` and run `npx expo prebuild`:

```json
{
  "expo": {
    "plugins": [
      [
        "expo-location-session",
        {
          "locationWhenInUseText": "Shares your location with the host during an active session.",
          "locationAlwaysText": "Keeps sharing your location during an active session when the app is in the background.",
          "androidNotificationIcon": "./assets/notification-icon.png"
        }
      ]
    ]
  }
}
```

| Option | Default | Effect |
| --- | --- | --- |
| `locationWhenInUseText` | existing value, else `Allow $(PRODUCT_NAME) to access your location` | iOS `NSLocationWhenInUseUsageDescription` |
| `locationAlwaysText` | existing value, else `Allow $(PRODUCT_NAME) to access your location in the background` | iOS `NSLocationAlwaysAndWhenInUseUsageDescription` |
| `enableBackgroundLocationPermission` | `true` | Adds `ACCESS_BACKGROUND_LOCATION` to the Android manifest. Set it to `false` if you do not want to file the Google Play background-location declaration; Android sessions then pause when the process dies (see [Survival](#survival)). |
| `androidNotificationIcon` | none | Path to a white-on-transparent PNG used as the small icon of the Android foreground-service notification. Without it the notification uses a system location icon. |

The plugin always adds `location` to iOS `UIBackgroundModes`, keeping any modes already there.

On Android the plugin also keeps the session, its headers, the upload queue and the debug log out of Auto Backup and device transfer (see [Backup](#backup)). When neither `android:fullBackupContent` nor `android:dataExtractionRules` is set, it points them at the library's rules. When the app already references its own rules files, it adds the library's excludes to those files, or prints a warning when it cannot.

The icon is copied to `res/drawable/expo_location_session_notification_icon.png` and looked up by name at runtime. If your app uses strict resource shrinking (`tools:shrinkMode="strict"`), keep it, for example with `tools:keep="@drawable/expo_location_session_notification_icon"` in a `res/raw/keep.xml`.

### Bare React Native

iOS `Info.plist`:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>…</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>…</string>
<key>UIBackgroundModes</key>
<array>
  <string>location</string>
</array>
```

`start` rejects with `INVALID_CONFIG` on iOS when `UIBackgroundModes` does not contain `location`. If a session was persisted by a build that had the mode and resumes in a build that does not (an app update), the library does not enable background updates, which would crash the app, and emits an `error` event with `CONFIG_MISSING_BACKGROUND_MODE`; the session keeps running but only updates while the app is in the foreground.

Android: the library manifest already declares fine and coarse location, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `WAKE_LOCK` and `SCHEDULE_EXACT_ALARM`, plus its service and receiver. To survive process death, add background location to your app manifest:

```xml
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

For a custom notification icon, add a drawable named `expo_location_session_notification_icon`.

To keep the session out of Auto Backup, add the library's rules to `<application>` (see [Backup](#backup)):

```xml
android:fullBackupContent="@xml/expo_location_session_backup_rules"
android:dataExtractionRules="@xml/expo_location_session_data_extraction_rules"
```

### iOS linking note

The library resumes a persisted session at app launch from an Objective-C `+load` method, without changes to your AppDelegate. When the pod is linked statically (the CocoaPods default), the app must be linked with `-ObjC`. CocoaPods adds it to the generated xcconfig; if you override `OTHER_LDFLAGS`, keep `$(inherited)`, or sessions will not resume after a relaunch.

## Permissions

`start` never asks for permissions. Call `requestPermission` first.

| | iOS | Android |
| --- | --- | --- |
| `requestPermission('whenInUse')` | When In Use prompt | Fine and coarse location; on Android 13+ also notifications |
| `requestPermission('always')` | When In Use prompt if undetermined, then the Always prompt. iOS shows the Always prompt once; after that the user has to change it in Settings. | As `whenInUse`, then background location. On Android 11+ this opens the app's location settings page. Only requested when the manifest declares `ACCESS_BACKGROUND_LOCATION`. |
| Required by `start` | When In Use or Always | Foreground location; on Android 13+ also notifications |
| Required to survive the app being killed | Always | Background location |

`PermissionStatus.notifications` is always `'granted'` on iOS (the library posts no notifications there). `precise` is iOS full accuracy or Android `ACCESS_FINE_LOCATION`.

## Usage

```ts
import { addListener, requestPermission, start, stop, getState } from 'expo-location-session';

await requestPermission('always');

const unsubscribe = addListener('stop', ({ reason, sessionId }) => {
  console.log(sessionId, reason);
});

await start({
  id: 'plan-123',
  expiresAt: Date.now() + 2 * 60 * 60 * 1000,
  destination: { latitude: 19.4326, longitude: -99.1332, radiusMeters: 150 },
  upload: {
    url: 'https://api.example.com/sessions/plan-123/location',
    headers: { Authorization: 'Bearer <short-lived token>' },
    extras: { userId: 42 },
  },
  android: {
    notification: { title: 'Sharing your location', text: 'Until you arrive', channelName: 'Location sharing' },
  },
});

const state = await getState();
await stop();
unsubscribe();
```

## API

```ts
getPermissionStatus(): Promise<PermissionStatus>
requestPermission(level: 'whenInUse' | 'always'): Promise<PermissionStatus>
getCurrentPosition(opts?: { accuracy?: Accuracy; timeoutMs?: number; maxAgeMs?: number }): Promise<Location>
start(config: SessionConfig): Promise<void>
stop(): Promise<void>
getState(): Promise<SessionState>
addListener(event, callback): () => void
configureDebug(options: DebugOptions): Promise<void>
getDebugLog(opts?: { limit?: number }): Promise<DebugLogLine[]>
clearDebugLog(): Promise<void>
flushDebugLog(): Promise<{ sent: number }>
getDebugInstallId(): Promise<string>
```

The last five are described in [Debug logging](#debug-logging).

### Types

```ts
type PermissionValue = 'granted' | 'denied' | 'undetermined';
type PermissionStatus = {
  foreground: PermissionValue;
  background: PermissionValue;
  precise: boolean;
  notifications: PermissionValue;
};

type Accuracy = 'high' | 'balanced' | 'low';

type Location = {
  latitude: number;
  longitude: number;
  accuracy: number; // meters
  altitude?: number;
  speed?: number; // m/s
  heading?: number; // degrees
  timestamp: number; // epoch ms of the fix
};

type SessionConfig = {
  id: string; // non-empty, sent in every upload
  expiresAt: number; // epoch ms, hard stop
  destination?: { latitude: number; longitude: number; radiusMeters: number };
  accuracy?: Accuracy; // default 'balanced'
  distanceFilterMeters?: number; // default 50
  upload: {
    url: string; // https only
    headers?: Record<string, string>;
    extras?: Record<string, string | number | boolean>;
    minIntervalMs?: number; // default 15000
  };
  android: { notification: { title: string; text: string; channelName: string } };
};

type StopReason = 'stopped' | 'expired' | 'arrived' | 'unauthorized' | 'rejected' | 'permissionRevoked';

type SessionState =
  | { status: 'idle'; lastStop?: { reason: StopReason; at: number; sessionId: string } }
  | {
      status: 'running';
      sessionId: string;
      startedAt: number;
      expiresAt: number;
      lastLocation?: Location;
      lastUploadAt?: number;
      survivesProcessDeath: boolean; // iOS: Always granted; Android: background location granted
    };
```

`accuracy` maps to `kCLLocationAccuracyBest` / `kCLLocationAccuracyHundredMeters` / `kCLLocationAccuracyKilometer` on iOS and to `PRIORITY_HIGH_ACCURACY` / `PRIORITY_BALANCED_POWER_ACCURACY` / `PRIORITY_LOW_POWER` on Android. On Android the session requests updates every 10 s, filtered by `distanceFilterMeters`. On iOS a filter of `0` means no filter.

`getCurrentPosition` defaults: `accuracy: 'balanced'`, `timeoutMs: 15000`, `maxAgeMs: 0` (no cached fix).

`getState` reads the native session, so it reflects a session that was resumed without JS. `lastStop` is kept after a stop until the next one.

`stop()` stops the running session with reason `stopped` and resolves; it does nothing when no session is running.

### Errors

Rejected promises carry a `LocationSessionError` with `code` and `message`.

| Code | When |
| --- | --- |
| `NOT_FOREGROUND` | `start` called while the app is not in the foreground |
| `PERMISSION_DENIED` | `start` or `getCurrentPosition` without location permission; on iOS also `getCurrentPosition` with location services off (iOS reports that as denied) |
| `ALREADY_RUNNING` | `start` while a session is running; call `stop` first |
| `INVALID_CONFIG` | empty `id`, non-https or host-less `url`, `radiusMeters <= 0`, negative `distanceFilterMeters` or `minIntervalMs`, non-finite numbers, invalid destination coordinates, an `accuracy` other than `high`, `balanced` or `low`; on iOS also a missing `UIBackgroundModes` `location` |
| `NOTIFICATION_PERMISSION_DENIED` | Android 13+: `start` without notification permission |
| `EXPIRED` | `expiresAt` is not in the future |
| `INTERNAL` | the session could not be persisted or started |
| `LOCATION_TIMEOUT` | `getCurrentPosition` got no fix within `timeoutMs` |
| `LOCATION_UNAVAILABLE` | `getCurrentPosition` failed; on Android also when location services are off |

### Events

```ts
addListener('location', (l: Location) => {});
addListener('upload', (r: { ok: boolean; status?: number; at: number }) => {});
addListener('stop', (s: { reason: StopReason; sessionId: string }) => {});
addListener('error', (e: { code: string; message: string }) => {});
addListener('log', (line: DebugLogLine) => {});
```

`log` events are emitted only while debug logging is on. `addListener` returns an unsubscribe function. Events reach JS only while the JS runtime is alive; the native session does not depend on them. `upload.status` is missing when the request failed without a response (timeout, no network).

Error event codes:

- `UPLOAD_DROPPED`: the server answered a 4xx other than 401, 403 or 410 (or, on Android, the body could not be encoded). That location is dropped and the session keeps running.
- `CONFIG_MISSING_BACKGROUND_MODE` (iOS): a session resumed, or was upgraded to Always, in a build whose `UIBackgroundModes` has no `location`. Background updates are not enabled; the session keeps running and only updates in the foreground. Sticky, see below.
- `ACCURACY_REDUCED` (iOS): precise location is off for this app, either when the session starts or resumes, or because the user turned it off while it runs. Emitted once per change and sticky (see below); the session keeps running. Fixes are then approximate (often 1–5 km), so arrival cannot trigger (it needs an accuracy within `max(radiusMeters, 100)` m) and region monitoring does not relaunch the app; the session runs to `expiresAt`. Check `getPermissionStatus().precise` before `start` to warn the user earlier. iOS only: on Android, switching an app to approximate location revokes `ACCESS_FINE_LOCATION`, which kills the process, so there is no running session to notify; `precise` reports it.
- `FGS_PROMOTION_FAILED` (Android): Android refused to restart the foreground service for 10 minutes. The session then stops with reason `stopped`. See [Android](#android).

`CONFIG_MISSING_BACKGROUND_MODE` and `ACCURACY_REDUCED` are sticky on iOS, because they are often raised during a resume at launch, before JS has added a listener. The latest event of each is kept while its condition holds and the session runs, and is emitted again to each `error` listener when it is added. It is dropped when the condition clears (precise location turned back on) or the session stops.

## Debug logging

Debug logging writes a line for each lifecycle, permission, location, upload and stop event to a file on the device, and can send those lines to your server. It is meant for testing on real devices, where the process may be killed and relaunched while nobody is watching a console. It is off by default: with it off no file is opened and no request is made.

```ts
await configureDebug({
  enabled: true,
  url: 'https://logs.example.com/logs',
  headers: { Authorization: 'Bearer <token>' },
  flushIntervalMs: 60000,
  maxFileBytes: 1_000_000,
});

const lines = await getDebugLog({ limit: 200 });
const { sent } = await flushDebugLog();
const installId = await getDebugInstallId();
await clearDebugLog();
await configureDebug({ enabled: false });
```

```ts
type DebugOptions = {
  enabled: boolean;
  url?: string;
  headers?: Record<string, string>;
  flushIntervalMs?: number;
  maxFileBytes?: number;
};
```

| Option | Default | Notes |
| --- | --- | --- |
| `url` | none | https only. Without it lines are written to the file but never sent. |
| `headers` | none | Sent with every batch, for example an `Authorization` header. |
| `flushIntervalMs` | `60000` | Minimum `10000`. |
| `maxFileBytes` | `1000000` | `100000` to `10000000`. |

Invalid options reject with `INVALID_CONFIG: <reason>`. `configureDebug` replaces the previous options entirely and the options are persisted, so debug logging stays on after the app is killed and relaunched, including a relaunch by the OS for a location event. `enabled: false` stops writing and sending but keeps the files; `clearDebugLog` deletes them. `getDebugLog` returns the lines in order, newest last; `limit` keeps only the newest ones. `flushDebugLog` sends what is pending now and resolves with the number of lines the server accepted. `getDebugInstallId` resolves with the `install.id` that batches carry (see [Shipping](#shipping)), creating it if it does not exist yet, so your backend can link an install to a user. Before the first unlock after a reboot, when the id cannot be read yet, it rejects with `INTERNAL: Debug storage is not available` on both platforms instead of creating a new one.

### Line format

One JSON object per line:

```ts
type DebugLogLine = {
  seq: number;
  t: number;
  pid: number;
  level: 'debug' | 'info' | 'warn' | 'error';
  tag: 'lifecycle' | 'permission' | 'location' | 'upload' | 'stop' | 'service' | 'gate' | 'region' | 'store' | 'debug';
  msg: string;
  sessionId?: string;
  data?: Record<string, unknown>;
};
```

`seq` increases by one for every line and survives relaunches. `t` is epoch milliseconds. `pid` is the process id, so a new `pid` marks a relaunch; the first line of a process has tag `lifecycle` and records how it was launched. Coordinates appear only in these lines.

The lines are kept in two files, `debug-0.jsonl` and `debug-1.jsonl`. When the current file passes `maxFileBytes` the logger switches to the other one and clears it, so the log holds between `maxFileBytes` and `2 × maxFileBytes`. On iOS the files are in `Application Support/expo-location-session/`, excluded from backup, with file protection `completeUntilFirstUserAuthentication`. Before the first unlock after a reboot the options cannot be read. The logger keeps up to 200 lines logged in that window in memory and writes them once the options load, which is retried when protected data becomes available. On Android the files are in `filesDir/expo-location-session/`. The app is not direct-boot aware, so it does not run before the first unlock after a reboot; if storage cannot be read the logger keeps up to 200 lines in memory and does not crash.

If a write fails partway (for example a full disk), the file is truncated back to the end of the last complete line, and the line is kept in memory (up to 1000 lines) until a write succeeds.

### Shipping

Each batch is a `POST` to `url` with `Content-Type: application/json`, your headers, and this body:

```json
{
  "install": {
    "id": "6f1c0a52-0d0e-4a43-9d44-7a1d2f6a9b10",
    "platform": "ios",
    "model": "iPhone15,2",
    "os": "iOS 17.5.0",
    "appVersion": "1.4.0 (212)",
    "libVersion": "0.1.1"
  },
  "lines": []
}
```

On Android `platform` is `"android"`, `model` is the manufacturer and model (`"Google Pixel 8"`), `os` is `"Android 15 (API 35)"` and `appVersion` is `versionName (versionCode)`.

`install.id` is a UUID generated once and kept across launches. A batch holds at most 500 lines, in `seq` order, and the request times out after 20 seconds. A 2xx response advances a persisted cursor; any other result (including a timeout) leaves it, and the same lines are sent again on the next trigger, so make the endpoint idempotent on `install.id` and `seq`. Changing the URL does not reset the cursor. If a file rotation removed lines that were never sent, the next batch starts with `{ tag: 'debug', msg: 'lines lost', data: { from, to } }` instead of silently skipping sequence numbers. That line reuses `seq` `to`, the last lost one, and counts towards the `sent` that `flushDebugLog` returns.

A batch is sent when the process starts, every `flushIntervalMs` while the process is alive, after each location upload completes, when a session is stopping, and on `flushDebugLog()`. Shipping never blocks the location pipeline: it runs on its own queue with its own `URLSession` on iOS and its own executor with `HttpURLConnection` on Android, one request at a time, and a slow or failing debug server does not delay or reorder location uploads. It never keeps the process awake by itself: on iOS it does not start a background task, and on Android, when the location service is not in the foreground, a batch sent while the background-start gate holds a delivery open gets only the time left in that window (its timeout is shortened, and the window is not extended). Otherwise it only runs while the process is awake for another reason.

### Privacy

- Coordinates are written only to the debug file and sent only to the debug server. `os_log` on iOS and logcat on Android carry no coordinates.
- On iOS the debug headers are stored in the Keychain with `AfterFirstUnlockThisDeviceOnly`; the other options, `seq`, the cursor and `install.id` are stored in `UserDefaults`. On Android all of them, headers included, are stored in the app's private `SharedPreferences`, like the session headers.
- The `debug configured` line records the header names, not their values, and the URL without its query string.
- Debug logging is for test builds. Turn it off, or do not ship the code that turns it on, in production.

### Cost

With debug logging on, the app makes a network request every `flushIntervalMs` while the process is alive, plus one after each location upload and one when a session stops. The file can grow to `2 × maxFileBytes`. Use a longer `flushIntervalMs` when measuring battery.

## Upload contract

Each accepted location is sent as:

```
POST <upload.url>
Content-Type: application/json
<upload.headers>
```

```json
{
  "sessionId": "plan-123",
  "location": {
    "latitude": 19.4326,
    "longitude": -99.1332,
    "accuracy": 12.3,
    "altitude": 2240.1,
    "speed": 8.2,
    "heading": 270,
    "timestamp": 1763480000000
  },
  "battery": { "level": 0.72, "charging": false },
  "extras": { "userId": 42 }
}
```

`altitude`, `speed` and `heading` are left out when the platform does not report them. `battery` is left out when the level is unknown (for example on the iOS simulator). `extras` is always present, `{}` when not configured. Request timeout: on iOS 20 s per request; on Android a 20 s connect timeout and a 20 s read timeout, with a watchdog that cancels the request 20 s after it was opened.

Only the latest position matters. There is one pending slot: a newer location replaces an unsent one. Locations with a timestamp before `start` are ignored.

| Response | Effect |
| --- | --- |
| `2xx` | Success. The pending location is cleared and `lastUploadAt` is recorded. |
| `401`, `403` | Stop with `unauthorized`. There is no token refresh in v0.1; use a short-lived, session-scoped token. |
| `410` | Stop with `rejected` (the server ended the session). |
| Other `4xx` | The location is dropped, an `error` event with `UPLOAD_DROPPED` is emitted, the session keeps running. |
| `3xx` | Redirects are never followed. Treated like a `5xx`: the location stays pending and is retried with backoff. Point `upload.url` at the final address. |
| `5xx`, timeout, no network | The location stays pending. Retries back off 5 s, 10 s, 20 s, 40 s, then every 60 s while the process is alive, and a new location triggers a new attempt. |

Throttle: after a success, locations that arrive within `minIntervalMs` stay pending and are sent when the interval has passed (by a timer while the process is alive, or with the next location).

Final uploads bypass the throttle:

- Arrival: the arrival location is sent and retried with the same backoff for at most 30 seconds (and never past `expiresAt`). Then the session stops with `arrived` (or `unauthorized` / `rejected` if the server answered 401/403/410). If it was not delivered in that time, for example with no network, it is dropped: the session does not stay running to deliver it.
- Expiry: any pending location gets a single attempt, also capped at 30 seconds, then the session stops with `expired`.

## Stop policy

The session stops itself when:

- `expired`: `now >= expiresAt`. Checked on every location, on resume and by a timer while the process is alive. Android also sets an alarm at `expiresAt`: an exact `setExactAndAllowWhileIdle` alarm when the app can schedule exact alarms (below Android 12, or "Alarms & reminders" granted), which fires on time even in Doze; otherwise an inexact `setAndAllowWhileIdle` alarm, which can fire minutes late. The alarm is re-armed when that permission changes: granting it (Android 12+ sends `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, received even when the process is not running) re-arms it as exact while a session is active; revoking it kills the app's process and deletes its exact alarms, and the next process start (a location delivery, a service restart or opening the app) resumes the session and re-arms it as inexact. Locations taken at or after `expiresAt` are never uploaded.
- `arrived`: a location is within `radiusMeters` of `destination` and its accuracy is at most `max(radiusMeters, 100)` meters.
- `unauthorized` / `rejected`: see the upload contract.
- `permissionRevoked`: location permission was removed. iOS detects it immediately (setting it to Never). Android kills the app on revocation, so it is detected at the next wake or app open.
- `stopped`: `stop()` was called, or Android could not restart the foreground service for 10 minutes.

Stopping removes location updates, regions, geofences, alarms and the Android notification, wipes the persisted session and pending location, and keeps only `lastStop`.

## Survival

| | App in background, screen locked | App killed (swiped away / OS kill) | Then the user opens the app |
| --- | --- | --- | --- |
| iOS, Always | Keeps running, blue location indicator | Relaunched in the background on movement; the session resumes natively | Keeps running |
| iOS, When In Use | Keeps running, blue location indicator | Stops tracking; nothing is sent | Resumes if not expired |
| Android, with background location | Keeps running with the notification | Swipe from recents: the service keeps running. Process killed: the next location delivery relaunches the process and the service comes back | Keeps running |
| Android, without background location | Keeps running while the process is alive | Swipe from recents: keeps running while the process lives. Process killed: pauses, no updates and no notification | Resumes if not expired |

`getState().survivesProcessDeath` tells the app which row applies.

### iOS

- One `CLLocationManager` runs standard updates (`allowsBackgroundLocationUpdates`, no automatic pause, background indicator shown) together with significant-change monitoring.
- With Always and a destination, the destination is also monitored as a region, so arrival can relaunch the app.
- When the app is about to terminate and Always is granted, it registers an exit-only region around the last fix (or the manager's last known location), so moving away relaunches the app sooner than a significant change would (~500 m). The radius is 150 m, plus the fix's accuracy when that accuracy is 150 m or worse, plus speed × seconds since the fix, capped at 1000 m (and at iOS's maximum region size). A poor or stale fix therefore still leaves the phone inside the region, where the exit can fire.
- At launch the bootstrap resumes the persisted session without JS: it stops it as `expired` or `permissionRevoked`, or restarts updates and sends the pending location. When no session is stored, it releases leftover registrations once per process (not on each JS reload), so a registration orphaned by a crash cannot keep relaunching the app: it removes `els.*` regions and stops significant-change monitoring. The library only stops significant-change monitoring it started: it records a marker in `UserDefaults` each time it starts it, and stops it (at that cleanup or when a session stops) only while the marker is set, then clears it. iOS shares significant-change monitoring across an app's location managers, so an app that also uses it can still find it stopped after a session of this library ends, and should start it again afterwards.
- The session config (headers included) is stored in the Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`; the pending location and `lastStop` are in `UserDefaults`. After a reboot nothing resumes before the first unlock; the engine waits and never overwrites the stored session. Because of `ThisDeviceOnly`, a session is not restored on a device restored from a backup or migrated to a new phone.
- Uploads run in a `beginBackgroundTask` so a request can finish after the app is suspended. If that background time runs out first while a running session's background location updates keep the process alive, the background task just ends and the request carries on. Otherwise the request is cancelled and retried with backoff; the location stays pending.
- v0.1 does not use iOS 17's `CLBackgroundActivitySession`: the library supports iOS 15.1+, and `allowsBackgroundLocationUpdates` with the `location` background mode already keeps updates running on every supported version.

### Android

- `start` launches a foreground service of type `location` (`stopWithTask="false"`, `START_STICKY`) with a low-importance notification built from `android.notification`.
- Location updates are requested from `FusedLocationProviderClient` with a `PendingIntent` to a manifest receiver (the launch gate). Play services keeps the request registered outside the process, so a delivery recreates the process. The request is issued again, with the same `PendingIntent` and parameters, every time the service receives a start command, so a registration Play services dropped is restored; it is not repeated for the start that comes right after the session (re)activated, which has just requested it.
- On each delivery the launch gate loads the session (expired or invalid: removes updates and wipes), hands the location to the engine, and starts the foreground service again if it is not running.
- If Android refuses to start the service from the background, the gate falls back to:
  1. an exact alarm that fires immediately, only if the user granted "Alarms & reminders" (`SCHEDULE_EXACT_ALARM`, off by default on Android 14+). Apps can send users to that setting (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`); the library does not.
  2. otherwise a recovery geofence around the last fix (radius: accuracy + 200 m, enter or exit, expires after 2 minutes).
  A second refusal waits for the next delivery. Until the service is back, locations are still handled and uploaded, without a notification. If no restart succeeds for 10 minutes after the first refusal, the session emits `FGS_PROMOTION_FAILED` and stops with `stopped`.
- After an app update (`MY_PACKAGE_REPLACED`) the gate resumes a persisted session the same way as a delivery: updates, expiry alarm and foreground service come back without the app being opened (an expired session stops with `expired`; without background location it stays paused until the app is opened).
- The session is stored in app-private `SharedPreferences`, headers included, unencrypted.

### Backup

The session (headers included) is in the `SharedPreferences` file `expo_location_session`, the upload and refresh headers per session in `expo_location_session_targets`, the debug options in `expo_location_session_debug`, and the upload queue (`queue.sqlite`) and debug log in `filesDir/expo-location-session/`. The library ships two rules files that exclude all four: `@xml/expo_location_session_backup_rules` (`android:fullBackupContent`, Android 11 and lower) and `@xml/expo_location_session_data_extraction_rules` (`android:dataExtractionRules`, Android 12+, cloud backup and device transfer). A library manifest cannot set these `<application>` attributes without clashing with the app's, so the Expo plugin sets them, or a bare app adds them.

When an attribute already points at another `@xml/…` file, the Expo plugin leaves the attribute alone and adds the excludes below to that file in `android/app/src/main/res/xml/`, skipping any already there. In a `<full-backup-content>` file it adds them at the top level; in a `<data-extraction-rules>` file it adds them to `<cloud-backup>` and `<device-transfer>`, creating a missing section (a section with only excludes still backs up everything else). This runs as a finalized mod, after the other plugins' manifest and dangerous mods, so rules files that another plugin writes in its own dangerous mod are found. If the file does not exist in the app's `res/xml` at that point, the plugin prints a warning and you add the excludes yourself. That is the case for `expo-secure-store`, whose rules files ship inside its own Android library: turn its `configureAndroidBackup` option off and let this plugin set its rules, or point both attributes at rules files of your own (written by a plugin of yours) that hold both libraries' excludes. Plugin order: list `expo-location-session` before plugins that set these attributes only when they are unset, or that write their rules files in a finalized mod, so their mods run before this one (Expo runs the mods of later plugins first).

In a bare app with its own rules files, add these excludes to them yourself (inside `<cloud-backup>` and `<device-transfer>` for the data extraction rules):

```xml
<exclude domain="sharedpref" path="expo_location_session.xml" />
<exclude domain="sharedpref" path="expo_location_session_debug.xml" />
<exclude domain="sharedpref" path="expo_location_session_targets.xml" />
<exclude domain="file" path="expo-location-session/" />
```

## Known limits

- iOS cannot wake an app at a set time. If the app was killed and the phone does not move, region and significant-change monitoring stay registered past `expiresAt` until the next wake. At that wake the session stops as `expired` and is wiped without sending anything.
- iOS When In Use: no relaunch after the app is killed.
- OEM battery managers (Samsung, Xiaomi and others) can kill the foreground service or block relaunches. v0.1 has no OEM helpers.
- A force stop (Settings › Force stop) cancels Play services requests and alarms. The session resumes when the user opens the app.
- Android has no boot resume in v0.1: after a reboot the session resumes when the app is opened.
- Android requires Google Play services.
- One session at a time; no history, batching or offline queue beyond the single pending location.
- No web support.

## Store review notes

### Apple

- Apps using the `location` background mode are checked against App Review Guidelines 2.5.4 (background modes only for their intended purpose) and 5.1.5 (location only when relevant to the feature).
- Write the `NSLocationAlwaysAndWhenInUseUsageDescription` text around the feature: why the app needs the location while closed, and that it stops on arrival or expiry.
- In the review notes, explain how to start a session, that the blue indicator shows while it runs, and when it stops. A short screen recording helps.
- Declare location in the App Store privacy details.

### Google Play

- Background location (`ACCESS_BACKGROUND_LOCATION`): complete the location permissions declaration in Play Console (App content › Sensitive app permissions), with a short video showing the feature. Show a prominent in-app disclosure before asking for the permission. If you do not want this, set `enableBackgroundLocationPermission: false`.
- Foreground service (`FOREGROUND_SERVICE_LOCATION`): apps targeting Android 14+ must declare the foreground service type in Play Console (App content › Foreground service permissions): describe the location-sharing task, what happens if it is interrupted, and link a video.
- Declare location collection in the Data safety form.

## Example app

`example/` is an Expo development build with a screen to request permissions, pick a destination, start and stop a session against a configurable URL, and see the state and event log. The Debug log section has a switch that calls `configureDebug` (default URL `https://localhost:8443/logs`, 10 s flush interval, 100 kB files), a field for the debug URL, a `Flush` button and a count of `log` events.

```sh
yarn install
yarn build:plugin
cd example
yarn install
yarn ios       # or: yarn android
```

Run the local echo server in another terminal. It prints every request body and answers `204`, or the status in `?status=` (for example `?status=401`); a request to `/fail?n=3` makes the next 3 uploads answer 503. `POST /logs` accepts debug batches: it prints one summary line per batch (install id, line count, `seq` range, `Authorization` header), answers `204` or the status in `?status=`, and waits `?delayMs=` before answering. Set `LOG_LINES=1` to print every line.

```sh
TLS_CERT=cert.pem TLS_KEY=key.pem node example/scripts/echo-server.mjs   # https on :8443
```

Uploads must be https. Use a certificate from a local CA (for example `mkcert localhost`):

- iOS simulator: trust the CA with `xcrun simctl keychain <udid> add-root-cert <ca.pem>`.
- Android: set `ELS_DEV_CA=<path to CA pem>` when running `npx expo prebuild`. The example's `with-dev-ca` plugin then trusts that CA in debug builds only. Forward the port with `adb reverse tcp:8443 tcp:8443`.

Drive the example with [Maestro](https://maestro.mobile.dev) flows (`tapOn`, `inputText`); the main controls have `testID`s. The real-device checklist is in [docs/QA.md](docs/QA.md).

## Development

```sh
yarn install          # Corepack + Yarn 4, Node from .nvmrc
yarn tsc --noEmit
yarn lint             # ESLint + Prettier check; `yarn format` fixes
yarn nitrogen         # regenerate nitrogen/generated after changing src/specs
cd ios/Core && swift test
cd example/android && ./gradlew :expo-location-session:testDebugUnitTest   # after `npx expo prebuild` in example
```

CI (`.github/workflows/ci.yml`) runs these on every push to `main` and every pull request, and fails if `nitrogen/generated` is out of date.

## Roadmap

| Version | Scope |
| --- | --- |
| v0.1 | Permissions, one-shot position, one running session, background survival, relaunch after kill on both platforms (Android: background-start gate with exact-alarm and recovery-geofence fallbacks), native upload of the latest position with retry, auto-stop on arrival / expiry, Expo config plugin, example app |
| v0.2 | Full persistence and sync: SQLite queue, batching, body templates, native token refresh, upload on connectivity regained |
| v0.3 | Motion / stationary detection (CoreMotion, Activity Recognition, stop timeout, stationary geofence) for long sessions |
| v0.4 | Geofencing: circular + polygon, enter/exit/dwell, beyond OS region limits |
| v0.5 | Scheduling windows, heartbeat, boot resume, native logs, power-save / OEM settings helpers |

## License

MIT
