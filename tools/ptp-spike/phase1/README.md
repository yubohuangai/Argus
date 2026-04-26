# Phase 1 - Capability Inventory

No root required. Run on any host with `adb` connected to a Pixel 7
that is associated to the RecSync hotspot.

## Steps

1. Run the inventory:
   ```sh
   bash check_capabilities.sh
   ```
   Outputs a Markdown report to `phase1/output/capability_<ts>.md`.

2. Build and run the `SO_TIMESTAMPING` probe:
   ```sh
   NDK=/path/to/android-ndk-r26 bash native_test/build.sh
   adb push native_test/out/timestamping_probe /data/local/tmp/
   adb shell chmod 755 /data/local/tmp/timestamping_probe
   adb shell /data/local/tmp/timestamping_probe | tee -a phase1/output/capability_<ts>.md
   ```

## Stop condition

If the report shows:

- `/sys/class/ptp/` is empty,
- the probe rejects every `SOF_TIMESTAMPING_*HARDWARE` flag with `EOPNOTSUPP` or `EINVAL`,
- and only `SOFTWARE` is accepted,

then Pixel 7 has **no Wi-Fi hardware timestamping** at the kernel level.
That alone is not a hard stop, but it caps achievable PTP accuracy on
Wi-Fi to the same software-timestamp regime as the existing SNTP path.
Decide whether to continue based on whether you expect kernel-level SW
timestamps to beat the user-space SNTP min-filter.
