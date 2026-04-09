/**
 * Copyright 2019 The Google Research Authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googleresearch.capturesync;

import android.content.Context;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.provider.Settings.Secure;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.googleresearch.capturesync.softwaresync.ClientInfo;
import com.googleresearch.capturesync.softwaresync.NetworkHelpers;
import com.googleresearch.capturesync.softwaresync.RpcCallback;
import com.googleresearch.capturesync.softwaresync.SoftwareSyncBase;
import com.googleresearch.capturesync.softwaresync.SoftwareSyncClient;
import com.googleresearch.capturesync.softwaresync.SoftwareSyncLeader;
import com.googleresearch.capturesync.softwaresync.SyncConstants;
import com.googleresearch.capturesync.softwaresync.TimeUtils;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Note : Needs Network permissions.

/** Controller managing setup and tear down the SoftwareSync object. */
public class SoftwareSyncController implements Closeable {

    private static final String TAG = "SoftwareSyncController";
    private final MainActivity context;
    private final TextView statusView;
    private final PhaseAlignController phaseAlignController;
    private boolean isLeader;
    SoftwareSyncBase softwareSync;

    /* Tell devices to save the frame at the requested trigger time. */
    public static final int METHOD_SET_TRIGGER_TIME = 200_000;

    /* Tell devices to phase align. */
    public static final int METHOD_DO_PHASE_ALIGN = 200_001;
    /* Tell devices to set manual exposure and white balance to the requested values. */
    public static final int METHOD_SET_2A = 200_002;
    public static final int METHOD_START_RECORDING = 200_003;
    public static final int METHOD_STOP_RECORDING = 200_004;

    // PATCH: collective reset
    public static final int METHOD_RESET_ALL = 200_005;

    /* Leader broadcasts the canonical SENSOR_FRAME_DURATION-derived period to all clients. */
    public static final int METHOD_BROADCAST_PERIOD = 200_006;

    /* Client reports its phase alignment terminal state to the leader. Payload: name,state,diffMs */
    public static final int METHOD_REPORT_ALIGNMENT_STATUS = 200_007;

    private long upcomingTriggerTimeNs;

    /** Last canonical period broadcast by this leader, replayed to late-joining clients. */
    private Long lastBroadcastPeriodNs = null;

    /**
     * Single-thread executor for client→leader RPC sends. The phase alignment listener fires on
     * the UI thread, and {@link SoftwareSyncClient#sendRpcToLeader} ultimately calls
     * {@link java.net.DatagramSocket#send} which throws {@code NetworkOnMainThreadException} if
     * invoked from the main thread. Hop here first.
     */
    private final ExecutorService rpcSendExecutor = Executors.newSingleThreadExecutor();

    /**
     * Per-device alignment status (display strings), keyed by device name. Populated on the
     * leader from local-leader updates and from {@link #METHOD_REPORT_ALIGNMENT_STATUS} RPCs.
     * LinkedHashMap so the leader's status reliably appears first if inserted first.
     */
    private final java.util.LinkedHashMap<String, String> alignmentStatusByName =
            new java.util.LinkedHashMap<>();

    /**
     * Constructor passed in with: - context - For setting UI elements and triggering captures. -
     * captureButton - The button used to send at trigger request by the leader. - statusView - The
     * TextView used to show currently connected clients on the leader device.
     */
    public SoftwareSyncController(
            MainActivity context, PhaseAlignController phaseAlignController, TextView statusView) {
        this.context = context;
        this.phaseAlignController = phaseAlignController;
        this.statusView = statusView;

        setupSoftwareSync();
    }

