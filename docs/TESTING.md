# On-hardware test checklist

Automated tests cover the protocol (`scripts/run-tests.sh`). Everything below
needs the real watch and the real phone, and is ordered so the first failure
tells you which half is broken.

## 0. Preconditions

| Thing | Check |
|---|---|
| Phone app installed | `adb shell pm list packages \| grep remoteshutter` |
| Camera permission granted | `adb shell dumpsys package com.remoteshutter \| grep CAMERA` |
| Garmin Connect installed and paired | `adb shell pm list packages \| grep connectmobile` |
| Watch app on the watch | `Settings > System > USB Mode > MTP`, then `scripts/install-watch.sh instinct2s` |
| Watch connected to the phone | open Garmin Connect once; the watch icon must be green |

## 1. Phone only (no watch)

This exercises camera + storage + threading without the BLE link.

```bash
adb shell am start -n com.remoteshutter/.MainActivity
adb logcat -c && adb logcat -s CiqLink &
adb shell am broadcast -a com.remoteshutter.TRIGGER --es cmd photo
adb shell am broadcast -a com.remoteshutter.TRIGGER --es cmd video
adb shell ls /sdcard/Pictures/RemoteShutter /sdcard/Movies/RemoteShutter
```

`--es cmd video` **toggles**: call it twice — first for start, second for stop.
The stop is only confirmed when the `Finalize` event arrives, which is when
the file becomes visible in MediaStore.

**Pass:** one JPEG per `photo`, one MP4 per video pair, and the on-screen
status shows `ok:...` each time.

## 2. Link only (watch → phone, no camera)

Watch: launch **Shutter**, press **START**.
Phone: `adb logcat -s CiqLink` should print

```
from <watch id>: Photo (SUCCESS)
```

and then, because the camera answers,

```
reply 'ok:photo' -> SUCCESS
```

**Pass:** the same message id round-trips. If you see the inbound line but no
reply line, the problem is on the phone side. If you see neither, it is the
Garmin Connect link.

## 3. Full loop

| Step | Watch shows | Expected |
|---|---|---|
| Press START (PHOTO) | `...` then `OK` + short buzz | JPEG on the phone |
| DOWN | `VIDEO` | mode switch only |
| Press START | `...` then `OK`, detail `recording` | MP4 starts |
| Press START | `...` then `OK`, detail `video` | MP4 finalises |
| UP | `PHOTO` | mode switch only |
| Walk out of range, press START | `FAILED / No phone` | nothing on the phone |
| Press BACK | — | app exits |

## 4. Failure matrix

| Symptom | Where to look |
|---|---|
| Watch: `FAILED / No phone` | BLE link. Garmin Connect running? Watch paired? |
| Watch: `FAILED / No reply` | Phone received nothing, or app not in foreground. `logcat -s CiqLink` |
| Watch: `FAILED / camera busy` | Camera not bound yet, or a video is still recording |
| Watch: `OK` but no file | MediaStore timing; check `Pictures/RemoteShutter` after a second |
| Phone shows "No watch paired" | `CiqLink.refresh()` found no devices — open Garmin Connect |
| Phone shows "Garmin Connect problem: GCM_NOT_INSTALLED" | install Garmin Connect Mobile |

## 5. Regressions worth adding as tests

Anything from §4 that is *decidable without hardware* belongs in
`ProtocolTest.kt`. Anything that needs the camera should be described here
instead — do not fake a `CameraProvider`.
