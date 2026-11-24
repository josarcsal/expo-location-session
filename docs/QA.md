# QA

v0.1 is done when every row passes on three physical devices: an iPhone (iOS 17+), a Pixel (Android 14+) and a Samsung or Xiaomi. Write the result in the device column (`pass`, `fail` plus a note, or a measured value); `n/a` means the row does not apply to that platform.

## Setup

- The example app (`example/`) as a development build, driven by hand or with Maestro flows.
- A reachable HTTPS upload endpoint, for example `example/scripts/echo-server.mjs` with TLS (see the README). It answers `?status=<code>` and `/fail?n=<count>`, and takes debug batches at `/logs` (`?status=<code>`, `?delayMs=<ms>`). For the queue rows below: `?auth=1` answers `401` until the request carries `Authorization: Bearer fresh` (`?auth=never` always answers `401`), and `POST /refresh` returns that token (`?fail=1` answers `500`); set `upload.auth.refresh.url` to it.
- A session that expires well after the test unless the row says otherwise.
- iOS: watch the device console for the `expo-location-session` subsystem.
- Android: `adb logcat -s ELS ExpoLocationSession`. Every service promotion logs `service promoted via <route>` under the `ELS` tag.
- Killing the Android process: `adb shell am kill <package>` does not kill it while the location foreground service runs. Use `adb shell run-as <package> kill -9 $(adb shell pidof <package>)` (debuggable builds) or let the OEM kill it.

## Matrix

### Basics

| Done | # | Check | Expected | iPhone | Pixel | Samsung / Xiaomi |
|------|---|-------|----------|--------|-------|------------------|
| [ ] | 1 | Foreground: `start` → walk or drive | One upload per `minIntervalMs` at most; `location` and `upload` events in the log | | | |
| [ ] | 2 | Background 30 min with the screen locked while moving | Uploads continue the whole time; iOS shows the location indicator, Android the notification | | | |
| [ ] | 3 | Airplane mode for 5 min while moving, then off | Uploads fail and retry; the latest pending location goes out when the network is back | | | |
| [ ] | 4 | Destination set → reach it | Arrival location uploaded, then `stop` with `arrived` | | | |
| [ ] | 5 | Expiry with the app alive | `stop` with `expired` at `expiresAt`; nothing uploaded after it | | | |
| [ ] | 6 | URL with `?status=401` | `stop` with `unauthorized` | | | |
| [ ] | 7 | URL with `?status=410` | `stop` with `rejected` | | | |
| [ ] | 8 | URL with `?status=422` | `error` with `UPLOAD_DROPPED`; the session keeps running | | | |
| [ ] | 9 | 30-min session while moving | Record battery used (%) | | | |
| [ ] | 44 | URL with `?status=302` (and once with `?status=307`) | The echo server answers with `Location: /redirected`; no request to `/redirected` appears in its log. `upload` events with `ok: false` and the 3xx status, retries back off, no `error` and no stop; the location stays pending | | | |

### iOS survival

| Done | # | Check | Expected | iPhone | Pixel | Samsung / Xiaomi |
|------|---|-------|----------|--------|-------|------------------|
| [ ] | 10 | Always → `start` → kill from the app switcher → move ~300 m with the phone locked | The server receives uploads without the app being opened | | n/a | n/a |
| [ ] | 11 | Same as 10, read the `first location event after launch` line | Note `kind` (`regionExit` or `locations`), `msSinceLaunch` and `distanceFromTerminateCenter`. A `regionExit` should come at roughly `terminateRadius` from the centre (150 m for a fresh accurate fix; iOS may report it later); a relaunch inside the region comes from a significant change | | n/a | n/a |
| [ ] | 12 | When In Use only → `start` → kill from the app switcher → move ~300 m | No uploads while the app is closed | | n/a | n/a |
| [ ] | 13 | After 12, open the app before `expiresAt` | Session resumes and uploads continue; `getState().survivesProcessDeath` is `false` | | n/a | n/a |
| [ ] | 14 | Always → `start` → kill → wait past `expiresAt` → move | App relaunches, stops with `expired`, nothing is uploaded | | n/a | n/a |
| [ ] | 15 | Always → `start` → reboot the phone → move before unlocking → unlock | Nothing happens before the first unlock; the session resumes after unlock and the stored session is not wiped | | n/a | n/a |
| [ ] | 16 | Always → `start` → kill from the app switcher → lock the phone and wait 20 s → move ~300 m | Relaunch while locked resumes the session and uploads (Keychain readable after first unlock) | | n/a | n/a |
| [ ] | 17 | While running, Settings → Location → While Using the App | Session keeps running; `survivesProcessDeath` becomes `false` | | n/a | n/a |
| [ ] | 18 | While running, Settings → Location → Never | Session stops with `permissionRevoked` | | n/a | n/a |
| [ ] | 19 | `stop()` after a kill/relaunch cycle | No `els.*` regions remain; no further relaunches or uploads | | n/a | n/a |
| [ ] | 45 | Always → `start` with `distanceFilterMeters: 500` → drive at 50+ km/h → kill from the app switcher between two fixes; repeat once indoors where the last fix has 150 m+ accuracy | The `armed terminate region` line shows `r` above 150 (speed × fix age, or accuracy + 150), never above 1000; the next `first location event after launch` is a `regionExit` | | n/a | n/a |
| [ ] | 46 | Always → `start` → install over it a build whose `Info.plist` has no `location` in `UIBackgroundModes` → move ~1 km without opening the app → open it | No crash on the relaunch; the debug log has `UIBackgroundModes has no location; background updates not enabled`; on open an `error` event with `CONFIG_MISSING_BACKGROUND_MODE`, and the session keeps updating in the foreground | | n/a | n/a |
| [ ] | 47 | While running, Settings → app → Location → Precise Location off; then on; then off before `start` and `start` again | One `error` event with `ACCURACY_REDUCED` per switch to off and one at `start`, none when it is turned back on; the session keeps running and uploads coarse fixes | | n/a | n/a |

