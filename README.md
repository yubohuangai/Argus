## Citations and lineage

The work behind this project follows one chain:

- **[Wireless Software Synchronization of Multiple Distributed Cameras](https://arxiv.org/abs/1812.09366)** — _Sameer Ansari, Neal Wadhwa, Rahul Garg, Jiawen Chen_, ICCP **2019**. Foundational synchronization methodology.
- **[Sub-millisecond Video Synchronization of Multiple Android Smartphones](https://arxiv.org/abs/2107.00987)** — Akhmetyanov et al., **2021**. Extends the **2019** line to synchronized multi-phone Android capture.
- **This repository** — Further modified from the **2021** paper and its codebase.



## Documentation

In addition to this README, the repository ships with longer-form docs under [`docs/`](docs/):

- **[docs/REPORT.md](docs/REPORT.md)** — Detailed technical report: architecture, synchronization pipeline (SNTP + phase alignment), per-class responsibilities, and the analysis behind the current sync-accuracy results.
- **[docs/ptp_feasibility.md](docs/ptp_feasibility.md)** — Feasibility memo on whether moving from SNTP to a PTP-style protocol would help on 11 Pixel 7 phones over Wi-Fi, including the cheaper non-PTP improvements that are worth doing first.

New contributors should start with this README, then read **REPORT.md** before changing anything in `softwaresync/` or the camera/encoder pipeline.

---

## Purpose of this document

This README is written for **new team members** learning the field workflow and for **AI agents** that need a precise, ordered checklist of human-operated steps when this app is used in practice.

---

## Hardware and roles (example setup)

- **Devices:** eleven Google Pixel 7 phones, labeled **Phone 1** through **Phone 11**.
- **Primary (leader):** Phone 1 — hosts the Wi-Fi hotspot. In code, “leader” is the device whose IP matches the Wi-Fi **DHCP server address** (the hotspot host). That device shows the **fleet controls** (record, align, exposure, sensitivity, reset).
- **Clients:** Phones 2–11 — connect to Phone 1’s hotspot. They **do not** show those control buttons; they still run preview, **Calculate period**, and show status plus **Phase Error** text.

**Implementation note:** Leadership detection depends on network APIs and may be **fragile on some Android builds or Wi‑Fi setups** (`SoftwareSyncController` compares local IP to `NetworkHelpers.getHotspotServerAddress()`). The hotspot workflow on Pixel is the supported case described here.

Terminology used in the app and below (button labels match `strings.xml` / layout where applicable):

| Term | Meaning |
|------|--------|
| **Calculate period** | Estimates the camera frame period from the live preview stream. After the capture session starts, the app triggers this **automatically** (about **2 seconds** after preview comes up, then **10 seconds** of sampling — see `PeriodCalculator.CALC_DURATION_MS`). The on-screen button text is **Calculate period**. |
| **Synchronization** | Clock alignment to the leader (SNTP-style exchange). The **primary** status area lists each client and a **sync accuracy** line (shown in **ms**) once offset updates land; allow **several seconds** after each client connects. |
| **Align Phases** | Iterative phase alignment (`PhaseAlignController`). Tap the **Align Phases** button on the **primary** to broadcast alignment to **all** devices. Each screen shows **Phase Error:** … **ms**; it should **converge** to a small stable value (e.g. **~0.04 ms** is a plausible steady state; exact numbers depend on device and scene). |
| **Record video** | Only the **primary** shows the **Record video** button. Tapping it starts or stops **encoded video + CSV logging on every device** via RPC. While recording, the **leader** button shows **Recording…** with a **red** background (clients use **toasts** and do not get the red button — their record control is hidden). |
| **Reset All** | Visible on the **primary** only. The leader **broadcasts** a reset RPC to all clients (clients schedule `restartApp` after **2 seconds**), then the leader **restarts immediately**. On a fresh leader UI, the control may show **Waiting** and stay disabled for about **5 seconds** before it becomes **RESET ALL**. |

---

## Synchronized multi-phone recording — step-by-step

### 1. Start the primary (Phone 1)

1. On **Phone 1**, enable the **Wi-Fi hotspot**.
2. Open the RecSync app on Phone 1.
3. **Calculate period** should **start automatically** shortly after the camera preview is running (brief delay, then **10 seconds** of measurement). With the hotspot enabled, this phone should be detected as the **primary** (leader).

### 2. Add the first client (Phone 2)

1. On **Phone 2**, enable Wi-Fi and **connect to Phone 1’s hotspot**.
2. Open the app on Phone 2.
3. Phone 2 becomes a **client** of Phone 1.
4. **Calculate period** should start automatically on Phone 2, and **synchronization** between Phone 1 and Phone 2 should begin (allow several seconds).

### 3. Add remaining clients (Phones 3–11)

Repeat the same steps as for Phone 2 for **each** of Phones 3 through 11: connect to the primary hotspot, open the app, and wait for **calculate period** and **synchronization** to settle.

### 4. Verify the full session

When all eleven phones have the app open and connected:

- The **primary** status text lists **connected clients** and per-client **sync** lines; only the primary shows **Calculate period**, **Align Phases**, **Record video**, exposure/sensitivity sliders, and **Reset All** (after the short **Waiting** gate).
- Expect about **10 seconds** of **Calculate period** sampling per device (after the auto-trigger delay) and **several seconds** for clock sync as each client joins.

### 5. Set exposure and sensitivity

**Only the primary** has the exposure and sensitivity sliders. Adjust them so previews look correct **on every phone** — after you **release** a slider, the app **broadcasts** the new 2A values to clients (short debounce). Check each device’s preview (or rely on the shared values) so nothing is too dark or too bright before you record.

### 6. Align Phases

On the **primary**, tap **Align Phases**. On each device, watch **Phase Error:** in the status area; after a short time it should **converge** toward a **small** stable value (e.g. **~0.04 ms** is a reasonable example). Slight per-device differences are normal.

### 7. Start recording

On **Phone 1**, tap **Record video**. On the **leader**, the button should switch to **Recording…** with a **red** highlight — that confirms the **leader** started capture. **All** devices record; **clients** also show **Started recording video** as a toast (they do not share the red button UI).

### 8. Stop recording

After you finish capture (e.g. after **10 minutes**), tap **Record video** again on the **primary** to **stop** recording on all devices (clients receive a stop RPC and a **Stopped recording video** toast).

### 9. Reset all (optional — another set of recordings)

On **Phone 1**, press **Reset All** when the button is enabled (not **Waiting**). The leader **signals every client to restart** (clients delay slightly), then **restarts itself**. When the fleet is back and re-synced, repeat **steps 5–8** for **another** video set.

### 10. Outputs on each phone

For each device, each recording run typically saves:

- **Video:** under the device’s external storage, path pattern **`RecSync/VID/VID_<timestamp>.mp4`** (see `getOutputMediaFilePath()`).
- **CSV:** **`RecSync/<same_timestamp>.csv`**, written by `CSVLogger` with **per-muxed-frame** timestamp lines for that clip (paired with the encoder pipeline).

Grant **storage** access as required by your Android version; paths assume traditional shared external storage (`Environment.getExternalStorageDirectory()`).

---

## Building and installing from the command line

These commands replace the Android Studio "Run" and "Build > Generate APK" workflows. Run them from the repo root.

### Build and install on the connected phone

```bash
./gradlew installDebug
```

This compiles a debug build and installs it on every phone currently visible to `adb` (check with `adb devices`). Then unlock the phone and tap the app icon to launch it manually.

### Generate a debug APK to share with other phones

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Copy that file to the other phones (USB, cloud drive, etc.) and install it on each — for example with `adb install -r app-debug.apk` while a phone is plugged in, or by tapping the file in a file manager on the phone.

### Useful extras

- `adb devices` — list connected phones and their serials.
- `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk` — install on one specific phone when several are plugged in.
- `./gradlew clean` — wipe build artifacts if a build gets into a weird state.

---

## Post-processing (off-device; not in the app)

The application does **not** include post-processing. The steps below describe a typical **offline** workflow to interpret how synchronization relates the captures across phones.

### 11. Transfer data

Copy **all** videos and CSV timestamp files from every phone to a computer.

### 12. Extract frames with timestamps

Extract each video into **images** and **name** (or otherwise tag) each image with its **timestamp** (from the CSV or your extraction pipeline).

### 13. Match “same-time” frames across cameras

Group frames whose timestamps differ by less than a chosen **threshold** and treat those frames as **approximately simultaneous** across all phones. That yields multi-view samples suitable for downstream analysis or stitching.

### Example: external preprocessing scripts (11 phones)

One practical pipeline (outside this repo) is:

1. Run `sync.py` to match timestamps across devices and write `matched.csv`.
   - Script: `C:\Users\yuboh\GitHub\Motion-Capture\scripts\preprocess\sync.py`
   - Output (example): `C:\Users\yuboh\GitHub\Motion-Capture\output\exp\sync0403_16ms\matched.csv`
   - Configuration: **timestamp match threshold = 16 ms** (set inside `sync.py`)
2. Move or separate unmatched images:
   - Script: `C:\Users\yuboh\GitHub\Motion-Capture\scripts\preprocess\move_unmatched.py`
3. Create a quick stitched multi-view visualization for sanity checking:
   - Script: `C:\Users\yuboh\GitHub\Motion-Capture\scripts\preprocess\synctest\stitch.py`

In the 11-phone setup with **11 Pixel 7** devices, the **maximum** observed inter-device timestamp difference stays **below 17 ms** for **9-minute** recordings.