    @SuppressWarnings("StringSplitter")
    private void setupSoftwareSync() {
        Log.w(TAG, "setup SoftwareSync");
        if (softwareSync != null) {
            return;
        }

        // Get Wifi Manager and use NetworkHelpers to determine local and leader IP addresses.
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        InetAddress leaderAddress;
        InetAddress localAddress;

        // Use last 4 digits of the serial as the name of the client.
        String name = lastFourSerial();
        Log.w(TAG, "Name/Serial# (Last 4 digits): " + name);

        try {
            NetworkHelpers networkHelper = new NetworkHelpers(wifiManager);
            localAddress = NetworkHelpers.getIPAddress();
            // TODO: hotspot patch
            leaderAddress = networkHelper.getHotspotServerAddress();

            // Note: This is a brittle way of checking leadership that may not work on all devices.
            // Leader only if it is the one with same IP address as the server, or a zero IP address.
            if (localAddress.equals(leaderAddress)) {
                Log.d(TAG, "Leader == Local Address");
                isLeader = true;
            } else if (localAddress.equals(InetAddress.getByName("0.0.0.0"))) {
//              Log.d(TAG, "Leader == 0.0.0.0");
                // TODO: hotspot patch
                isLeader = true;
            }
//        isLeader = true;

            Log.w(
                    TAG,
                    String.format(
                            "Current IP: %s , Leader IP: %s | Leader? %s",
                            localAddress, leaderAddress, isLeader ? "Y" : "N"));
        } catch (UnknownHostException | SocketException e) {
            if (isLeader) {
                Log.e(TAG, "Error: " + e);
                throw new IllegalStateException(
                        "Unable to get IP addresses, check if WiFi hotspot is enabled.", e);
            } else {
                throw new IllegalStateException(
                        "Unable to get IP addresses, check Network permissions.", e);
            }
        }

        // Set up shared rpcs.
        Map<Integer, RpcCallback> sharedRpcs = new HashMap<>();
        sharedRpcs.put(
                METHOD_SET_TRIGGER_TIME,
                payload -> {
                    Log.v(TAG, "Setting next trigger to" + payload);
                    upcomingTriggerTimeNs = Long.valueOf(payload);
                    // TODO: (MROB) change to video
                    context.setUpcomingCaptureStill(upcomingTriggerTimeNs);
                });


        sharedRpcs.put(
                METHOD_DO_PHASE_ALIGN,
                payload -> {
                    // Note: One could pass the current phase of the leader and have all clients sync to
                    // that, reducing potential error, though special attention should be placed to phases
                    // close to the zero or period boundary.
                    Log.v(TAG, "Starting phase alignment.");
                    phaseAlignController.startAlign();
                });

        sharedRpcs.put(
                METHOD_BROADCAST_PERIOD,
                payload -> {
                    long periodNs = Long.parseLong(payload);
                    Log.i(TAG, "Received canonical period broadcast: " + periodNs + " ns");
                    context.runOnUiThread(() -> phaseAlignController.setPeriodNs(periodNs));
                });

        sharedRpcs.put(
                METHOD_SET_2A,
                payload -> {
                    Log.v(TAG, "Received payload: " + payload);

                    String[] segments = payload.split(",");
                    if (segments.length != 2) {
                        throw new IllegalArgumentException("Wrong number of segments in payload: " + payload);
                    }
                    long sensorExposureNs = Long.parseLong(segments[0]);
                    int sensorSensitivity = Integer.parseInt(segments[1]);
                    context.set2aAndUpdatePreview(sensorExposureNs, sensorSensitivity);
                });

        if (isLeader) {
            // Leader.
            long initTimeNs = TimeUtils.millisToNanos(System.currentTimeMillis());
            // Create rpc mapping specific to leader.
            Map<Integer, RpcCallback> leaderRpcs = new HashMap<>(sharedRpcs);
            leaderRpcs.put(
                    SyncConstants.METHOD_MSG_ADDED_CLIENT,
                    payload -> {
                        updateClientsUI();
                        // Replay the last canonical period to a late-joining client.
                        if (lastBroadcastPeriodNs != null && softwareSync instanceof SoftwareSyncLeader) {
                            ((SoftwareSyncLeader) softwareSync)
                                    .broadcastRpc(
                                            METHOD_BROADCAST_PERIOD, String.valueOf(lastBroadcastPeriodNs));
                        }
                    });
            // Leader-only: clients report their phase alignment terminal state here.
            leaderRpcs.put(
                    METHOD_REPORT_ALIGNMENT_STATUS,
                    payload -> {
                        // Payload: name,state,diffMs
                        String[] parts = payload.split(",", 3);
                        if (parts.length != 3) {
                            Log.w(TAG, "Malformed alignment status payload: " + payload);
                            return;
                        }
                        String reporterName = parts[0];
                        String state = parts[1];
                        String diffMs = parts[2];
                        alignmentStatusByName.put(
                                reporterName, formatAlignmentStatus(state, diffMs));
                        updateClientsUI();
                    });
            leaderRpcs.put(SyncConstants.METHOD_MSG_REMOVED_CLIENT, payload -> updateClientsUI());
            leaderRpcs.put(SyncConstants.METHOD_MSG_SYNCING, payload -> updateClientsUI());
            leaderRpcs.put(SyncConstants.METHOD_MSG_OFFSET_UPDATED, payload -> updateClientsUI());
            softwareSync = new SoftwareSyncLeader(name, initTimeNs, localAddress, leaderRpcs);
        } else {
            // Client.
            Map<Integer, RpcCallback> clientRpcs = new HashMap<>(sharedRpcs);
            clientRpcs.put(
                    SyncConstants.METHOD_MSG_WAITING_FOR_LEADER,
                    payload ->
                            context.runOnUiThread(
                                    () -> statusView.setText(softwareSync.getName() + ": Waiting for Leader")));
            clientRpcs.put(
                    SyncConstants.METHOD_MSG_SYNCING,
                    payload ->
                            context.runOnUiThread(
                                    () -> statusView.setText(softwareSync.getName() + ": Waiting for Sync")));

            clientRpcs.put(
                    METHOD_START_RECORDING,
                    payload -> {
                        Log.v(TAG, "Starting video");
                        context.runOnUiThread(
                                () -> context.startVideo(false)
                        );
                    });

            clientRpcs.put(
                    METHOD_STOP_RECORDING,
                    payload -> {
                        Log.v(TAG, "Stopping video");
                        context.runOnUiThread(
                                context::stopVideo
                        );
                    });

            clientRpcs.put(
                    SyncConstants.METHOD_MSG_OFFSET_UPDATED,
                    payload ->
                            context.runOnUiThread(
                                    () ->
                                            statusView.setText(
                                                    String.format(
                                                            "Client %s\n-Synced to Leader %s",
                                                            softwareSync.getName(), softwareSync.getLeaderAddress()))));


            // PATCH: collective reset
            // register client rpc method to listen to the call
            clientRpcs.put(
                    SoftwareSyncController.METHOD_RESET_ALL, // ADD THIS RPC CALLBACK
                    payload -> {
                        Log.d(TAG, "Received METHOD_RESET_ALL from leader. Restarting in 2 seconds.");
                        context.runOnUiThread(
                                () -> statusView.setText("Resetting in 2 sec...")
                        );
                        context.restartApp(2000); // 2-second delay for clients
                    });
            softwareSync = new SoftwareSyncClient(name, localAddress, leaderAddress, clientRpcs);
        }

        if (isLeader) {
            context.runOnUiThread(
                    () -> {
                        statusView.setText("Leader : " + softwareSync.getName());
                        statusView.setTextColor(Color.rgb(0, 139, 0)); // Dark green.
                    });
        } else {
            context.runOnUiThread(
                    () -> {
                        statusView.setText("Client : " + softwareSync.getName());
                        statusView.setTextColor(Color.rgb(0, 0, 139)); // Dark blue.
                    });
        }
    }

