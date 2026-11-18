# expo-location-session

Background location sessions for React Native. A session sends the device position to your server from native code (Swift on iOS, Kotlin on Android). It keeps working when the app is in the background, when the screen is locked and, with the right permission, after the app has been killed. It stops itself on arrival at a destination or at an expiry time, even if JavaScript never runs.

Built with [Nitro Modules](https://nitro.margelo.com). Status: v0.1 alpha. One session at a time, latest position only.

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

`start` rejects with `INVALID_CONFIG` on iOS when `UIBackgroundModes` does not contain `location`.

Android: the library manifest already declares fine and coarse location, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `WAKE_LOCK` and `SCHEDULE_EXACT_ALARM`, plus its service and receiver. To survive process death, add background location to your app manifest:

```xml
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

For a custom notification icon, add a drawable named `expo_location_session_notification_icon`.

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
```

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
```

`addListener` returns an unsubscribe function. Events reach JS only while the JS runtime is alive; the native session does not depend on them. `upload.status` is missing when the request failed without a response (timeout, no network).

Error event codes:

- `UPLOAD_DROPPED`: the server answered a 4xx other than 401, 403 or 410 (or, on Android, the body could not be encoded). That location is dropped and the session keeps running.
- `FGS_PROMOTION_FAILED` (Android): Android refused to restart the foreground service for 10 minutes. The session then stops with reason `stopped`. See [Android](#android).

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
| `5xx`, timeout, no network | The location stays pending. Retries back off 5 s, 10 s, 20 s, 40 s, then every 60 s while the process is alive, and a new location triggers a new attempt. |

Throttle: after a success, locations that arrive within `minIntervalMs` stay pending and are sent when the interval has passed (by a timer while the process is alive, or with the next location).

Final uploads bypass the throttle:

- Arrival: the arrival location is sent, retried with the same backoff until it is delivered, the server answers with a terminal status, or `expiresAt` passes. Then the session stops with `arrived` (or `unauthorized` / `rejected` if the server answered 401/403/410).
- Expiry: any pending location gets a single attempt, then the session stops with `expired`.

## Stop policy

The session stops itself when:

- `expired`: `now >= expiresAt`. Checked on every location, on resume and by a timer while the process is alive. Android also sets an inexact `AlarmManager.setAndAllowWhileIdle` alarm at `expiresAt` (it can fire a few minutes late). Locations taken at or after `expiresAt` are never uploaded.
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
- When the app is about to terminate and Always is granted, it registers an exit-only region of 200 m around the last fix, so moving away relaunches the app sooner than a significant change would (~500 m).
- At launch the bootstrap resumes the persisted session without JS: it stops it as `expired` or `permissionRevoked`, or restarts updates and sends the pending location.
- The session config (headers included) is stored in the Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`; the pending location and `lastStop` are in `UserDefaults`. After a reboot nothing resumes before the first unlock; the engine waits and never overwrites the stored session. Because of `ThisDeviceOnly`, a session is not restored on a device restored from a backup or migrated to a new phone.
- Uploads run in a `beginBackgroundTask` so a request can finish after the app is suspended.
- v0.1 does not use iOS 17's `CLBackgroundActivitySession`: the library supports iOS 15.1+, and `allowsBackgroundLocationUpdates` with the `location` background mode already keeps updates running on every supported version.

### Android

- `start` launches a foreground service of type `location` (`stopWithTask="false"`, `START_STICKY`) with a low-importance notification built from `android.notification`.
- Location updates are requested from `FusedLocationProviderClient` with a `PendingIntent` to a manifest receiver (the launch gate). Play services keeps the request registered outside the process, so a delivery recreates the process.
- On each delivery the launch gate loads the session (expired or invalid: removes updates and wipes), hands the location to the engine, and starts the foreground service again if it is not running.
- If Android refuses to start the service from the background, the gate falls back to:
  1. an exact alarm that fires immediately, only if the user granted "Alarms & reminders" (`SCHEDULE_EXACT_ALARM`, off by default on Android 14+). Apps can send users to that setting (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`); the library does not.
  2. otherwise a recovery geofence around the last fix (radius: accuracy + 200 m, enter or exit, expires after 2 minutes).
  A second refusal waits for the next delivery. Until the service is back, locations are still handled and uploaded, without a notification. If no restart succeeds for 10 minutes after the first refusal, the session emits `FGS_PROMOTION_FAILED` and stops with `stopped`.
- The session is stored in app-private `SharedPreferences`, headers included, unencrypted.

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

`example/` is an Expo development build with a screen to request permissions, pick a destination, start and stop a session against a configurable URL, and see the state and event log.

```sh
yarn install
yarn build:plugin
cd example
yarn install
yarn ios       # or: yarn android
```

Run the local echo server in another terminal. It prints every request body and answers `204`, or the status in `?status=` (for example `?status=401`); a request to `/fail?n=3` makes the next 3 uploads answer 503.

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
