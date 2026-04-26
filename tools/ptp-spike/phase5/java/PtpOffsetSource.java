/*
 * Reference stub for Phase 5 of the PTP feasibility spike.
 *
 * NOT compiled into the RecSync app. Copy under
 *   app/src/main/java/com/googleresearch/capturesync/softwaresync/
 * only if Phase 4 returns Go.
 *
 * Reads big-endian int64 offset values (in nanoseconds, in the same
 * convention as SoftwareSyncBase.leaderFromLocalNs) from an abstract
 * Unix domain socket published by the rooted helper daemon.
 */
package com.googleresearch.capturesync.softwaresync;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import java.io.DataInputStream;
import java.io.IOException;

public class PtpOffsetSource implements AutoCloseable {

  private static final String TAG = "PtpOffsetSource";
  private static final String ABSTRACT_NAME = "recsync-ptp";

  private final SoftwareSyncBase base;
  private final Thread reader;
  private volatile boolean running = true;

  public PtpOffsetSource(SoftwareSyncBase base) {
    this.base = base;
    this.reader = new Thread(this::loop, "PtpOffsetSource");
    this.reader.setDaemon(true);
  }

  public void start() {
    reader.start();
  }

  private void loop() {
    while (running) {
      try (LocalSocket sock = new LocalSocket()) {
        sock.connect(new LocalSocketAddress(ABSTRACT_NAME, LocalSocketAddress.Namespace.ABSTRACT));
        DataInputStream in = new DataInputStream(sock.getInputStream());
        while (running) {
          long offsetNs = in.readLong();
          base.setLeaderFromLocalNs(offsetNs);
        }
      } catch (IOException e) {
        if (!running) return;
        Log.w(TAG, "ptp helper disconnected, retrying in 1s: " + e);
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  @Override
  public void close() {
    running = false;
    reader.interrupt();
  }
}
