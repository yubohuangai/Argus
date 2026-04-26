/*
 * SO_TIMESTAMPING capability probe for Android Wi-Fi.
 *
 * Opens a UDP socket, binds to the device's wlan0 IPv4 address, and
 * tries to enable each SOF_TIMESTAMPING_* flag individually via
 * setsockopt(SO_TIMESTAMPING). Prints which flags the kernel accepts.
 *
 * Build with phase1/native_test/build.sh (NDK), push to device, run as
 * shell user. Root not required for setsockopt; some flags may still
 * need CAP_NET_ADMIN to actually deliver timestamps.
 */
#include <arpa/inet.h>
#include <errno.h>
#include <ifaddrs.h>
#include <linux/net_tstamp.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

struct flag_entry {
  unsigned int bit;
  const char *name;
};

static const struct flag_entry FLAGS[] = {
    {SOF_TIMESTAMPING_TX_HARDWARE, "TX_HARDWARE"},
    {SOF_TIMESTAMPING_TX_SOFTWARE, "TX_SOFTWARE"},
    {SOF_TIMESTAMPING_RX_HARDWARE, "RX_HARDWARE"},
    {SOF_TIMESTAMPING_RX_SOFTWARE, "RX_SOFTWARE"},
    {SOF_TIMESTAMPING_SOFTWARE, "SOFTWARE"},
    {SOF_TIMESTAMPING_SYS_HARDWARE, "SYS_HARDWARE"},
    {SOF_TIMESTAMPING_RAW_HARDWARE, "RAW_HARDWARE"},
#ifdef SOF_TIMESTAMPING_OPT_TX_SWHW
    {SOF_TIMESTAMPING_OPT_TX_SWHW, "OPT_TX_SWHW"},
#endif
#ifdef SOF_TIMESTAMPING_OPT_ID
    {SOF_TIMESTAMPING_OPT_ID, "OPT_ID"},
#endif
#ifdef SOF_TIMESTAMPING_OPT_CMSG
    {SOF_TIMESTAMPING_OPT_CMSG, "OPT_CMSG"},
#endif
};

static int find_wlan0_addr(struct in_addr *out) {
  struct ifaddrs *ifa = NULL, *p;
  if (getifaddrs(&ifa) != 0) return -1;
  int found = -1;
  for (p = ifa; p; p = p->ifa_next) {
    if (!p->ifa_addr || p->ifa_addr->sa_family != AF_INET) continue;
    if (!p->ifa_name) continue;
    if (strncmp(p->ifa_name, "wlan", 4) == 0) {
      *out = ((struct sockaddr_in *)p->ifa_addr)->sin_addr;
      found = 0;
      break;
    }
  }
  freeifaddrs(ifa);
  return found;
}

static int probe_flag(struct in_addr addr, unsigned int flag) {
  int s = socket(AF_INET, SOCK_DGRAM, 0);
  if (s < 0) return -errno;
  struct sockaddr_in sa = {0};
  sa.sin_family = AF_INET;
  sa.sin_addr = addr;
  sa.sin_port = 0;
  if (bind(s, (struct sockaddr *)&sa, sizeof(sa)) != 0) {
    int e = errno;
    close(s);
    return -e;
  }
  unsigned int val = flag;
  int rc = setsockopt(s, SOL_SOCKET, SO_TIMESTAMPING, &val, sizeof(val));
  int e = (rc != 0) ? errno : 0;
  close(s);
  return rc == 0 ? 0 : -e;
}

int main(void) {
  struct in_addr addr;
  printf("# SO_TIMESTAMPING probe\n");
  if (find_wlan0_addr(&addr) != 0) {
    printf("could not find wlan* IPv4 address; is Wi-Fi up?\n");
    return 1;
  }
  char buf[INET_ADDRSTRLEN];
  inet_ntop(AF_INET, &addr, buf, sizeof(buf));
  printf("interface ip: %s\n\n", buf);
  printf("| flag | accepted | errno |\n");
  printf("|------|----------|-------|\n");
  size_t n = sizeof(FLAGS) / sizeof(FLAGS[0]);
  for (size_t i = 0; i < n; ++i) {
    int rc = probe_flag(addr, FLAGS[i].bit);
    if (rc == 0) {
      printf("| %s | yes | - |\n", FLAGS[i].name);
    } else {
      printf("| %s | no | %d (%s) |\n", FLAGS[i].name, -rc, strerror(-rc));
    }
  }
  return 0;
}