### Android survival

| Done | # | Check | Expected | iPhone | Pixel | Samsung / Xiaomi |
|------|---|-------|----------|--------|-------|------------------|
| [ ] | 20 | Swipe the app from recents while running | Notification stays, uploads continue | n/a | | |
| [ ] | 21 | Background location granted → `start` → background the app → kill the process (`run-as … kill -9`, or an OEM kill) → move | The next movement relaunches the process, the notification returns, uploads continue | n/a | | |
| [ ] | 22 | Same as 21, note the `ELS` route lines after the relaunch | Record whether `foreground service start allowed via location` appears (a plain location broadcast is an FGS-start exemption) or `denied via location` | n/a | | |
| [ ] | 23 | Alarms & reminders on; repeat 21 until a start is denied | `retry scheduled via exactAlarm`, then `service promoted via exactAlarm` | n/a | | |
| [ ] | 24 | Settings › Apps › Special app access › Alarms & reminders off; repeat 21 until a start is denied | `retry scheduled via recoveryGeofence`, then `service promoted via recoveryGeofence` | n/a | | |
| [ ] | 25 | Denied starts keep happening for 10 minutes (e.g. both routes unavailable) | `error` with `FGS_PROMOTION_FAILED`, then the session stops with `stopped` | n/a | | |
| [ ] | 26 | Without background location → `start` → background → kill the process → move | No relaunch, no notification, no uploads while closed | n/a | | |
| [ ] | 27 | After 26, open the app before `expiresAt` (from the launcher; the app does not need to call the library on mount) | Session resumes and uploads continue; `getState().survivesProcessDeath` is `false` | n/a | | |
| [ ] | 28 | Background location granted → `start` → kill the process → wait past `expiresAt` | The expiry alarm (exact with Alarms & reminders on, otherwise inexact and possibly minutes late) wakes the app and the session stops with `expired`; nothing is uploaded after `expiresAt`; on the next open `getState().lastStop.reason` is `expired` | n/a | | |
| [ ] | 29 | `stop()` after a kill/relaunch cycle | No relaunches, no uploads, no notification, no recovery geofence left | n/a | | |
| [ ] | 40 | Alarms & reminders on → `start` with `expiresAt` 15 min ahead, no destination → leave the phone still with the screen off (Doze: `adb shell dumpsys deviceidle force-idle` to force it) | `exact expiry alarm set` is logged; the session stops with `expired` within a minute of `expiresAt`, and the notification goes away | n/a | | |
| [ ] | 41 | Alarms & reminders off (Android 14+), same as 40 | `inexact expiry alarm set` is logged; the session stops with `expired`, possibly minutes late | n/a | | |
| [ ] | 42 | Background location granted → `start` → background the app → install an update over it (`adb install -r` of a new build, or a Play internal-testing update) → move | Without opening the app, the gate logs `gate delivery packageReplaced`, `service promoted via packageReplaced`, the notification returns and uploads continue | n/a | | |
| [ ] | 43 | Same as 42 with Alarms & reminders off and a short session; install the update after `expiresAt`, before the inexact expiry alarm has fired | `gate delivery packageReplaced`, then the session stops with `expired` without the app being opened; nothing uploaded after `expiresAt` | n/a | | |

