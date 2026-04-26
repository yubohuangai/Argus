# Phase 2 - Toolchain and Cross-compile

You need:

- One **spare** Pixel 7 (do NOT root your production fleet).
- A Linux or WSL2 host with: `bash`, `autoconf`, `automake`, `libtool`,
  `make`, `pkg-config`.
- Android NDK r26+ (`NDK=/opt/android-ndk-r26d`).
- Source: clone of `https://github.com/ptpd/ptpd` (you already have one
  at `C:\Users\yuboh\GitHub\ptpd`; from WSL it's `/mnt/c/Users/yuboh/GitHub/ptpd`).

## Rooting

Follow Magisk's official guide for Pixel 7. Out of scope here; only
note that:

- Stock Pixel 7 boot images are needed to patch via Magisk.
- After rooting, expect `setenforce 0` may be required during the spike
  to allow ptpd to bind raw sockets.
- Document Magisk version, kernel build, and SELinux state in the
  capability report (Phase 1).

## Cross-compile

```sh
NDK=/opt/android-ndk-r26d \
PTPD_SRC=/mnt/c/Users/yuboh/GitHub/ptpd \
bash build_ptpd_android.sh
```

Output binary at `phase2/out/ptpd2`. Smoke test:

```sh
adb push out/ptpd2 /data/local/tmp/
adb shell chmod 755 /data/local/tmp/ptpd2
adb shell su -c '/data/local/tmp/ptpd2 --help | head -n 30'
```

## Stop condition

If `configure` or `make` cannot be coerced into producing an aarch64
binary even with conservative flags (`--without-pcap --disable-snmp`),
or if Android bionic is missing too many shims, fall back to
[`linuxptp`](https://github.com/nwtime/linuxptp) which is much smaller
and known to cross-compile cleanly. Document the choice in the
Phase 4 comparison report.
