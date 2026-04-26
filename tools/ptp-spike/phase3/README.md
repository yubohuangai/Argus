# Phase 3 - Bring-up: 1 leader + 2 clients

## Topology

```
Pixel 7 #1 (rooted) -- hotspot AP, ptpd master, RecSync leader app
Pixel 7 #2 (rooted) -- ptpd slave-only, RecSync client app
Pixel 7 #3 (rooted) -- ptpd slave-only, RecSync client app
```

## Procedure

1. On the leader phone, start the Wi-Fi hotspot. Note its IP via:
   ```sh
   adb -s <leader-serial> shell ip -4 addr show wlan1
   ```
2. Edit `leader.conf` so `ptpengine:interface` matches what you saw.
3. On each client phone, connect to that hotspot.
4. From the workstation, in three terminals:
   ```sh
   bash run_leader.sh  <leader-serial>
   bash run_client.sh  <client1-serial>
   bash run_client.sh  <client2-serial>
   ```
5. After 60 seconds, confirm both clients have entered the SLAVE state
   in `/data/local/tmp/ptpd-client.event.log`.
6. Let the run continue for at least 10 minutes. Then pull stats:
   ```sh
   adb -s <client1-serial> pull /data/local/tmp/ptpd-client.stats.log
   ```

## Multicast fallback

Wi-Fi APs often drop multicast frames or rate-limit them. If clients
never enter SLAVE state, switch both configs to:
```
ptpengine:ip_mode = hybrid
```
or
```
ptpengine:ip_mode = unicast
ptpengine:unicast_destinations = <leader-ip>   ; on clients
ptpengine:unicast_negotiation = N
```
and re-run.

## Stop conditions

- Master never elected best master clock (BMC) -> stop, document.
- Clients never reach SLAVE state under multicast or hybrid -> stop.
- ptpd crashes / aborts on Android -> capture stderr, fall back to
  `linuxptp`/`ptp4l` (see Phase 2 README).
