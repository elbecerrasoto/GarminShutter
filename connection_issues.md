# Garmin Instinct 2S Connection Debug Log

## Summary

Successfully resolved Garmin Instinct 2S connection issues on Manjaro Linux. The device is now properly mounted and accessible for backup.

## Device Information

- **Model**: Garmin Instinct 2S
- **USB Vendor ID**: `091e`
- **USB Product ID**: `0f31` (mass storage mode)
- **Mount Point**: `/run/media/ebecerra/GARMIN`
- **Storage Size**: 19 MB (9.3 MB used, 51% utilization)
- **Filesystem**: FAT16

## The Problem

When connecting the Garmin Instinct 2S in "Mass Storage" mode, the device was not appearing in Dolphin file explorer.

### Root Cause

The `garmin_gps` kernel module was claiming the device for GPS data transfer instead of allowing the `usb-storage` module to handle mass storage mode. This caused:
- Device appearing in `lsusb` but not in `lsblk`
- Repeated USB resets and disconnections
- Device not mounting automatically

### Evidence from dmesg

```
[ 1425.614568] usb-storage 1-2:1.0: USB Mass Storage device detected
[ 1426.680426] sd 4:0:0:0: [sda] 37211 512-byte logical blocks: (19.1 MB/18.2 MiB)
[ 1426.725814] sd 4:0:0:0: [sda] Attached SCSI removable disk
```

The device was detected as mass storage, but the `garmin_gps` driver was interfering:
```
garmin_gps 1-2:1.0: Garmin GPS usb/tty converter detected
```

## Solution

The system eventually resolved itself - the `usb-storage` driver took over and the device mounted automatically at `/run/media/ebecerra/GARMIN`.

## Device Structure

```
GARMIN/
├── Activity/          # Activity data
├── Adjstmts/          # Adjustment settings
├── APPS/              # Apps
├── Benchmark/         # Benchmark data
├── Calendar/          # Calendar data
├── ChngLog/           # Change log
├── Clubs/             # Club data
├── Courses/           # Courses
├── Debug/             # Debug files
├── Device/            # Device info
├── device.fit         # Device configuration
├── EDM/               # Edge data
├── EVNTLOGS/          # Event logs
├── EXPRESS/           # Express upload data
├── EXTDATA/           # External data
├── FbtBackup/         # Firmware backup
├── FbtPtdBackup/      # Firmware backup
├── GarminDevice.xml   # Device metadata
├── Goals/             # Fitness goals
├── Golf/              # Golf data
├── HMD/               # Health monitoring data
├── HRVStatus/         # HRV status
├── HSA/               # Health status
├── Location/          # Location data
├── Metrics/           # Custom metrics
├── MltSport/          # Multi-sport data
├── Monitor/           # Monitor data
├── NewFiles/          # New files
├── PaceBands/         # Pace bands
├── PointCache/        # Point cache
├── Records/           # Personal records
├── RunningTrack/      # Running track data
├── Schedule/          # Workout schedule
├── Scorecards/        # Scorecards
├── Settings/          # Device settings
├── Sleep/             # Sleep data
├── Sports/            # Sport settings
├── Summary/           # Summary data
├── TempFIT/           # Temporary FIT files
├── TEXT/              # Text files
├── TLG/               # Training log
├── Totals/            # Totals data
└── Workouts/          # Custom workouts
```

## Commands Used for Diagnosis

```bash
# Check if device is detected
lsusb

# Check block devices
lsblk -o NAME,SIZE,TYPE,MOUNTPOINT,FSTYPE

# Check kernel messages
sudo dmesg | tail -50

# Check USB device details
ls -la /sys/bus/usb/devices/1-2/

# Check SCSI device
ls -la /sys/class/scsi_host/host4/device/target4:0:0/4:0:0:0/block/sda/

# Check filesystem
sudo fdisk -l /dev/sda

# Mount device manually (if needed)
sudo mount /dev/sda /mnt
```

## Permanent Fix (Recommended)

To prevent future connection issues, create a udev rule:

```bash
echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="091e", ATTR{idProduct}=="0f31", ENV{ID_MM_DEVICE_IGNORE}="1"' | sudo tee /etc/udev/rules.d/99-garmin-mass-storage.rules
```

Then reload udev rules:
```bash
sudo udevadm control --reload-rules && sudo udevadm trigger
```

## Alternative: Manual Driver Unbind

When the device is in mass storage mode but not working:

```bash
# Find the device path
ls /sys/bus/usb-serial/drivers/garmin_gps/

# Unbind the garmin_gps driver (replace with actual device path)
echo "1-2:1.0" | sudo tee /sys/bus/usb-serial/drivers/garmin_gps/unbind
```

## Notes

- The device uses a multi-mode protocol (GPS mode vs Mass Storage mode)
- When you select "Mass Storage" on the device, it should switch modes
- Sometimes the `garmin_gps` driver holds onto the device preventing mode switch
- The device is now accessible in Dolphin file explorer at `/run/media/ebecerra/GARMIN`
