# Lessons learned

Everything below cost time at least once. The format is
**symptom → cause → fix**, because that is how it will be needed again.

---

## Connect IQ / Monkey C

### `ERROR: Invalid device id specified: 'instinct2'`
**Cause.** Since Connect IQ 8.x the SDK zip no longer ships device profiles.
`monkeyc` reads them from `~/.Garmin/ConnectIQ/Devices/<product>/`
(`compiler.json` + `simulator.json`), which only the SDK Manager downloads,
and only after a (free) Garmin developer login.

**Fix.** `connectiq-sdk-manager` → **Devices** tab → download the device
family. They land in `~/.Garmin/ConnectIQ/Devices/`. The device list lives
behind `https://api.gcs.garmin.com/ciq-product-onboarding/devices` and
returns 401 without a token, so it cannot be fetched with curl.

**Trap.** `monkeyc -u devices.xml` is still accepted but is **ignored**:
`The command line option -u is deprecated and will be ignored`. The error is
identical, which makes it look like the XML was malformed. It was not.
The `devices.xml` inside `monkeybrains.jar` is a complete 196-device
database, but nothing will read it for you.

### `The private symbol 'onTimeout' will not be found when using ':onTimeout'`
**Cause.** Monkey C's `method(:name)` is an *indirect* lookup; it cannot see
`private` members. This is a **warning**, and the build succeeds — the timer
callback then fails at runtime, on the watch, in front of the subject.

**Fix.** Make anything passed to `method(:...)` `public` (or `protected`).

### `Invalid explicit typing of a local variable`
**Cause.** Monkey C infers local types; `var x as Lang.Object? = list[0];` is
not allowed. Only method signatures take explicit types.

**Fix.** Don't annotate locals. If a null-check then becomes dead code and
the compiler warns `Statement is not reachable`, delete the check and use the
expression directly (the type is already non-nullable).

### `Cannot resolve type 'Boolean'`
**Cause.** `Boolean`, `Number`, `String`, `Array`, `Dictionary` live in
`Toybox.Lang`; a file that only imports `Toybox.WatchUi` cannot name them.

**Fix.** `import Toybox.Lang;`

### `Communications.transmit` arity
**Cause.** Old tutorials show `transmit(msg, listener)`. The current API is
`transmit(content, options, listener)`.

**Fix.** `Communications.transmit("photo", null, listener);`

### `registerForPhoneAppMessages` vs `setMailboxListener`
The mailbox API was deprecated in Connect IQ 4.0. Guard the call:

```monkey
if (Communications has :registerForPhoneAppMessages) {
    Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
}
```

### Manifest / resource warnings
* `<iq:product id="...">` entries must exist in the *downloaded* device
  library, otherwise: `Invalid device id found in the application manifest`.
  Only include what you actually downloaded (23 devices for the API-3.4
  group), or the warning list becomes noise.
* The launcher icon must be the device's exact size or the compiler scales
  it and warns: `The launcher icon (40x40) isn't compatible with ... (54x54)`.
  For Instinct 2S it is **54x54**.
* Enums declared at file scope are hoisted and referenced as `$.MODE_PHOTO`.

### `transmit` completing does not mean the shutter fired
`onComplete()` only says the message reached the *phone*. If the companion is
not running, the message is dropped silently and the watch would happily keep
showing "sent". **This is why the protocol has an ack**: the watch stays in
`...` and waits for `ok:`/`err:` with a 6-second timeout.

---

## Android / Connect IQ Mobile SDK for Android

### The "BLE SDK" is not a BLE SDK
Decompiling `ciq-companion-app-sdk-2.4.0.aar` shows AIDL interfaces
(`com.garmin.android.apps.connectmobile.connectiq.ICompanionAppService`) and a
`<queries>` entry for `com.garmin.android.apps.connectmobile`. It is an IPC
client for **Garmin Connect Mobile**, which owns the radio.

