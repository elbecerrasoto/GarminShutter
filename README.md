# Remote Shutter

Trigger your phone's camera from a Garmin watch.

```
  Instinct 2                Samsung S21
 ┌──────────┐   BLE    ┌──────────────────────┐
 │  START   │ ───────► │ Garmin Connect       │
 │  button  │          │   Mobile (proxy)     │
 └──────────┘          └──────────┬───────────┘
                                  │ Android binder
                       ┌──────────▼───────────┐
                       │ Remote Shutter app   │
                       │  CameraX → shutter   │
                       └──────────────────────┘
```

Press **START** on the watch → the phone takes a photo (or starts/stops
video). Press **UP/DOWN** to switch between PHOTO and VIDEO. The watch shows
`OK` or `FAILED` so you always know whether the shot happened.

Photos land in `Pictures/RemoteShutter`, videos in `Movies/RemoteShutter`
(visible in Samsung Gallery / Google Photos).

---

## Why it is built this way (short version)

Connect IQ apps cannot touch a phone's camera, and they cannot open a raw
Bluetooth socket either. The only channel a watch app has to the phone is the
Connect IQ app-message channel, which is routed by **Garmin Connect Mobile**.

So the project is two halves that speak one tiny protocol:

| Side    | Tech                             | Job                                     |
|---------|----------------------------------|-----------------------------------------|
| Watch   | Connect IQ / Monkey C            | Send `"photo"` / `"video"`, show result |
| Phone   | Kotlin + CameraX + CIQ Mobile SDK| Own the camera, reply `ok:…` / `err:…`  |

Full decision record, including the approaches that were rejected and why:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Requirements

* Garmin Instinct 2 (or any watch in `watch/manifest.xml`) — Connect IQ 3.0+
* Android 12+ phone with **Garmin Connect** installed and the watch paired
* A Garmin developer account (free) — only needed once, to download the
  Connect IQ device definitions that `monkeyc` uses to compile
* `scripts/setup-toolchain.sh` installs JDK 17 + the Android SDK under
  `$HOME` (no root, no Android Studio)

## Install

### 1. Phone app

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or just `scripts/install-phone.sh`.

### 2. Watch app

**First, once:** on the watch, `Settings -> System -> USB Mode -> MTP`, then
replug the cable. In "Garmin mode" the watch enumerates as `091e:0003` with
no filesystem, so there is nothing to copy into.

```bash
scripts/install-watch.sh            # build for instinct2 + copy to GARMIN/APPS
scripts/install-watch.sh instinct2x # any other product id
```

Garmin sideloading is exactly that: drop the `.prg` into `GARMIN/APPS` and
the app shows up in the watch's app list. There is no `adb` equivalent.

**Build prerequisite:** `monkeyc` needs the Connect IQ *device library*,
which is not in the SDK zip. Open `connectiq-sdk-manager`, go to the
**Devices** tab and download the Instinct 2 family once. Without it every
build fails with `Invalid device id specified`.

## Usage

1. Open **Remote Shutter** on the phone and point it at the subject.
   The screen stays awake on purpose: CameraX is lifecycle-bound, so if the
   app is backgrounded the camera is released and triggers will be refused.
2. Launch **Shutter** on the watch.
3. Press **START**. The watch goes `...` then `OK` (and buzzes).
4. **UP** = photo mode, **DOWN** = video mode. In video mode, START starts and
   stops recording.

### Testing without the watch

The whole camera path can be exercised from the terminal:

```bash
adb shell am broadcast -a mx.ebecerra.remoteshutter.TRIGGER --es cmd photo
adb shell am broadcast -a mx.ebecerra.remoteshutter.TRIGGER --es cmd video
adb shell am broadcast -a mx.ebecerra.remoteshutter.TRIGGER --es cmd mode:video
```

If this works but the watch does not, the problem is on the Garmin side (see
Troubleshooting).

## Troubleshooting

| Symptom | Cause |
|---|---|
| Watch shows `FAILED / No phone` | Garmin Connect Mobile not running, or the watch is out of range |
| Watch shows `FAILED / No reply` | Phone app not running in the foreground |
| Watch shows `FAILED / camera busy` | Still writing the previous shot — wait a moment |
| Phone says "No watch paired in Garmin Connect" | Watch not connected; open Garmin Connect once |
| Phone says "Garmin Connect problem: GCM_NOT_INSTALLED" | Install Garmin Connect Mobile |

`adb logcat -s CiqLink` shows exactly what the phone receives from the watch.

## Repository layout

```
android/                     Kotlin companion app (CameraX + Connect IQ SDK)
watch/                       Connect IQ app (Monkey C)
scripts/                     build / install / backup helpers
docs/ARCHITECTURE.md         design decisions and rejected approaches
tools/                       locally downloaded SDKs (git-ignored)
```

## Status

The phone half is verified on real hardware (photos written to
`Pictures/RemoteShutter`, 18+6 unit tests green). The watch half compiles
cleanly for `instinct2s` and the `.prg` is 96 KB, but the copy into
`GARMIN/APPS` is still pending: the watch's USB mass-storage interface wedges
under sustained I/O on this machine. See [LESSONS.md](LESSONS.md) "Garmin USB
storage" for the diagnosis and the workaround (`ro` mount, write first).

Not published to the Connect IQ store: that requires a public privacy policy,
screenshots and a review cycle, and the app is only useful to someone who also
installs the APK. See `docs/ARCHITECTURE.md` for the "make it a real product"
checklist.

## Tests

Two suites, both self-contained, neither needs the watch.

```bash
scripts/run-tests.sh            # both
scripts/run-tests.sh phone      # JVM only (fast)
scripts/run-tests.sh watch      # Connect IQ simulator only
```

| Suite | What | How | Needs |
|---|---|---|---|
| Phone | 18 JUnit tests over `Protocol.kt` | `./gradlew testDebugUnitTest` | JDK 17 only |
| Watch | 6 Monkey C tests over `ReplyProtocol.mc` | `monkeyc -t` + `monkeydo -t` | Connect IQ SDK + simulator |

The two suites test **the same contract from both sides**, because that
contract is where the silent failures live:

```
watch -> phone : "photo" | "video"
phone -> watch : "ok:<detail>" | "err:<detail>"
```

What is pinned down:

* commands are case- and whitespace-insensitive;
* the Connect IQ SDK delivers a **List**, always — `parseMessage` unwraps it
  (this is the bug that would have made every successful shot report
  `FAILED` on the watch);
* replies split on the **first** colon only, so `ok:video 00:12` parses;
* anything unrecognised becomes an error, never a silent success.

The camera and the BLE link are exercised manually — see “Testing without
the watch” above for the `adb shell am broadcast` path.

For the manual, on-hardware checklist see [docs/TESTING.md](docs/TESTING.md).

## Lessons learned

[LESSONS.md](LESSONS.md) — every platform trap that cost time, as
symptom → cause → fix. Read it before touching `monkeyc`, `udisks`, or the
watch's USB mode.
