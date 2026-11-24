# expo-location-session

Background location sessions for React Native. A session sends the device position to your server from native code (Swift on iOS, Kotlin on Android). It keeps working when the app is in the background, when the screen is locked and, with the right permission, after the app has been killed. It stops itself on arrival at a destination or at an expiry time, even if JavaScript never runs.

Built with [Nitro Modules](https://nitro.margelo.com). Status: v0.2 beta. One session at a time. By default only the latest position is kept and sent; opt in to a persistent queue that keeps every location, batches them, shapes the request body with a template, refreshes credentials natively and delivers after the session ends (see [Upload contract](#upload-contract)).

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
| `backgroundSync` | `false` | Lets the OS deliver queued locations of ended sessions while the app is closed (see [Background delivery](#background-delivery)). Android: adds the `expo-location-session.backgroundSync` meta-data. iOS: adds `processing` to `UIBackgroundModes` and `<bundle id>.els.queue-sync` to `BGTaskSchedulerPermittedIdentifiers`. |

The plugin always adds `location` to iOS `UIBackgroundModes`, keeping any modes already there (and `processing` with `backgroundSync`).


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

For [background delivery](#background-delivery) in a bare app, on iOS add `processing` to `UIBackgroundModes` and the identifier to the permitted list, replacing `<bundle id>` with your bundle identifier:

```xml
<key>UIBackgroundModes</key>
<array>
  <string>location</string>
  <string>processing</string>
</array>
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string><bundle id>.els.queue-sync</string>
</array>
```

and on Android add this inside `<application>`:

```xml
<meta-data android:name="expo-location-session.backgroundSync" android:value="true" />
```

The library depends on `androidx.work`. WorkManager's default initializer runs at app start in every app that includes the library, whether or not `backgroundSync` is on; the library itself never calls WorkManager unless the meta-data above is set.

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
getQueueStatus(): Promise<QueueStatus>
syncQueue(): Promise<{ sent: number }>
clearQueue(): Promise<void>
```

The five debug functions are described in [Debug logging](#debug-logging), the last three in [Queue functions](#queue-functions).

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
    queue?: {
      mode: 'latest' | 'all'; // default 'latest'
      maxRecords?: number; // default 10000, 1 to 100000
      maxAgeMs?: number; // default 86400000, 60000 to 2592000000
    };
    batch?: { maxSize: number; rootProperty?: string }; // maxSize 1 to 500, rootProperty default 'locations'; mode 'all' only
    body?: { template: Record<string, unknown> }; // up to 8 KB, see Templates
    auth?: { refresh: { url: string; headers?: Record<string, string>; body?: Record<string, unknown> } }; // url https only
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

type QueueStatus = {
  pending: number; // rows not yet delivered, in-flight rows included
  oldestAt?: number; // epoch ms of the oldest pending fix
  sessions: number; // distinct sessions with pending rows
};

type UploadResult = { ok: boolean; status?: number; at: number; sessionId?: string; count?: number };
type ErrorEvent = { code: string; message: string; sessionId?: string };
```

`accuracy` maps to `kCLLocationAccuracyBest` / `kCLLocationAccuracyHundredMeters` / `kCLLocationAccuracyKilometer` on iOS and to `PRIORITY_HIGH_ACCURACY` / `PRIORITY_BALANCED_POWER_ACCURACY` / `PRIORITY_LOW_POWER` on Android. On Android the session requests updates every 10 s, filtered by `distanceFilterMeters`. On iOS a filter of `0` means no filter.

`getCurrentPosition` defaults: `accuracy: 'balanced'`, `timeoutMs: 15000`, `maxAgeMs: 0` (no cached fix).

`getState` reads the native session, so it reflects a session that was resumed without JS. `lastStop` is kept after a stop until the next one.

`stop()` stops the running session with reason `stopped` and resolves; it does nothing when no session is running.

### Queue functions

```ts
const { pending, oldestAt, sessions } = await getQueueStatus();
const { sent } = await syncQueue();
await clearQueue();
```

- `getQueueStatus()` counts every row that is not delivered yet, including rows that are in flight right now. Rows the server already accepted but that could not be deleted yet (see [Storage failure](#storage-failure)) are not counted, and neither are locations kept in memory. In `latest` mode it is at most one row per session. It rejects with `INTERNAL` when the queue storage is not available.
- `syncQueue()` delivers the rows of ended sessions now and resolves with `{ sent }`, the number of rows delivered by that pass. It is not "the queue is empty": it stops at the first failure (a retry is then scheduled with backoff), and it can resolve early, possibly with `0`, when a running session is blocked on its own backlog or is sending it. Read `getQueueStatus()` for what remains. Call it, for example, when the app opens.
- `clearQueue()` cancels any delivery in progress and deletes every queued row, the running session's included, together with the stored credentials of ended sessions. The running session keeps running.

### Errors

Rejected promises carry a `LocationSessionError` with `code` and `message`.

| Code | When |
| --- | --- |
| `NOT_FOREGROUND` | `start` called while the app is not in the foreground |
| `PERMISSION_DENIED` | `start` or `getCurrentPosition` without location permission; on iOS also `getCurrentPosition` with location services off (iOS reports that as denied) |
| `ALREADY_RUNNING` | `start` while a session is running; call `stop` first |
| `INVALID_CONFIG` | empty `id`, non-https or host-less `url`, `radiusMeters <= 0`, negative `distanceFilterMeters` or `minIntervalMs`, non-finite numbers, invalid destination coordinates, an `accuracy` other than `high`, `balanced` or `low`; on iOS also a missing `UIBackgroundModes` `location`. Queue options, with the reason after the code: `batch requires queue.mode 'all'`, `batch.maxSize must be between 1 and 500`, `batch.rootProperty must not be empty`, `queue.maxRecords must be between 1 and 100000`, `queue.maxAgeMs must be between 60000 and 2592000000`, `auth.refresh.url must be an https URL`, `auth.refresh.body must be a JSON object`, `body.template is required` for a `body` without `template`, and `template: <reason>` for a body template that is not a JSON object, is larger than 8 KB, has a malformed `{{` placeholder, an unknown field, or an `extras.` key that is not in `upload.extras` |
| `NOTIFICATION_PERMISSION_DENIED` | Android 13+: `start` without notification permission |
| `EXPIRED` | `expiresAt` is not in the future |
| `INTERNAL` | the session could not be persisted or started |
| `LOCATION_TIMEOUT` | `getCurrentPosition` got no fix within `timeoutMs` |
| `LOCATION_UNAVAILABLE` | `getCurrentPosition` failed; on Android also when location services are off |

### Events

```ts
addListener('location', (l: Location) => {});
addListener('upload', (r: { ok: boolean; status?: number; at: number; sessionId?: string; count?: number }) => {});
addListener('stop', (s: { reason: StopReason; sessionId: string }) => {});
addListener('error', (e: { code: string; message: string; sessionId?: string }) => {});
addListener('log', (line: DebugLogLine) => {});
```

`log` events are emitted only while debug logging is on. `addListener` returns an unsubscribe function. Events reach JS only while the JS runtime is alive; the native session does not depend on them. `upload.status` is missing when the request failed without a response (timeout, no network). `upload.sessionId` is the session the request belonged to, which can be an ended session being delivered after its stop. `upload.count` is the number of locations in the request and is present only in queue mode `all`. Error events carry `sessionId` when they belong to a session, except the sticky ones below.

Error event codes:

- `UPLOAD_DROPPED`: the server answered a 4xx other than 401, 403 or 410 (or the location could not be encoded). That location, or that whole batch, is dropped and the session keeps running. It is also emitted for ended sessions (see the status table) and when a queued row or the stored settings of a queued session are unreadable.
- `QUEUE_TRIMMED` (queue mode `all`): the queue reached `maxRecords` and the oldest pending rows were dropped to make room. The message says how many. It is also emitted, once per outage, when the database cannot be written and the 500-location memory buffer starts dropping its oldest locations. The session keeps running.
- `AUTH_REFRESH_FAILED`: a token refresh did not succeed (see [Token refresh](#token-refresh)). Whether the session stops depends on the failure.
- `QUEUE_UNAVAILABLE`: the queue database (`queue.sqlite`) cannot be opened, or a location cannot be written to it (for example a full disk), while a session runs. Emitted once per outage; the session keeps running. In `latest` mode the newest location is still uploaded from memory; in `all` mode up to 500 locations are kept in memory and written in order once the database accepts writes again (see [Storage failure](#storage-failure)). The library tries to open the database again on the next location, at most every 5 seconds. On iOS a file that is not a valid database is deleted and recreated once per process before this is reported, and nothing is reported before the first unlock after a reboot. On Android a corrupt file is recreated by Android's SQLite. It is sticky on both platforms (see below); on Android it also carries `sessionId`.
- `CONFIG_MISSING_BACKGROUND_MODE` (iOS): a session resumed, or was upgraded to Always, in a build whose `UIBackgroundModes` has no `location`. Background updates are not enabled; the session keeps running and only updates in the foreground. Sticky, see below.
- `ACCURACY_REDUCED` (iOS): precise location is off for this app, either when the session starts or resumes, or because the user turned it off while it runs. Emitted once per change and sticky (see below); the session keeps running. Fixes are then approximate (often 1–5 km), so arrival cannot trigger (it needs an accuracy within `max(radiusMeters, 100)` m) and region monitoring does not relaunch the app; the session runs to `expiresAt`. Check `getPermissionStatus().precise` before `start` to warn the user earlier. iOS only: on Android, switching an app to approximate location revokes `ACCESS_FINE_LOCATION`, which kills the process, so there is no running session to notify; `precise` reports it.
- `FGS_PROMOTION_FAILED` (Android): Android refused to restart the foreground service for 10 minutes. The session then stops with reason `stopped`. See [Android](#android).

`CONFIG_MISSING_BACKGROUND_MODE`, `ACCURACY_REDUCED` and `QUEUE_UNAVAILABLE` are sticky on iOS, and `QUEUE_UNAVAILABLE` on Android, because they are often raised during a resume at launch, before JS has added a listener. On iOS sticky errors carry no `sessionId`. The latest event of each is kept while its condition holds and the session runs, and is emitted again to each `error` listener when it is added, unless that listener already received it. It is dropped when the condition clears (precise location turned back on, the queue database accepts writes again) or the session stops.

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
  tag:
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
  msg: string;
  sessionId?: string;
  data?: Record<string, unknown>;
};
```

`queue`, `auth` and `sync` are new in 0.2. Adding them widens the `DebugLogTag` type, so an exhaustive `switch` over it in your code needs the three new cases to keep compiling.

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
    "libVersion": "0.2.0-beta.1"
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

Uploads run in one of two queue modes, chosen by `upload.queue.mode`. `latest` is the default and behaves as in v0.1. `all` is opt-in.

### Default (latest)

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
| `401`, `403` | Stop with `unauthorized`. Without `upload.auth.refresh` there is no token refresh; use a short-lived, session-scoped token, or configure [Token refresh](#token-refresh). |
| `410` | Stop with `rejected` (the server ended the session). |
| Other `4xx` | The location is dropped, an `error` event with `UPLOAD_DROPPED` is emitted, the session keeps running. |
| `3xx` | Redirects are never followed. Treated like a `5xx`: the location stays pending and is retried with backoff. Point `upload.url` at the final address. |
| `5xx`, timeout, no network | The location stays pending. Retries back off 5 s, 10 s, 20 s, 40 s, then every 60 s while the process is alive. A new location replaces the pending one and is sent when the backoff timer fires; only connectivity returning or a final upload (arrival, expiry) ends the wait early. |

Throttle: after a success, locations that arrive within `minIntervalMs` stay pending and are sent when the interval has passed (by a timer while the process is alive, or with the next location).

Final uploads bypass the throttle:

- Arrival: the arrival location is sent and retried with the same backoff for at most 30 seconds (and never past `expiresAt`). Then the session stops with `arrived` (or `unauthorized` / `rejected` if the server answered 401/403/410). If it was not delivered in that time, for example with no network, it is dropped: the session does not stay running to deliver it.
- Expiry: any pending location gets a single attempt, also capped at 30 seconds, then the session stops with `expired`.

### Queue mode all

```ts
upload: {
  url: 'https://api.example.com/sessions/plan-123/locations',
  headers: { Authorization: 'Bearer <token>' },
  queue: { mode: 'all', maxRecords: 10000, maxAgeMs: 24 * 60 * 60 * 1000 },
  batch: { maxSize: 50, rootProperty: 'locations' },
  auth: { refresh: { url: 'https://api.example.com/auth/refresh', headers: { Authorization: 'Bearer <refresh token>' } } },
}
```

Every accepted location is written to a SQLite queue in app-private storage and sent in order. Nothing is replaced by a newer fix.

- Persistence: the queue survives process death, app updates and reboots. On iOS it is in `Application Support/expo-location-session/queue.sqlite`, excluded from backup, with file protection `completeUntilFirstUserAuthentication`. On Android it is `filesDir/expo-location-session/queue.sqlite` (see [Backup](#backup)). Rows being sent when the process dies are made pending again at the next launch. On iOS, locations that arrive before the first unlock after a reboot, while the database cannot be opened, are kept in memory (up to 500) and written once it opens.
- Retention: `maxRecords` (default 10000, at most 100000) counts every row in the database. When it is exceeded the globally oldest pending rows are dropped and a `QUEUE_TRIMMED` error event names how many. `maxAgeMs` (default 24 h, from 1 minute to 30 days) drops pending rows whose fix timestamp is older than that, checked when the queue is opened and before each delivery pass; it applies to `all` sessions, and pending rows that belong to no session are always dropped. Sessions that ended and have no rows left are forgotten, together with their stored credentials.
- Delivery is at-least-once. A new `start()` while a delivery of an ended session is in flight, a request watchdog (25 s) or an expiry can send a batch again. Each element carries a stable `id` (a UUID made when the fix was queued) so the server can dedupe on it.
- Throttle: `minIntervalMs` still spaces the sends that a new location triggers. Once a send succeeds, a backlog drains back to back, one request after another, `batch.maxSize` locations at a time.
- Connectivity: when the network comes back the queue is delivered without waiting for the backoff timer or for the next location.
- Request timeout: 20 s, as in `latest`. Redirects are never followed.

#### Request body

Without `batch` and `body`, each request holds one location, the v0.1 body plus an `id`:

```json
{
  "id": "6f1c0a52-0d0e-4a43-9d44-7a1d2f6a9b10",
  "sessionId": "plan-123",
  "location": { "latitude": 19.4326, "longitude": -99.1332, "accuracy": 12.3, "timestamp": 1763480000000 },
  "battery": { "level": 0.72, "charging": false },
  "extras": { "userId": 42 }
}
```

With `batch: { maxSize }` (1 to 500, only with mode `all`) and a `maxSize` above 1, each request holds up to `maxSize` locations in an envelope. `rootProperty` is the array name, `locations` by default. `extras` is sent once, in the envelope:

```json
{
  "sessionId": "plan-123",
  "locations": [
    {
      "id": "6f1c0a52-0d0e-4a43-9d44-7a1d2f6a9b10",
      "location": { "latitude": 19.4326, "longitude": -99.1332, "accuracy": 12.3, "timestamp": 1763480000000 },
      "battery": { "level": 0.72, "charging": false }
    }
  ],
  "extras": { "userId": 42 }
}
```

Elements keep the order in which the locations were taken. `altitude`, `speed`, `heading` and `battery` are left out when unknown, as in `latest`. With `maxSize: 1` there is no envelope: the request is the single-location body shown above.

#### Templates

`upload.body.template` replaces the shape of each element with your own JSON object. Strings in it can hold `{{placeholders}}`:

```ts
body: {
  template: {
    device: '{{extras.deviceId}}',
    point: { lat: '{{latitude}}', lng: '{{longitude}}', accuracy: '{{accuracy}}' },
    at: '{{timestampIso}}',
    label: 'fix {{id}} at {{timestamp}}',
  },
},
```

| Placeholder | Value |
| --- | --- |
| `{{id}}` | the row's stable UUID |
| `{{sessionId}}` | `SessionConfig.id` |
| `{{latitude}}`, `{{longitude}}`, `{{accuracy}}` | numbers |
| `{{altitude}}`, `{{speed}}`, `{{heading}}` | numbers, missing when the platform does not report them |
| `{{timestamp}}` | epoch ms of the fix |
| `{{timestampIso}}` | the same instant as an ISO 8601 UTC string with milliseconds |
| `{{batteryLevel}}`, `{{batteryCharging}}` | `0..1` and a boolean, missing when the level is unknown |
| `{{extras.<key>}}` | a value of `upload.extras`; the key must exist there |

- A string that is exactly one placeholder keeps the value's JSON type: `'{{latitude}}'` becomes a number. A string with other text around the placeholder, or with several, becomes text (numbers and booleans written as `12.3` and `true`).
- A missing value omits the key from an object, becomes `null` in an array, and is an empty string inside longer text. A non-finite number becomes `null`.
- Placeholders are replaced in every string value, at any depth, in objects and arrays; keys are not rewritten.
- The template must be a JSON object of at most 8 KB. An unknown field, an unknown `extras.` key or an unclosed `{{` rejects `start` with `INVALID_CONFIG: template: <reason>`.
- Without `batch` (or with `maxSize: 1`), the request body is the rendered object itself: `sessionId` and `extras` are not added, and `id` is there only when you use `{{id}}`. With `batch.maxSize` above 1, the rendered objects are the elements of the envelope above, which keeps its `sessionId` and `extras`. Put `{{id}}` in the template so the server can dedupe.
- A template also applies in mode `latest`.

#### Responses

| Response | Running session | Ended session (rows kept after the stop) |
| --- | --- | --- |
| `2xx` | The sent rows are deleted and `lastUploadAt` is recorded. | The sent rows are deleted. |
| `401` with `auth.refresh` | Refresh the credentials, then send the same rows again, once per attempt. A second `401` stops the session with `unauthorized`. | The same refresh and retry. A second `401` leaves the rows in place and retries with backoff. |
| `401` without `auth.refresh`, `403` | Stop with `unauthorized`. | The sent rows are dropped with `UPLOAD_DROPPED`. |
| `410` | Stop with `rejected`; the session's rows are deleted. | All rows of the session are deleted, with one `UPLOAD_DROPPED`. |
| Other `4xx` (for example `413`, `422`) | That batch is dropped with `UPLOAD_DROPPED`; the session keeps running, so one poison batch does not block the rest. | The same. |
| `3xx`, `5xx`, timeout, no network | The rows stay pending and are retried with backoff 5 s, 10 s, 20 s, 40 s, then every 60 s. Redirects are never followed. | The same. |

`413` is an ordinary 4xx: the batch is dropped, not split. Size `batch.maxSize` to the server's limit.

When a session stops in mode `all`, the rows it still has are kept, marked as ended, and tried again later, with the exceptions in the table (`410`, and `stop` with reason `rejected`, delete them). A stop caused by `401` or `403` keeps the rows too; their next attempt follows the ended column. In mode `latest` a stop deletes the pending location, as in v0.1.

Final uploads still run on arrival, expiry and `stop()`, with the same 30 s budget as in `latest`. What they do not deliver stays queued. Ended sessions are then delivered when the stop completes, when connectivity returns, when the process starts, on `syncQueue()` and from the OS job (see [Background delivery](#background-delivery)), one session at a time, oldest first, while a running session's own rows go first.

Server contract: the credentials of an ended session stay stored, and the server must keep accepting them, until its rows are delivered or dropped: up to `maxAgeMs` after the end. A `401` after the end is not recoverable unless `auth.refresh` is set.

### Token refresh

With `upload.auth.refresh`, a `401` from the upload endpoint makes the library fetch new credentials natively, with no JS, and send the same rows again:

```
POST <auth.refresh.url>
Content-Type: application/json
<auth.refresh.headers>

{ "sessionId": "plan-123" }
```

The body is `auth.refresh.body` as given when you set it, otherwise `{ "sessionId": "<id>" }`. `auth.refresh.url` must be https, and redirects are never followed. The request times out after 20 s. `auth.refresh.body` is stored with the session's target in the app's private queue database (`queue.sqlite`, excluded from backup), not in the Keychain, so do not put secrets in it; put them in `auth.refresh.headers`, which are stored like the upload headers.

A `2xx` answer must be a JSON object:

```json
{
  "headers": { "Authorization": "Bearer <new token>" },
  "refreshHeaders": { "Authorization": "Bearer <new refresh token>" }
}
```

- `headers` is required, non-empty, and holds string values. It replaces all the upload headers of this session, so send every header the upload endpoint needs. It is stored for the session, also for after the process restarts.
- `refreshHeaders` is optional, string values. It replaces the headers of the next refresh request (token rotation). When absent the previous ones are kept.
- After a successful refresh the same rows are sent again. If that attempt answers `401` again, the session stops with `unauthorized` (or, for an ended session, retries later); the library does not refresh twice in a row.

Failures emit `AUTH_REFRESH_FAILED` and are told apart:

- Transport failure (network error, timeout, a `3xx`): the upload is retried with backoff, and so is the refresh. Until the backoff timer fires, new locations are queued but do not trigger an upload; only connectivity returning or a final upload (arrival, expiry) ends the wait early. The session keeps running.
- Rejected by the server (a non-2xx, non-3xx status, or a `2xx` whose body is not the object above): the running session stops with `unauthorized`. For an ended session the rows stay and are retried later.

The refresh also applies in queue mode `latest`.

### Background delivery

Rows of an ended session are delivered while the process is alive, when connectivity returns and on `syncQueue()`. To also deliver while the app is closed, set the plugin option `backgroundSync: true` (or the bare setup in [Install](#bare-react-native)). The library then schedules an OS job when a session ends with rows left, and while no session runs.

- Android: a WorkManager job that requires a network connection. The system decides when it runs and retries it with backoff.
- iOS: a `BGProcessingTask` with the identifier `<bundle id>.els.queue-sync`. iOS decides when it runs: that can be hours later, or never for an app the user rarely opens. It does not run on the Simulator, so test it on a device. It is scheduled only while no session runs, and again when a session ends.
- The job reports success when no ended-session rows remain, and is rescheduled otherwise.

### Storage failure

When a location cannot be written to the queue (the database cannot be opened, the disk is full, or any other write error), the library reports `QUEUE_UNAVAILABLE` once and keeps the session running:

- `latest` (default): the newest location is kept in memory and uploaded with the same body, throttle, backoff, final-upload budget and status handling as a queued one. A newer location replaces it; a `2xx` or a drop clears it; a retry keeps it. Once writes succeed again, new locations go back to the queue, and a location still in memory is never overtaken by an older queued one. When the session stops, the location in memory is discarded, as the pending location is in 0.1. It does not survive process death.
- `all`: locations are never sent around the queue. Up to 500 are kept in memory, oldest dropped first (`QUEUE_TRIMMED` once per outage), and written in order on the next location and before each send attempt. Rows already in the queue keep being sent meanwhile, as far as the database allows. Locations still in memory when the process dies are lost.
- When the server accepts a request but its rows cannot be deleted, they are remembered in memory as sent: they are not sent again by this process, are left out of `getQueueStatus()`, and the delete is retried on the next queue operation. If the process dies before that, they are sent again at the next launch (delivery is at-least-once; deduplicate on the `id` template field, or on `sessionId` with the location `timestamp`). Rows that cannot be made pending again after a failed request are retried the same way.
- `QUEUE_UNAVAILABLE` is cleared once a location is written to the queue again and nothing is left in memory.

## Stop policy

The session stops itself when:

- `expired`: `now >= expiresAt`. Checked on every location, on resume and by a timer while the process is alive. Android also sets an alarm at `expiresAt`: an exact `setExactAndAllowWhileIdle` alarm when the app can schedule exact alarms (below Android 12, or "Alarms & reminders" granted), which fires on time even in Doze; otherwise an inexact `setAndAllowWhileIdle` alarm, which can fire minutes late. The alarm is re-armed when that permission changes: granting it (Android 12+ sends `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, received even when the process is not running) re-arms it as exact while a session is active; revoking it kills the app's process and deletes its exact alarms, and the next process start (a location delivery, a service restart or opening the app) resumes the session and re-arms it as inexact. Locations taken at or after `expiresAt` are never uploaded.
- `arrived`: a location is within `radiusMeters` of `destination` and its accuracy is at most `max(radiusMeters, 100)` meters.
- `unauthorized` / `rejected`: see the upload contract.
- `permissionRevoked`: location permission was removed. iOS detects it immediately (setting it to Never). Android kills the app on revocation, so it is detected at the next wake or app open.
- `stopped`: `stop()` was called, or Android could not restart the foreground service for 10 minutes.

Stopping removes location updates, regions, geofences, alarms and the Android notification, wipes the persisted session and pending location, and keeps only `lastStop`. In queue mode `all` the queued rows and the session's upload target stay for delivery (see [Queue mode all](#queue-mode-all)).

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

## Upgrading to 0.2

With none of the new options, the upload contract is the one of 0.1. Other changes to know about:

- After a failed upload, new locations wait for the backoff instead of being sent immediately. In 0.1 a new location triggered a new attempt at once (on iOS, about one request per location against a failing server). The backoff (5 s, 10 s, 20 s, 40 s, then 60 s) is now always respected; a new location still replaces the pending one, and connectivity returning still resets the backoff and sends at once. The request body is unchanged.
- Android: the library depends on `androidx.work:work-runtime`, so WorkManager's startup initializer (through `androidx.startup`) and its merged permissions, including `RECEIVE_BOOT_COMPLETED`, appear in your merged manifest whether or not `backgroundSync` is on. The library never calls WorkManager unless `backgroundSync` is on.
- A connectivity monitor runs in every queue mode, the default included, while a session runs or rows are pending. When the network returns it resets the backoff and uploads at once.
- Delivery is at-least-once: a request whose response is lost (timeout, process killed) is sent again, so the server may receive the same location twice. Deduplicate on `sessionId` with the location `timestamp`, or on the `id` template field.
- A new error code, `QUEUE_UNAVAILABLE`, reports that the queue database cannot be opened or written (see [Events](#events)). In the default mode the newest location is still uploaded from memory meanwhile (see [Storage failure](#storage-failure)).

## Known limits

- iOS cannot wake an app at a set time. If the app was killed and the phone does not move, region and significant-change monitoring stay registered past `expiresAt` until the next wake. At that wake the session stops as `expired` and is wiped without sending anything.
- iOS When In Use: no relaunch after the app is killed.
- OEM battery managers (Samsung, Xiaomi and others) can kill the foreground service or block relaunches. v0.1 has no OEM helpers.
- A force stop (Settings › Force stop) cancels Play services requests and alarms. The session resumes when the user opens the app.
- Android has no boot resume in v0.1: after a reboot the session resumes when the app is opened.
- Android requires Google Play services.
- One session at a time. The default `latest` mode keeps only the single pending location; the offline queue is opt-in (`upload.queue.mode: 'all'`).
- Queue delivery is at-least-once, and `getQueueStatus().pending` includes rows that are in flight. OS background delivery depends on the system scheduler (see [Background delivery](#background-delivery)).
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

Run the local echo server in another terminal. It prints every request body and answers `204`, or the status in `?status=` (for example `?status=401`); a request to `/fail?n=3` makes the next 3 uploads answer 503. `?auth=1` answers `401` unless the request carries `Authorization: Bearer fresh`, and `?auth=never` always answers `401`. `POST /refresh` answers `200` with `{ "headers": { "Authorization": "Bearer fresh" } }`, or `500` with `?fail=1`; point `upload.auth.refresh.url` at it. `POST /logs` accepts debug batches: it prints one summary line per batch (install id, line count, `seq` range, `Authorization` header), answers `204` or the status in `?status=`, and waits `?delayMs=` before answering. Set `LOG_LINES=1` to print every line.

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
| v0.2 (beta) | Full persistence and sync: SQLite queue, batching, body templates, native token refresh, upload on connectivity regained, delivery after the session ends and from OS background jobs |
| v0.3 | Motion / stationary detection (CoreMotion, Activity Recognition, stop timeout, stationary geofence) for long sessions |
| v0.4 | Geofencing: circular + polygon, enter/exit/dwell, beyond OS region limits |
| v0.5 | Scheduling windows, heartbeat, boot resume, native logs, power-save / OEM settings helpers |

## License

MIT
