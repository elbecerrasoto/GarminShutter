# Architecture & Decision Record

## 1. The problem, restated as constraints

We want: press a button on a Garmin watch → an Android phone takes a photo
(or toggles video).

Before choosing anything, three facts about the Connect IQ platform bound the
solution space. They were verified against the SDK (`tools/connectiq/doc`) and
against the actual `ciq-companion-app-sdk-2.4.0.aar` that we decompiled.

1. **A watch app cannot touch the phone's camera directly.** There is no
   "camera" API in Connect IQ. There is no way to send an Android `Intent`.
2. **A watch app cannot open a generic Bluetooth socket.** Connect IQ exposes
   `Toybox.Communications` only. The watch has no internet and no raw BLE
   peripheral/central API for arbitrary protocols.
3. **App messages to the phone go through Garmin Connect Mobile.** The
   Android mobile SDK (`com.garmin.connectiq:ciq-companion-app-sdk`) is not a
   BLE stack: its AAR contains AIDL interfaces
   (`com.garmin.android.apps.connectmobile.connectiq.ICompanionAppService`)
   and a `<queries>` entry for `com.garmin.android.apps.connectmobile`. It
   binds to Garmin Connect Mobile, which owns the BLE link.

Point 3 is the good news: we inherit Garmin Connect's battle-tested pairing,
reconnection and battery handling. It is also a dependency: **Garmin Connect
must stay installed, running and paired.**

## 2. Options considered

### A. Steer the OEM camera app (AccessibilityService)

Watch → phone app → AccessibilityService clicks the shutter node in the OEM
Camera.

* Pro: you keep the OEM's image processing.
* Con: the OEM camera app's view hierarchy changes between versions, the
  service dies when the screen locks, and Google restricts accessibility APIs
  used for automation. This is the definition of "it works until it doesn't".

**Rejected.**

### B. Use `ACTION_IMAGE_CAPTURE` / `INTENT_ACTION_STILL_IMAGE_CAMERA`

* Pro: three lines of code.
* Con: `ACTION_IMAGE_CAPTURE` ends in a save/discard confirmation on most OEM
  camera apps; `INTENT_ACTION_STILL_IMAGE_CAMERA` only *opens* the camera app
  and gives no way to press the shutter. Neither can start/stop video.

**Rejected.**

### C. Watch app → Connect IQ Mobile SDK → our own camera  ✅ CHOSEN

* Watch: `Communications.transmit("photo", null, listener)`.
* Phone: `registerForAppEvents(...)` + CameraX.
* Latency: app-message round trip, ~100–300 ms. Feels instant.
* Uses Garmin's official, documented, versioned API.

**Chosen.** It is the only path that is both officially supported and gives us
full control of the shutter.

### D. Watch app → `makeWebRequest("http://127.0.0.1:PORT/…")`

Interesting and much smaller: no mobile SDK at all, just an HTTP server on the
phone. Garmin explicitly allows plain HTTP to `127.0.0.1` because the request
is proxied by Garmin Connect Mobile, so `127.0.0.1` means *the phone*.

* Pro: ~80 lines of Monkey C, ~120 lines on the phone, no AAR, no binder.
* Con: every trigger pays a full HTTP round trip through Garmin Connect's
  proxy (higher latency, more battery), the localhost proxy has a history of
  quirks, and error reporting is coarser.

**Kept as a documented fallback.** If Garmin ever breaks the mobile SDK, this
is the escape hatch, and the camera half of this project would not change at
all — `onCommand()` is the seam.

### E. Abusing Garmin's music controls as a media-key trigger

* Pro: zero watch-side code.
* Con: hijacks the user's media session and play/pause. Cute, unusable.

**Rejected.**

### F. Custom BLE protocol / ANT+ / hardware dongle

Not possible from Connect IQ for arbitrary payloads. **Rejected.**

## 3. The protocol

Deliberately the smallest thing that can work — plain strings, no
serialization layer to desynchronize:

| Direction      | Payload                             |
|----------------|-------------------------------------|
| watch → phone  | `photo` \| `video`                  |
| phone → watch  | `ok:<detail>` \| `err:<detail>`     |

Why the ack matters: `Communications.transmit` completing only means the
message reached the *phone*, not that the camera fired. The watch therefore
stays in `...` and waits for the phone's `ok:`/`err:`. If nothing arrives in
6 s it shows `FAILED / No reply`. This is what turns a demo into something you
can trust in front of a group of people.

