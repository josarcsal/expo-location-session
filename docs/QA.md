# QA

## iOS survival (real device)

Run on a physical iPhone with the example app, a reachable HTTPS upload endpoint and a session that expires well after the test.

| Done | # | Check | Expected | Result |
|------|---|-------|----------|--------|
| [ ] | 1 | Always granted → `start` → kill the app from the app switcher → walk or drive ~300 m with the phone locked | The server receives uploads without the app being opened | |
| [ ] | 2 | Same as 1, measure distance until the first upload after the kill | First upload after roughly 200 m (terminate region), not only after a ~500 m significant change | |
| [ ] | 3 | When In Use only → `start` → kill from the app switcher → move ~300 m | No uploads while the app is closed | |
| [ ] | 4 | After 3, open the app before `expiresAt` | Session resumes and uploads continue; `getState().survivesProcessDeath` is `false` | |
| [ ] | 5 | Always → `start` → kill → wait past `expiresAt` → move | App relaunches, stops with `expired`, nothing is uploaded | |
| [ ] | 6 | Always → `start` → reboot the phone → move before unlocking → unlock | Nothing happens before the first unlock; the session resumes after unlock and the stored session is not wiped | |
| [ ] | 7 | Always → `start` → kill from the app switcher → lock the phone and wait 20 s → move ~300 m | Relaunch while locked resumes the session and uploads (Keychain readable after first unlock) | |
| [ ] | 8 | While running, Settings → Location → When In Use | Session keeps running; `survivesProcessDeath` becomes `false` | |
| [ ] | 9 | While running, Settings → Location → Never | Session stops with `permissionRevoked` | |
| [ ] | 10 | `stop()` after a kill/relaunch cycle | No `els.*` regions remain; no further relaunches or uploads | |

## Android survival (real device)

Run on a Pixel with Android 14+ and the example app, a reachable HTTPS upload endpoint and a session that expires well after the test. Watch `adb logcat -s ELS ExpoLocationSession`; every promotion logs `service promoted via <route>` under the `ELS` tag.

| Done | # | Check | Expected | Result |
|------|---|-------|----------|--------|
| [ ] | 1 | Background location granted → `start` → background the app → `adb shell am kill <package>` (or an OEM kill) → move | The next movement relaunches the process, the notification returns, uploads continue | |
| [ ] | 2 | Same as 1, note the `ELS` route lines after the relaunch | Record whether `foreground service start allowed via location` appears (a plain location broadcast is an FGS-start exemption) or `denied via location` | |
| [ ] | 3 | Turn off Settings › Apps › Special app access › Alarms & reminders for the app; repeat 1 until a start is denied | `retry scheduled via recoveryGeofence`, then `service promoted via recoveryGeofence` | |
| [ ] | 4 | Alarms & reminders on; repeat 1 until a start is denied | `retry scheduled via exactAlarm`, then `service promoted via exactAlarm` | |
| [ ] | 5 | Without background location → `start` → background → kill → move | No relaunch with a notification, no uploads while closed | |
| [ ] | 6 | After 5, open the app before `expiresAt` | Session resumes and uploads continue; `getState().survivesProcessDeath` is `false` | |
| [ ] | 7 | Swipe the app from recents while running | Notification stays, uploads continue | |
| [ ] | 8 | Denied starts keep happening for 10 minutes (e.g. both routes unavailable) | `error` with code `FGS_PROMOTION_FAILED`, then the session stops with `stopped` | |
| [ ] | 9 | `stop()` after a kill/relaunch cycle | No relaunches, no uploads, no notification, no recovery geofence left | |
