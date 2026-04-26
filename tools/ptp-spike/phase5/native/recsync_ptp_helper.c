/*
 * Skeleton for the rooted PTP -> RecSync offset bridge.
 *
 * Build with NDK (see phase1/native_test/build.sh for a similar
 * invocation). Run as root on rooted Pixel 7 alongside ptpd2.
 *
 * Responsibilities:
 *   1. Sample ptpd offset from /data/local/tmp/ptpd-client.status
 *      (or from ptpd management messages - left as TODO).
 *   2. Translate the value into the leaderFromLocalNs convention used
 *      by SoftwareSyncBase: leader_time = local_elapsed_time - offset.
 *   3. Publish int64 BE offsets on an abstract Unix socket
 *      "@recsync-ptp" so the app's PtpOffsetSource can consume.
 *
 * This file is intentionally a sketch: do not ship without hardening
 * (file watch, error backoff, jitter rejection, etc.).
 */
#include <arpa/inet.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define ABSTRACT_NAME "recsync-ptp"

static int bind_abstract(void) {
  int s = socket(AF_UNIX, SOCK_STREAM, 0);
  if (s < 0) return -1;
  struct sockaddr_un sa = {0};
  sa.sun_family = AF_UNIX;
  // First byte 0 -> abstract namespace.
  sa.sun_path[0] = '\0';
  strncpy(sa.sun_path + 1, ABSTRACT_NAME, sizeof(sa.sun_path) - 2);
  socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(ABSTRACT_NAME));
  if (bind(s, (struct sockaddr *)&sa, len) != 0) { close(s); return -1; }
  if (listen(s, 4) != 0) { close(s); return -1; }
  return s;
}

static int read_ptpd_offset_ns(int64_t *out) {
  // TODO: parse /data/local/tmp/ptpd-client.status or attach to ptpd
  // management messages. Placeholder returns 0 (no offset).
  (void)out;
  *out = 0;
  return 0;
}

static int64_t boottime_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_BOOTTIME, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

int main(void) {
  int srv = bind_abstract();
  if (srv < 0) { perror("bind_abstract"); return 1; }

  for (;;) {
    int c = accept(srv, NULL, NULL);
    if (c < 0) {
      if (errno == EINTR) continue;
      perror("accept");
      sleep(1);
      continue;
    }
    while (1) {
      int64_t offset_ns = 0;
      if (read_ptpd_offset_ns(&offset_ns) != 0) break;
      // Network byte order int64.
      uint8_t buf[8];
      uint64_t u = (uint64_t)offset_ns;
      for (int i = 0; i < 8; ++i) buf[7 - i] = (uint8_t)(u >> (8 * i));
      ssize_t w = send(c, buf, sizeof(buf), 0);
      if (w != (ssize_t)sizeof(buf)) break;
      sleep(1);
      (void)boottime_ns;  // reserved for future drift compensation
    }
    close(c);
  }
  return 0;
}