## 4. Why we own the camera

CameraX, `Pictures/RemoteShutter`, `Movies/RemoteShutter`, no OEM-specific
APIs, no root, no accessibility service. Consequences:

* Works on *any* Android 12+ phone, not just one phone — nothing in the phone
  app is device-specific.
* Silent capture (no fake shutter sound), full control of flash, zoom, and
  video.
* Trade-off: you shoot with our viewfinder, not the OEM's. Acceptable: the
  phone is on a tripod and nobody is touching it.

## 5. Failure modes and how they are handled

| Failure | Handling |
|---|---|
| Garmin Connect not installed | `onInitializeError(GCM_NOT_INSTALLED)` → shown on screen |
| Watch out of range | transmit error → `FAILED / No phone` |
| Phone app not in foreground | camera released by lifecycle → `err:camera busy` |
| Phone slow to write the JPEG | reply arrives late; 6 s timeout |
| Key bounce / double press | 800 ms debounce in `ShutterView.shoot()` |
| Camera not yet bound | `err:camera init failed` |
| Garmin Connect killed by the OS | re-open the app; `ciq.start()` retries |

Not handled (by design, for now): triggering while the phone screen is off.
CameraX is lifecycle-bound, so the activity must be visible.

## 6. Things deliberately NOT implemented (options considered)

The point of listing these is that the architecture already has room for them;
none of them are built.

**Near-term, cheap**

* **Self-timer / countdown** (`3…2…1` on the watch) so you can hide the
  button press. Pure watch-side state, no protocol change.
* **Burst mode**: hold START → N photos. `ImageCapture` already queues.
* **Video length / quality selector**, audio on/off.
* **Flash toggle, zoom, front/back camera.** Needs new commands; the protocol
  is a string, so it is `"cmd:arg"` and a `when` branch.
* **Persist last mode** in `Application.Storage` so the watch remembers
  PHOTO/VIDEO across launches.

**Interesting**

* **Live preview on the watch.** The SDK has `sendImage` /`IQSendImageListener`
  and the watch can draw bitmaps. A 1 fps, 176×176 thumbnail on your wrist is
  genuinely useful for framing a solo shot. Cost: a real protocol (chunked
  image frames) and battery.
* **Photo review**: send the JPEG back at low res so you can check the shot
  without walking to the phone.
* **Foreground service with `foregroundServiceType="camera"`** so the camera
  keeps running with the screen off. This is the single biggest UX
  improvement and also the biggest chunk of Android plumbing (custom
  `LifecycleOwner`, wake locks, notification).
* **HTTP trigger in parallel with the watch**, which would let a laptop on the
  same Wi-Fi trigger the camera (via `adb forward`). Two lines of `NanoHTTPD`
  and `onCommand()` does the rest — this is the "any trigger source" seam
  paying off.
* **Glance / widget** on the watch for one-swipe access instead of launching
  the app.
* **Makefile for the phone app** (`shake to trigger`).

**Product-level**

* Publish to the Connect IQ store. Requires a privacy policy, screenshots, a
  review cycle, and an APK distribution story — for a tool used by exactly one
  person, side-loading is the correct choice.
* iOS companion. The same Monkey C app would work unchanged; only the phone
  half is new.

## 7. Toolchain notes (Linux, no Android Studio)

Reproducing this build on Manjaro without Android Studio required:

```bash
# JDK 17 (AGP 8.6 does not support JDK 26)
~/.local/share/jdks/jdk-17.0.20.1+1

# Android SDK (command line tools only)
~/android-sdk   # platforms;android-35, build-tools;35.0.0

# Connect IQ SDK
tools/connectiq # connectiq-sdk-lin-9.1.0
```

`monkeyc` needs the Connect IQ *device library*, which is **not** in the SDK
zip: it is downloaded by the SDK Manager into `~/.Garmin/ConnectIQ/Devices/`
and requires a (free) Garmin developer login. Without it every device id is
rejected with `Invalid device id specified`.

The SDK Manager binary needs `libjxl.so.0.11`; Manjaro ships `0.12`, so
`tools/jxlshim/libjxl.so.0.11` is a symlink used through `LD_LIBRARY_PATH`.