    /**
     * Show the number of connected clients on the leader status UI, with each device's
     * latest phase alignment status appended.
     */
    private void updateClientsUI() {
        SoftwareSyncLeader leader = ((SoftwareSyncLeader) softwareSync);
        final int clientCount = leader.getClients().size();
        context.runOnUiThread(
                () -> {
                    StringBuilder msg = new StringBuilder();
                    String leaderName = softwareSync.getName();
                    String leaderStatus = alignmentStatusByName.get(leaderName);
                    msg.append(
                            String.format(
                                    "Leader %s: %d clients.%s\n",
                                    leaderName,
                                    clientCount,
                                    leaderStatus == null ? "" : " | " + leaderStatus));
                    for (Entry<InetAddress, ClientInfo> entry : leader.getClients().entrySet()) {
                        ClientInfo client = entry.getValue();
                        String status = alignmentStatusByName.get(client.name());
                        String suffix = status == null ? "" : " | " + status;
                        if (client.syncAccuracy() == 0) {
                            msg.append(
                                    String.format("-Client %s: syncing...%s\n", client.name(), suffix));
                        } else {
                            msg.append(
                                    String.format(
                                            "-Client %s: %.2f ms sync%s\n",
                                            client.name(), client.syncAccuracy() / 1e6, suffix));
                        }
                    }
                    statusView.setText(msg.toString());
                });
    }