Consequences, all good:
* no `BLUETOOTH_*` permissions (Garmin's own sample declares none either),
* no BLE pairing code,
* no fight with Garmin Connect over the connection,
* but Garmin Connect Mobile must be installed, running and paired.

### `getKnownDevices()` succeeding means nothing
It returns paired devices whether or not they are reachable.
`registerForAppEvents()` throws `ServiceUnavailableException` for a device
that is away — and if you only register at `onSdkReady()`, the app never
hears from the watch again until it is restarted.

**Fix.** Also `registerForDeviceEvents()`, and re-subscribe on `CONNECTED`.
Keep a `Set<Long>` of subscribed device ids so a reconnect cannot double
deliver.

### Payload shape
The SDK deserializes the watch's message into `List<Object>` — always a list,
even for a single String. Assume the list, but tolerate a bare value.

### `onRequestPermissionsResult` override
In a `ComponentActivity` the platform signature is
`Array<String>`, not `Array<out String>`; with `out` Kotlin reports
`'onRequestPermissionsResult' overrides nothing`.

### Why we do not drive Samsung's camera
`ACTION_IMAGE_CAPTURE` ends in a save/discard confirmation;
`INTENT_ACTION_STILL_IMAGE_CAMERA` only *opens* the camera app; pressing
another app's shutter needs an AccessibilityService, which breaks with every
One UI release. Owning the camera with CameraX costs ~150 lines and is the
only path that is silent, instant and version-proof.

### CameraX is lifecycle-bound
Backgrounding the activity releases the camera, so triggers fail. The app is
therefore foreground-only by design (`FLAG_KEEP_SCREEN_ON`, immersive mode).
Making it work with the screen off needs a foreground service of type
`camera` plus a custom `LifecycleOwner` — deliberately not done yet.

### Toolchain versions that matter
* AGP 8.6 / Gradle 8.7 / Kotlin 1.9.20 / **JDK 17**.
  Manjaro ships JDK 26; AGP refuses it. Hence `JAVA_HOME` is exported
  explicitly in every script.
* No Android Studio and no root: `commandlinetools-linux-*.zip` into
  `~/android-sdk`, then `sdkmanager "platforms;android-35"
  "build-tools;35.0.0"`.
* A Gradle wrapper can be bootstrapped without `gradle` installed by
  fetching `gradlew`, `gradle/wrapper/gradle-wrapper.jar` and
  `gradle-wrapper.properties` from the Gradle repo tag.

---

## Garmin USB storage (the part that hurt)

The Instinct 2S does **not** use MTP: it enumerates as **USB Mass Storage**
(`bInterfaceClass 8 / SCSI / Bulk-Only`, `091e:0f31`), an 18.2 MB vfat volume
labelled `GARMIN`. Sideloading is just `cp foo.prg GARMIN/APPS/`.

### `091e:0003` instead of a filesystem
That is **Garmin mode**: the kernel binds `garmin_gps`, `/dev/ttyUSB0`
appears, and there is no volume at all.
**Fix:** Settings → System → **USB Mode → MTP** on the watch, then replug.
(The menu says MTP even though the device presents as mass storage.)

### `Error looking up object for device /dev/sda`
`udisksd` learns about devices from udev and can lose one permanently after
it wedges: the D-Bus object is dropped and never re-created on replug.
**Fix:** `sudo systemctl restart udisks2` (a `udevadm trigger --action=add
--sysname-match=sda` also works once, but the restart is the durable fix).

### A read-only backup that wedges the whole device
vfat mounted **read-write** is never really read-only: the kernel writes the
volume dirty flag on mount, and the flusher then *must* write it back. If the
device stops answering, `flush-8:0` and `usb-storage` go into `D` state —
uninterruptible, unkillable, and the mount is gone until replug.
**Fix:** back up from a **`-o ro`** mount. Only mount `rw` for the few
seconds needed to copy the `.prg`.

### Three files always fail with `Input/output error (5)`
`Totals/Totals.fit`, `TLG/TLG_KPI.bak`, `TEXT/UKRAINIA.LN4`.
Garmin deliberately makes a few internal bookkeeping files unreadable from
the host. They are not corruption.
**Fix:** `--exclude` them up front (see `scripts/backup-garmin.sh`); letting
them fail mid-rsync costs minutes of SCSI timeouts.

### The responsive window is short
In practice the volume serves I/O for roughly a minute or two, then hangs.
**Fix:** do the **write first** (a 96 KB `cp`), back up second, and never run
a long foreground transfer. One earlier attempt used `rsync` over the whole
tree and lost the device mid-run.

### `hdparm` in `D` state
KDE/Solid probes new block devices with `hdparm`; a USB mass-storage bridge
does not answer ATA passthrough, so the probe hangs and — because
`usb-storage` serialises commands — every later I/O waits behind it.
If the device hangs immediately on mount, this is a likely co-conspirator.

---

## Working on this repo (tooling notes)

* **Long commands get interrupted.** Builds, downloads and backups must run
  as background jobs; foreground commands are killed after ~30 s here.
  `./scripts/install-phone.sh` and friends are safe to run in a terminal.
* **Write source files with heredocs** (`cat > f <<'EOF'`). Saving files
  through the assistant's `save` tool collapsed long comment blocks twice,
  producing files whose first six lines were welded into one and whose
  `import` statements were swallowed by a `//` comment. The compiler caught
  it (`extraneous input 'private'`), but heredocs avoid it entirely.
* **Build early.** Three of the bugs above (the `Boolean` import, the
  private-symbol timer, unreachable code) were only found by compiling.
  Monkey C's type checker is doing real work; do not trust reading alone.
* Keep the **signing key** (`tools/devkey.der`) out of git. It is the app's
  identity: re-signing is fine, but treat it as a secret.