### Build configurations

| Done | # | Check | Expected | iPhone | Pixel | Samsung / Xiaomi |
|------|---|-------|----------|--------|-------|------------------|
| [ ] | 30 | Android release build with R8 minify (`android.enableMinifyInReleaseBuilds=true`): `start` → kill the process (release builds are not debuggable: use an OEM kill, or `adb root` and `kill -9` on an emulator) → relaunch from the launcher | `start` resolves, uploads arrive, the session resumes after the relaunch | n/a | | |
| [ ] | 31 | iOS build with static frameworks (`expo-build-properties` `ios.useFrameworks: "static"`): Always → `start` → kill → relaunch | `bootstrap launch` is logged at launch and the session resumes | | n/a | n/a |

### Debug logging

The Android logger is in 0.1.0-beta.2 and later. Use the example's debug toggle with the debug URL pointing at the echo server `/logs`; the toggle logs the install id, which batches carry as `install.id`.

| Done | # | Check | Expected | iPhone | Pixel | Samsung / Xiaomi |
|------|---|-------|----------|--------|-------|------------------|
| [ ] | 32 | Debug off (default) → `start` → move | No `debug-*.jsonl` file exists and the echo server receives no `/logs` request | | | |
| [ ] | 33 | Debug on with the echo server `/logs` → `start` → move | Lines arrive in `seq` order with no gaps; `location` and `upload` lines appear; a batch arrives about every `flushIntervalMs` and after each upload | | | |
| [ ] | 34 | Debug on → kill the app → relaunch with the phone locked (Always, as row 16) | Lines with a new `pid` and a `lifecycle` line with the launch reason reach the server | | | |
| [ ] | 35 | Debug on → `/logs?delayMs=60000` or `?status=500` as the debug URL → move | Location uploads keep arriving at their normal cadence | | | |
| [ ] | 36 | Debug on with a small `maxFileBytes` and no reachable server → move until the files rotate → restore the server | The first batch starts with a `lines lost` line; no `seq` is skipped silently | | | |
| [ ] | 37 | Debug on → watch the device console / logcat during a session | No coordinates in `os_log` or logcat | | | |
| [ ] | 38 | Debug on, then `configureDebug({ enabled: false })` → move | No new lines and no requests; the files are still there | | | |
| [ ] | 39 | Debug on → compare the install id the example logs with `install.id` in a batch | They are equal | | | |

### Queue and sync (v0.2)

Every v0.1 row above is re-run once with the default config (no `upload.queue`); that is the regression gate for v0.2.

| Done | # | Check | Expected | iPhone | Pixel | Samsung / Xiaomi |
|------|---|-------|----------|--------|-------|------------------|
| [ ] | 48 | `queue: { mode: 'all' }` with `batch: { maxSize: 50 }` → `start` → airplane mode for 10 min while moving → online | `getQueueStatus().pending` grows while offline; when the network returns the backlog is delivered in order in batches of at most 50, each element with a unique `id`, and `pending` goes to 0 | | | |
| [ ] | 49 | Mode `all` → `start` → go offline → move → `stop()` with rows pending → kill the app → reconnect (with `backgroundSync: true`, do not open the app) | The rows of the ended session are delivered by the OS job (iOS: may take hours or never, device only); with the app opened instead, they are delivered at launch; `pending` ends at 0 | | | |
| [ ] | 50 | Mode `all` with `auth.refresh` → URL `?auth=1` (the echo server wants `Bearer fresh`) | One `401`, one `POST /refresh`, the same rows sent again with `Bearer fresh` and accepted; the session keeps running. Then `?auth=never`: after the refresh the second `401` stops the session with `unauthorized`, `AUTH_REFRESH_FAILED` is not emitted, and no row stays in flight | | | |
| [ ] | 51 | Mode `all` → URL `?status=422` | `error` with `UPLOAD_DROPPED` for each dropped batch, the session keeps running, the dropped batch does not block the batches after it (switch the URL back to `?status=204` mid-session and the rest is delivered) | | | |
| [ ] | 52 | Mode `all` with `maxRecords: 20` and `maxAgeMs: 60000` → go offline → move for 5 min | `QUEUE_TRIMMED` errors name the dropped count, `pending` never passes 20, and after the age limit old pending rows are purged | | | |