    /** Render an alignment state + phase error into a short display string. */
    private static String formatAlignmentStatus(String state, String diffMs) {
        switch (state) {
            case "ALIGNED":
                return "ALIGNED \u2713 " + diffMs + " ms";
            case "FAILED":
                return "NOT ALIGNED \u2717 " + diffMs + " ms";
            case "RUNNING":
                return "ALIGNING\u2026";
            case "IDLE":
            default:
                return "";
        }
    }

    /**
     * Called from {@link MainActivity}'s {@link PhaseAlignController.AlignmentListener} on every
     * device. On the leader, updates its own entry in the status map and refreshes the UI.
     * On a client, sends a {@link #METHOD_REPORT_ALIGNMENT_STATUS} RPC to the leader.
     */
    public void reportLocalAlignmentStatus(
            PhaseAlignController.AlignmentState state, long diffFromGoalNs) {
        String stateName = state.name();
        String diffMs = String.format(java.util.Locale.US, "%.2f",
                TimeUtils.nanosToMillis((double) diffFromGoalNs));
        if (isLeader) {
            alignmentStatusByName.put(
                    softwareSync.getName(), formatAlignmentStatus(stateName, diffMs));
            updateClientsUI();
        } else if (softwareSync instanceof SoftwareSyncClient) {
            String payload = String.format("%s,%s,%s", softwareSync.getName(), stateName, diffMs);
            // Hop off the UI thread: DatagramSocket.send throws NetworkOnMainThreadException.
            SoftwareSyncClient client = (SoftwareSyncClient) softwareSync;
            rpcSendExecutor.execute(
                    () -> {
                        try {
                            client.sendRpcToLeader(METHOD_REPORT_ALIGNMENT_STATUS, payload);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to send alignment status to leader", e);
                        }
                    });
        }
    }

    /**
     * Leader-side: pre-mark every known client (and the leader itself) as ALIGNING the moment
     * the leader broadcasts {@link #METHOD_DO_PHASE_ALIGN}, so the UI reflects the in-progress
     * state immediately rather than waiting for terminal reports to drift in.
     */
    public void markAllDevicesAligning() {
        if (!isLeader || !(softwareSync instanceof SoftwareSyncLeader)) {
            return;
        }
        SoftwareSyncLeader leader = (SoftwareSyncLeader) softwareSync;
        String aligningStr = formatAlignmentStatus("RUNNING", "");
        alignmentStatusByName.put(softwareSync.getName(), aligningStr);
        for (Entry<InetAddress, ClientInfo> entry : leader.getClients().entrySet()) {
            alignmentStatusByName.put(entry.getValue().name(), aligningStr);
        }
        updateClientsUI();
    }

    @Override
    public void close() {
        Log.w(TAG, "close SoftwareSyncController");
        rpcSendExecutor.shutdownNow();
        if (softwareSync != null) {
            try {
                softwareSync.close();
            } catch (IOException e) {
                throw new IllegalStateException("Error closing SoftwareSync", e);
            }
            softwareSync = null;
        }
    }

    private String lastFourSerial() {
        String serial = Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);
        if (serial.length() <= 4) {
            return serial;
        } else {
            return serial.substring(serial.length() - 4);
        }
    }

    public boolean isLeader() {
        return isLeader;
    }


    /**
     * Broadcasts the canonical SENSOR_FRAME_DURATION-derived period to all clients so every
     * device's {@link PhaseAlignController} computes phase in the same modular arithmetic space.
     * Stores the value so it can be replayed to late-joining clients via the MSG_ADDED_CLIENT
     * hook above. No-op on clients.
     */
    public void broadcastCanonicalPeriod(long periodNs) {
        lastBroadcastPeriodNs = periodNs;
        if (isLeader && softwareSync instanceof SoftwareSyncLeader) {
            Log.i(TAG, "Leader broadcasting canonical period: " + periodNs + " ns");
            ((SoftwareSyncLeader) softwareSync)
                    .broadcastRpc(METHOD_BROADCAST_PERIOD, String.valueOf(periodNs));
        }
    }

    // PATCH: collective reset
    /**
     * Leader broadcasts a reset command to all connected clients.
     */
    public void broadcastResetAll() {
        if (isLeader && softwareSync instanceof SoftwareSyncLeader) {
            Log.d(TAG, "Leader broadcasting METHOD_RESET_ALL to clients.");
            ((SoftwareSyncLeader) softwareSync).broadcastRpc(METHOD_RESET_ALL, "reset");
        } else {
            Log.w(TAG, "Attempted to broadcast reset from a non-leader device.");
        }
    }
}
