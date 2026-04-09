/*
 * Copyright 2021 Mobile Robotics Lab. at Skoltech.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.googleresearch.capturesync;

import android.Manifest.permission;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraDevice.StateCallback;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import android.os.Process;
import android.os.Build;

import android.os.SystemClock;

import com.googleresearch.capturesync.softwaresync.CSVLogger;
import com.googleresearch.capturesync.softwaresync.SoftwareSyncLeader;
import com.googleresearch.capturesync.softwaresync.TimeUtils;
import com.googleresearch.capturesync.softwaresync.phasealign.PeriodCalculator;
import com.googleresearch.capturesync.softwaresync.phasealign.PhaseConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Main activity for the libsoftwaresync demo app using the camera 2 API.
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int STATIC_LEN = 15_000;
    /** Video resolution: QUALITY_1080P (1920×1080) or QUALITY_2160P (4K, 3840×2160) */
    private static final int VIDEO_PROFILE_QUALITY = CamcorderProfile.QUALITY_2160P;
    private static final Integer VIDEO_BITRATE_OVERRIDE = null; // Set to e.g. 30_000_000 to override.
    private static final int VIEWFINDER_MAX_WIDTH = 1920;
    private static final int VIEWFINDER_MAX_HEIGHT = 1080;
    private static final int VIEWFINDER_LAYOUT_MAX_WIDTH = 1280;
    private static final int VIEWFINDER_LAYOUT_MAX_HEIGHT = 720;
    private String lastTimeStamp;
    private PeriodCalculator periodCalculator;

    public String getLastVideoPath() {
        return lastVideoPath;
    }

    public void deleteUnusedVideo() {
        String videoPath = getLastVideoPath();
        File videoFile = new File(videoPath);
        boolean result = videoFile.delete();
        if (!result) {
            Log.d(TAG, "Video file could not be deleted");
        }
    }

    private String lastVideoPath;

    public Integer getLastVideoSeqId() {
        return lastVideoSeqId;
    }

    /**
     * Sequence id returned by {@link CameraCaptureSession#setRepeatingRequest} for the current video
     * clip. Null while not recording video CSV: CSV lines are written only when
     * {@code sequenceId == lastVideoSeqId}. Volatile so the camera thread sees the id as soon as it is
     * assigned (avoids missing the first frame vs a separate "logging enabled" flag set afterward).
     */
    private volatile Integer lastVideoSeqId;

    /** Last sensor timestamp written to the video CSV for this clip (dedupe duplicate callbacks). */
    private long lastVideoCsvSensorTimestampNs = Long.MIN_VALUE;

    /** Called when video capture sequence has ended and logger is closed. */
    public void clearVideoRecordingSequenceId() {
        lastVideoSeqId = null;
    }

    /** FIFO timestamps to pair with muxed encoder output (one CSV line per muxed sample). */
    private final LinkedBlockingQueue<Long> videoCsvTimestampQueue = new LinkedBlockingQueue<>();

    private Mp4SurfaceEncoder mp4SurfaceEncoder;

    /**
     * Enqueue a leader-time timestamp for the next muxed video frame. CSV is written from the
     * encoder callback, not here, so line count matches muxed samples.
     */
    public void offerVideoCsvTimestamp(long synchronizedTimestampNs, long unSyncTimestampNs) {
        if (mLogger == null || mLogger.isClosed() || lastVideoSeqId == null) {
            return;
        }
        if (!tryAcceptVideoCsvTimestamp(unSyncTimestampNs)) {
            return;
        }
        videoCsvTimestampQueue.offer(synchronizedTimestampNs);
    }

    /**
     * Stops muxer/codec after the capture sequence ends (same point MediaRecorder.stop() ran).
     * Blocks the camera thread until the file is finalized.
     */
    public void finishVideoRecordingFromCaptureSequence() {
        try {
            if (mp4SurfaceEncoder == null) {
                Log.e(TAG, "finishVideoRecording: encoder missing");
                if (mLogger != null) {
                    mLogger.close();
                }
                setLogger(null);
                clearVideoRecordingSequenceId();
                videoCsvTimestampQueue.clear();
                return;
            }
            mp4SurfaceEncoder.stopAndRelease(
                    () -> {
                        if (isVideoRecording()) {
                            setVideoRecording(false);
                        } else {
                            deleteUnusedVideo();
                        }
                        if (mLogger != null) {
                            mLogger.close();
                        }
                        setLogger(null);
                        clearVideoRecordingSequenceId();
                        int leftover = videoCsvTimestampQueue.size();
                        if (leftover > 0) {
                            Log.w(
                                    TAG,
                                    leftover
                                            + " camera timestamps had no muxed sample (encoder"
                                            + " drops); CSV lines match MP4 frames only");
                        }
                        videoCsvTimestampQueue.clear();
                    });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "finishVideoRecording interrupted", e);
        }
    }

    /**
     * @return true if this timestamp should be logged (not a duplicate of the previous line).
     */
    public boolean tryAcceptVideoCsvTimestamp(long sensorTimestampNs) {
        if (sensorTimestampNs == lastVideoCsvSensorTimestampNs) {
            return false;
        }
        lastVideoCsvSensorTimestampNs = sensorTimestampNs;
        return true;
    }

    public int getCurSequence() {
        return curSequence;
    }

    public void setLogger(CSVLogger mLogger) {
        this.mLogger = mLogger;
    }

    public CSVLogger getLogger() {
        return mLogger;
    }

    private CSVLogger mLogger;

    private int curSequence;

    private static final String SUBDIR_NAME = "RecSync";

    private boolean permissionsGranted = false;

    // Phase config file to use for phase alignment, configs are located in the raw folder.
    private final int phaseConfigFile = R.raw.default_phaseconfig;

    private boolean isVideoRecording = false;

    // Camera controls.
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Handler send2aHandler;
    private CameraManager cameraManager;

    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCharacteristics cameraCharacteristics;

    // Cached camera characteristics.
    private Size viewfinderResolution;
    private Size rawImageResolution;
    private Size yuvImageResolution;
    /** Resolved video profile (with fallback if 4K unsupported on device). */
    private int resolvedVideoProfileQuality;

    // Top level UI windows.
    private int lastOrientation = Configuration.ORIENTATION_UNDEFINED;

    // UI controls.
    private Button captureStillButton;
    private Button getPeriodButton;
    private Button phaseAlignButton;
    private SeekBar exposureSeekBar;
    private SeekBar sensitivitySeekBar;
    private TextView statusTextView;
    private TextView sensorExposureTextView;
    private TextView sensorSensitivityTextView;
    private TextView softwaresyncStatusTextView;
    private TextView phaseTextView;

    // PATCH: collective reset
    private Button resetAllButton;
    // Add a Handler as a class member for managing UI delays
    private Handler uiHandler = new Handler(Looper.getMainLooper());

    // Local variables tracking current manual exposure and sensitivity values.
    private long currentSensorExposureTimeNs = seekBarValueToExposureNs(10);
    private int currentSensorSensitivity = seekBarValueToSensitivity(3);

    // High level camera controls.
    private CameraController cameraController;
    private CameraCaptureSession captureSession;

    /**
     * Manages SoftwareSync setup/teardown. Since softwaresync should only run when the camera is
     * running, it is instantiated in openCamera() and closed inside closeCamera().
     */
    private SoftwareSyncController softwareSyncController;

    private AutoFitSurfaceView surfaceView;

    private final SurfaceHolder.Callback surfaceCallback =
            new SurfaceHolder.Callback() {

                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    Log.i(TAG, "Surface created.");
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    Log.i(TAG, "Surface changed.");
                    viewfinderSurface = holder.getSurface();
                    openCamera();
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    Log.i(TAG, "destroyed.");
                }
            };
    private Surface viewfinderSurface;
    private PhaseAlignController phaseAlignController;
    private int numCaptures;
    private Toast latestToast;
    private Surface surface;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v(TAG, "onCreate");
        periodCalculator = new PeriodCalculator();
        checkPermissions();
        if (permissionsGranted) {
            onCreateWithPermission();
        } else {
            // Wait for user to finish permissions before setting up the app.
        }
    }

    private void onCreateWithPermission() {
        setContentView(R.layout.activity_main);
        send2aHandler = new Handler();

        createUi();
        setupPhaseAlignController();

        // Query for camera characteristics and cache them.
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cacheCameraCharacteristics();
        } catch (CameraAccessException e) {
            Toast.makeText(this, R.string.error_msg_cant_open_camera2, Toast.LENGTH_LONG).show();
            Log.e(TAG, String.valueOf(R.string.error_msg_cant_open_camera2));
            finish();
        }

        // Set the aspect ratio now that we know the viewfinder resolution.
        surfaceView.setAspectRatio(viewfinderResolution.getWidth(), viewfinderResolution.getHeight());

        // Process the initial configuration (for i.e. initial orientation)
        // We need this because #onConfigurationChanged doesn't get called when
        // the app launches
        maybeUpdateConfiguration(getResources().getConfiguration());
    }

    private void setupPhaseAlignController() {
        // Set up phase aligner.
        PhaseConfig phaseConfig;
        try {
            phaseConfig = loadPhaseConfigFile();
        } catch (JSONException e) {
            throw new IllegalArgumentException("Error reading JSON file: ", e);
        }
        phaseAlignController = new PhaseAlignController(phaseConfig, this);
    }

    /**
     * Called when "configuration" changes, as defined in the manifest. In our case, when the
     * orientation changes, screen size changes, or keyboard is hidden.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        maybeUpdateConfiguration(getResources().getConfiguration());
    }

    private void maybeUpdateConfiguration(Configuration newConfig) {
        if (lastOrientation != newConfig.orientation) {
            lastOrientation = newConfig.orientation;
            updateViewfinderLayoutParams();
        }
    }

    /**
     * Resize the SurfaceView to be centered on screen.
     */
    private void updateViewfinderLayoutParams() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();

        // displaySize is set by the OS: it's how big the display is.
        Point displaySize = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(displaySize);
        Log.i(TAG, String.format("display resized, now %d x %d", displaySize.x, displaySize.y));

        // Fit an image inside a rectangle maximizing the resulting area and centering (coordinates are
        // rounded down).
        int maxWidth = Math.min(displaySize.x, VIEWFINDER_LAYOUT_MAX_WIDTH);
        int maxHeight = Math.min(displaySize.y, VIEWFINDER_LAYOUT_MAX_HEIGHT);
        params.width =
                Math.min(
                        maxWidth,
                        maxHeight * viewfinderResolution.getWidth() / viewfinderResolution.getHeight());
        params.height =
                Math.min(
                        maxWidth * viewfinderResolution.getHeight() / viewfinderResolution.getWidth(),
                        maxHeight);
        params.gravity = Gravity.CENTER;

        surfaceView.setLayoutParams(params);
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume");
        super.onResume(); // Required.

        surfaceView
                .getHolder()
                .setFixedSize(viewfinderResolution.getWidth(), viewfinderResolution.getHeight());
        surfaceView.getHolder().addCallback(surfaceCallback);
        Log.d(TAG, "Surfaceview size: " + surfaceView.getWidth() + ", " + surfaceView.getHeight());
        surfaceView.setVisibility(View.VISIBLE);

        startCameraThread();
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        closeCamera();
        stopCameraThread();
        // Make the SurfaceView GONE so that on resume, surfaceCreated() is called,
        // and on pause, surfaceDestroyed() is called.
        surfaceView.getHolder().removeCallback(surfaceCallback);
        surfaceView.setVisibility(View.GONE);

        super.onPause(); // required
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        cameraThread.quitSafely();
        try {
            cameraThread.join();
            cameraThread = null;
            cameraHandler = null;
        } catch (InterruptedException e) {
            Log.e(TAG, "Failed to stop camera thread", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            Log.d(TAG, "resumeCamera");

            StateCallback cameraStateCallback =
                    new StateCallback() {
                        @Override
                        public void onOpened(CameraDevice openedCameraDevice) {
                            cameraDevice = openedCameraDevice;
                            startSoftwareSync();
                            initCameraController();
                            configureCaptureSession(); // calls startPreview();
                        }

                        @Override
                        public void onDisconnected(CameraDevice cameraDevice) {
                        }

                        @Override
                        public void onError(CameraDevice cameraDevice, int i) {
                        }
                    };
            cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler); // Starts chain.

        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot open camera!", e);
            finish();
        }
    }

    public void onTimestampNs(long timestampNs) {
        periodCalculator.onFrameTimestamp(timestampNs);
    }

    /* Set up UI controls and listeners based on if device is currently a leader of client. */
    private void setLeaderClientControls(boolean isLeader) {
        getPeriodButton.setOnClickListener(
                view -> {
                    Log.d(TAG, "Calculating frames period.");
                    // PATCH:
                    // when pressed, set the button color and text
                    getPeriodButton.setText("Calculating period...");
                    getPeriodButton.setEnabled(false);
                    getPeriodButton.setTextColor(Color.BLACK);
//
//                    FutureTask<Integer> periodTask = new FutureTask<Integer>(
//                            () -> {
//                                try {
//                                    long periodNs = periodCalculator.getPeriodNs();
//                                    Log.d(TAG, "Calculated period: " + periodNs);
//                                    if (latestToast != null) {
//                                        latestToast.cancel();
//                                    }
//                                    latestToast =
//                                            Toast.makeText(
//                                                    this,
//                                                    "Calculated period: " + periodNs,
//                                                    Toast.LENGTH_LONG);
//                                    latestToast.show();
//                                    phaseAlignController.setPeriodNs(periodNs);
//
//                                } catch (InterruptedException e) {
//                                    Log.d(TAG, "Failed calculating period");
//                                    e.printStackTrace();
//                                }
//
//                                return 0;
//                            }
//                    );
//                    periodTask.run();

                    // PATCH:
                    // run period calculation in background thread
                    new Thread(() -> {
                        try {
                            // This part now runs in the background and does not block the UI.
                            long periodNs = periodCalculator.getPeriodNs();
                            Log.d(TAG, "Calculated period: " + periodNs);
                            Log.i(TAG, String.format("PeriodCalculator result: %d ns", periodNs));

                            // --- Step 3: Post the final UI update back to the main thread ---
                            runOnUiThread(() -> {
                                // This code will be executed on the UI thread.

                                phaseAlignController.setPeriodNs(periodNs);

                                // Revert the button's appearance to the "finished" state
                                getPeriodButton.setEnabled(true);
                                getPeriodButton.setTextColor(Color.parseColor("#006400"));
                                getPeriodButton.setText("Period: " + periodNs + " ns");
                            });

                        } catch (InterruptedException e) {
                            Log.d(TAG, "Failed calculating period");
                            e.printStackTrace();
                        }
                    }).start(); // Don't forget to start the thread!
                }
        );

        if (isLeader) {
            // Leader, all controls visible and set.
            captureStillButton.setVisibility(View.VISIBLE);
            phaseAlignButton.setVisibility(View.VISIBLE);
            getPeriodButton.setVisibility(View.VISIBLE);
            exposureSeekBar.setVisibility(View.VISIBLE);
            sensitivitySeekBar.setVisibility(View.VISIBLE);

            // PATCH: collective reset
            // leader device
            // Make Reset All button visible for leader and set its listener
            resetAllButton.setVisibility(View.VISIBLE);
            resetAllButton.setEnabled(false);
            resetAllButton.setTextColor(Color.GRAY);
            resetAllButton.setText("Waiting");

            // Schedule a task to re-enable the button after 5 seconds.
            uiHandler.postDelayed(() -> {
                if (resetAllButton != null) {
                    resetAllButton.setEnabled(true);
                    resetAllButton.setText("RESET ALL");
                    resetAllButton.setTextColor(Color.RED);
                }
            }, 5000);
            resetAllButton.setOnClickListener(v -> {
                Log.d(TAG, "Leader 'Reset All' button pressed. Broadcasting reset and restarting.");

                // Add a cooldown to prevent rapid-fire clicks
                resetAllButton.setEnabled(false);
                resetAllButton.setText("Resetting...");

                uiHandler.postDelayed(() -> {
                    if (resetAllButton != null) {
                        resetAllButton.setEnabled(true);
                        resetAllButton.setText("Reset All");
                    }
                }, 7000); // 7 seconds cooldown

                // 1. Broadcast reset to all clients
                softwareSyncController.broadcastResetAll();
                // 2. Restart leader app
                restartApp(0);
            });



            captureStillButton.setOnClickListener(
                    view -> {
                        if (isVideoRecording) {
                            stopVideo();
                            ((SoftwareSyncLeader) softwareSyncController.softwareSync)
                                    .broadcastRpc(
                                            SoftwareSyncController.METHOD_STOP_RECORDING,
                                            "0");

                            // PATCH:
                            // Set capture button color when recording
                            captureStillButton.setText("RECORD VIDEO"); // Revert text
                            captureStillButton.getBackground().setColorFilter(null); // Remove red color
                            captureStillButton.setTextColor(Color.BLACK);

                        } else {
                            startVideo(false);
                            ((SoftwareSyncLeader) softwareSyncController.softwareSync)
                                    .broadcastRpc(
                                            SoftwareSyncController.METHOD_START_RECORDING,
                                            "0");
                            // PATCH:
                            // Revert capture button color when stopped recording
                            captureStillButton.setText("Recording..."); // Set new text
                            // Make the button's background red
                            captureStillButton.getBackground().setColorFilter(
                                    Color.RED, PorterDuff.Mode.MULTIPLY);
                            captureStillButton.setTextColor(Color.WHITE);
                            // Note: The isVideoRecording flag is set to
                        }

/*            if (cameraController.getOutputSurfaces().isEmpty()) {
              Log.e(TAG, "No output surfaces found.");
              Toast.makeText(this, R.string.error_msg_no_outputs, Toast.LENGTH_LONG).show();
              return;
            }

            long currentTimestamp = softwareSyncController.softwareSync.getLeaderTimeNs();
            // Trigger request some time in the future (~500ms for example) so all devices have time
            // to receive the request (delayed due to network latency) and prepare for triggering.
            // Note: If the user keeps a running circular buffer of images, they can select frames
            // in the near past as well, allowing for 'instantaneous' captures on all devices.
            long futureTimestamp = currentTimestamp + Constants.FUTURE_TRIGGER_DELAY_NS;

            Log.d(
                TAG,
                String.format(
                    "Trigger button, sending timestamp %,d at %,d",
                    futureTimestamp, currentTimestamp));

            // Broadcast desired synchronized capture time to all devices.
            ((SoftwareSyncLeader) softwareSyncController.softwareSync)
                .broadcastRpc(
                    SoftwareSyncController.METHOD_SET_TRIGGER_TIME,
                    String.valueOf(futureTimestamp));*/
                    });

            phaseAlignButton.setOnClickListener(
                    view -> {
                        Log.d(TAG, "Broadcasting phase alignment request.");
                        // Optimistically show RUNNING in case this device is also a participant.
                        updateAlignButtonState(PhaseAlignController.AlignmentState.RUNNING);
                        // Request phase alignment on all devices.
                        ((SoftwareSyncLeader) softwareSyncController.softwareSync)
                                .broadcastRpc(SoftwareSyncController.METHOD_DO_PHASE_ALIGN, "");
                    });

            // Reflect the phase alignment loop's terminal state on the button.
            phaseAlignController.setAlignmentListener(this::updateAlignButtonState);
            updateAlignButtonState(PhaseAlignController.AlignmentState.IDLE);

            exposureSeekBar.setOnSeekBarChangeListener(
                    new OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                            currentSensorExposureTimeNs = seekBarValueToExposureNs(value);
                            sensorExposureTextView.setText(
                                    "Exposure: " + prettyExposureValue(currentSensorExposureTimeNs));
                            Log.i(
                                    TAG,
                                    "Exposure Seekbar "
                                            + value
                                            + " to set exposure "
                                            + currentSensorExposureTimeNs
                                            + " : "
                                            + prettyExposureValue(currentSensorExposureTimeNs));
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar seekBar) {
                        }

                        @Override
                        public void onStopTrackingTouch(SeekBar seekBar) {
                            // Do it immediately on leader for immediate feedback, but doesn't update clients
                            // without
                            // clicking the 2A button.
                            startPreview();
                            scheduleBroadcast2a();
                        }
                    });

            sensitivitySeekBar.setOnSeekBarChangeListener(
                    new OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                            currentSensorSensitivity = seekBarValueToSensitivity(value);
                            sensorSensitivityTextView.setText("Sensitivity: " + currentSensorSensitivity);
                            Log.i(
                                    TAG,
                                    "Sensitivity Seekbar "
                                            + value
                                            + " to set sensitivity "
                                            + currentSensorSensitivity);
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar seekBar) {
                        }

                        @Override
                        public void onStopTrackingTouch(SeekBar seekBar) {
                            // Do it immediately on leader for immediate feedback, but doesn't update clients
                            // without
                            // clicking the 2A button.
                            startPreview();
                            scheduleBroadcast2a();
                        }
                    });
        } else {
            // Client. All controls invisible.
            captureStillButton.setVisibility(View.INVISIBLE);
            phaseAlignButton.setVisibility(View.INVISIBLE);
            getPeriodButton.setVisibility(View.VISIBLE);
            exposureSeekBar.setVisibility(View.INVISIBLE);
            sensitivitySeekBar.setVisibility(View.INVISIBLE);

            // PATCH: collective reset
            // Client device don't have this btn
            resetAllButton.setVisibility(View.GONE);
            resetAllButton.setOnClickListener(null);


            captureStillButton.setOnClickListener(null);
            phaseAlignButton.setOnClickListener(null);
            exposureSeekBar.setOnSeekBarChangeListener(null);
            sensitivitySeekBar.setOnSeekBarChangeListener(null);
        }
    }

    private void startSoftwareSync() {
        // Start softwaresync, close it first if it's already running.
        if (softwareSyncController != null) {
            softwareSyncController.close();
            softwareSyncController = null;
        }
        try {
            softwareSyncController =
                    new SoftwareSyncController(this, phaseAlignController, softwaresyncStatusTextView);
            setLeaderClientControls(softwareSyncController.isLeader());
        } catch (IllegalStateException e) {
            // If wifi is disabled, start pick wifi activity.
            Log.e(
                    TAG,
                    "Couldn't start SoftwareSync due to " + e + ", requesting user pick a wifi network.");
            finish(); // Close current app, expect user to restart.
            startActivity(new Intent(WifiManager.ACTION_PICK_WIFI_NETWORK));
        }
    }

    private PhaseConfig loadPhaseConfigFile() throws JSONException {
        // Load phase config file and pass to phase aligner.

        JSONObject json;
        try {
            InputStream inputStream = getResources().openRawResource(phaseConfigFile);
            byte[] buffer = new byte[inputStream.available()];
            //noinspection ResultOfMethodCallIgnored
            inputStream.read(buffer);
            inputStream.close();
            json = new JSONObject(new String(buffer, StandardCharsets.UTF_8));
        } catch (JSONException | IOException e) {
            throw new IllegalArgumentException("Error reading JSON file: ", e);
        }
        return PhaseConfig.parseFromJSON(json);
    }

    private void closeCamera() {
        stopPreview();
        captureSession = null;
        surface.release();
        if (cameraController != null) {
            cameraController.close();
            cameraController = null;
        }

        if (cameraDevice != null) {
            Log.d(TAG, "Closing camera...");
            cameraDevice.close();
            Log.d(TAG, "Camera closed.");
        }

        // Close softwaresync whenever camera is stopped.
        if (softwareSyncController != null) {
            softwareSyncController.close();
            softwareSyncController = null;
        }
    }

    /**
     * Gathers useful camera characteristics like available resolutions and cache them so we don't
     * have to query the CameraCharacteristics struct again.
     */
    private void cacheCameraCharacteristics() throws CameraAccessException {
        cameraId = null;
        for (String id : cameraManager.getCameraIdList()) {
            if (cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                    == Constants.DEFAULT_CAMERA_FACING) {
                cameraId = id;
                break;
            }
        }
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);

        // Resolve video profile with fallback for devices that don't support 4K
        int cameraIdInt = Integer.parseInt(cameraId);
        if (VIDEO_PROFILE_QUALITY == CamcorderProfile.QUALITY_2160P
                && CamcorderProfile.hasProfile(cameraIdInt, CamcorderProfile.QUALITY_2160P)) {
            resolvedVideoProfileQuality = CamcorderProfile.QUALITY_2160P;
        } else if (VIDEO_PROFILE_QUALITY == CamcorderProfile.QUALITY_2160P) {
            resolvedVideoProfileQuality = CamcorderProfile.QUALITY_1080P;
            Log.w(TAG, "4K (QUALITY_2160P) not supported on this camera, falling back to 1080p");
        } else {
            resolvedVideoProfileQuality = VIDEO_PROFILE_QUALITY;
        }

        StreamConfigurationMap scm =
                cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        // We always capture the viewfinder. Its resolution is special: it's set chosen in Constants.
        // Use 1080p viewfinder when recording 4K to reduce CPU/GPU load (display isn't 4K anyway).
        CamcorderProfile videoProfile = CamcorderProfile.get(resolvedVideoProfileQuality);
        boolean matchViewfinderToVideo = (resolvedVideoProfileQuality != CamcorderProfile.QUALITY_2160P);
        int maxViewfinderWidth = matchViewfinderToVideo ? videoProfile.videoFrameWidth : VIEWFINDER_MAX_WIDTH;
        int maxViewfinderHeight = matchViewfinderToVideo ? videoProfile.videoFrameHeight : VIEWFINDER_MAX_HEIGHT;
        List<Size> viewfinderOutputSizes = Arrays.stream(scm.getOutputSizes(SurfaceTexture.class))
                .filter(
                        size -> size.getWidth() <= maxViewfinderWidth && size.getHeight() <= maxViewfinderHeight
                )
                .collect(Collectors.toList());
        if (viewfinderOutputSizes.size() != 0) {
            Log.i(TAG, "Available viewfinder resolutions:");
            for (Size s : viewfinderOutputSizes) {
                Log.i(TAG, s.toString());
            }
        } else {
            throw new IllegalStateException("Viewfinder unavailable!");
        }
        if (matchViewfinderToVideo) {
            viewfinderResolution =
                    viewfinderOutputSizes.stream()
                            .filter(size -> size.getWidth() == videoProfile.videoFrameWidth
                                    && size.getHeight() == videoProfile.videoFrameHeight)
                            .findFirst()
                            .orElse(Collections.max(viewfinderOutputSizes, new CompareSizesByArea()));
        } else {
            viewfinderResolution =
                    Collections.max(viewfinderOutputSizes, new CompareSizesByArea());
        }

        Size[] rawOutputSizes = scm.getOutputSizes(ImageFormat.RAW10);
//    if (rawOutputSizes != null) {
//      Log.i(TAG, "Available Bayer RAW resolutions:");
//      for (Size s : rawOutputSizes) {
//        Log.i(TAG, s.toString());
//      }
//    } else {
//      Log.i(TAG, "Bayer RAW unavailable!");
//    }
//    rawImageResolution = Collections.max(Arrays.asList(rawOutputSizes), new CompareSizesByArea());

        if (Constants.SAVE_YUV) {
            CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
            List<Size> yuvOutputSizes = Arrays.stream(scm.getOutputSizes(ImageFormat.YUV_420_888)).filter(
                    size -> size.getHeight() <= profile.videoFrameHeight && size.getWidth() <= profile.videoFrameWidth
            ).collect(Collectors.toList());
            if (yuvOutputSizes.size() != 0) {
                Log.i(TAG, "Available YUV resolutions:");
                for (Size s : yuvOutputSizes) {
                    Log.i(TAG, s.toString());
                }
            } else {
                Log.i(TAG, "YUV unavailable!");
            }
            yuvImageResolution = Collections.max(yuvOutputSizes, new CompareSizesByArea());
            Log.i(TAG, "Chosen yuv resolution: " + yuvImageResolution);
        } else {
            yuvImageResolution = null;
            Log.i(TAG, "YUV stream disabled (MP4-only mode)");
        }
        Log.i(TAG, "Video recording: " + videoProfile.videoFrameWidth + "x" + videoProfile.videoFrameHeight
                + " (viewfinder: " + viewfinderResolution + ")");
//    Log.i(TAG, "Chosen raw resolution: " + rawImageResolution);
    }

    public void setUpcomingCaptureStill(long upcomingTriggerTimeNs) {
        cameraController.setUpcomingCaptureStill(upcomingTriggerTimeNs);
        double timeTillSec =
                TimeUtils.nanosToSeconds(
                        (double)
                                (upcomingTriggerTimeNs - softwareSyncController.softwareSync.getLeaderTimeNs()));
        runOnUiThread(
                () -> {
                    if (latestToast != null) {
                        latestToast.cancel();
                    }
                    latestToast =
                            Toast.makeText(
                                    this,
                                    String.format("Capturing in %.2f seconds", timeTillSec),
                                    Toast.LENGTH_SHORT);
                    latestToast.show();
                });
    }

    public void notifyCapturing(String name) {
        runOnUiThread(
                () -> {
                    if (latestToast != null) {
                        latestToast.cancel();
                    }
                    latestToast = Toast.makeText(this, "Capturing " + name + "...", Toast.LENGTH_SHORT);
                    latestToast.show();
                });
    }

    public void notifyCaptured(String name) {
        numCaptures++;
        runOnUiThread(
                () -> {
                    if (latestToast != null) {
                        latestToast.cancel();
                    }
                    latestToast = Toast.makeText(this, "Captured " + name, Toast.LENGTH_LONG);
                    latestToast.show();
                    statusTextView.setText(String.format("%d captures", numCaptures));
                });
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    public void injectFrame(long desiredExposureTimeNs) {
        try {
            CaptureRequest.Builder builder =
                    cameraController
                            .getRequestFactory()
                            .makeFrameInjectionRequest(
                                    desiredExposureTimeNs,
                                    cameraController.getOutputSurfaces(),
                                    viewfinderSurface);
            captureSession.capture(
                    builder.build(), cameraController.getSynchronizerCaptureCallback(), cameraHandler);
        } catch (CameraAccessException e) {
            throw new IllegalStateException("Camera capture failure during frame injection.", e);
        }
    }

    private void createUi() {
        Window appWindow = getWindow();
        appWindow.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // Disable sleep / screen off.
        appWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Create the SurfaceView.
        surfaceView = findViewById(R.id.viewfinder_surface_view);

        // TextViews.
        statusTextView = findViewById(R.id.status_text);
        softwaresyncStatusTextView = findViewById(R.id.softwaresync_text);
        sensorExposureTextView = findViewById(R.id.sensor_exposure);
        sensorSensitivityTextView = findViewById(R.id.sensor_sensitivity);
        phaseTextView = findViewById(R.id.phase);

        // Controls.
        captureStillButton = findViewById(R.id.capture_still_button);
        phaseAlignButton = findViewById(R.id.phase_align_button);
        getPeriodButton = findViewById(R.id.get_period_button);

        // PATCH: collective reset
        resetAllButton = findViewById(R.id.reset_all_button);

        exposureSeekBar = findViewById(R.id.exposure_seekbar);
        sensitivitySeekBar = findViewById(R.id.sensitivity_seekbar);
        sensorExposureTextView.setText("Exposure: " + prettyExposureValue(currentSensorExposureTimeNs));
        sensorSensitivityTextView.setText("Sensitivity: " + currentSensorSensitivity);
    }

    private void scheduleBroadcast2a() {
        send2aHandler.removeCallbacks(null); // Replace delayed callback with latest 2a values.
        send2aHandler.postDelayed(
                () -> {
                    Log.d(TAG, "Broadcasting current 2A values.");
                    String payload =
                            String.format("%d,%d", currentSensorExposureTimeNs, currentSensorSensitivity);
                    // Send 2A values to all devices
                    ((SoftwareSyncLeader) softwareSyncController.softwareSync)
                            .broadcastRpc(SoftwareSyncController.METHOD_SET_2A, payload);
                },
                500);
    }

    void set2aAndUpdatePreview(long sensorExposureTimeNs, int sensorSensitivity) {
        currentSensorExposureTimeNs = sensorExposureTimeNs;
        currentSensorSensitivity = sensorSensitivity;
        sensorExposureTextView.setText("Exposure: " + prettyExposureValue(currentSensorExposureTimeNs));
        sensorSensitivityTextView.setText("Sensitivity: " + currentSensorSensitivity);
        Log.i(
                TAG,
                String.format(
                        " Updating 2A to Exposure %d (%s), Sensitivity %d",
                        currentSensorExposureTimeNs,
                        prettyExposureValue(currentSensorExposureTimeNs),
                        currentSensorSensitivity));
        startPreview();
    }

    void updatePhaseTextView(long phaseErrorNs) {
        phaseTextView.setText(
                String.format("Phase Error: %.2f ms", TimeUtils.nanosToMillis((double) phaseErrorNs)));
    }

    /** Reflect the phase alignment loop's terminal state on the Align Phases button. */
    void updateAlignButtonState(PhaseAlignController.AlignmentState state) {
        if (phaseAlignButton == null) {
            return;
        }
        switch (state) {
            case RUNNING:
                phaseAlignButton.setEnabled(false);
                phaseAlignButton.setText("ALIGNING...");
                phaseAlignButton.setTextColor(Color.BLACK);
                break;
            case ALIGNED:
                phaseAlignButton.setEnabled(true);
                phaseAlignButton.setText("ALIGNED \u2713");
                phaseAlignButton.setTextColor(Color.parseColor("#006400"));
                break;
            case FAILED:
                phaseAlignButton.setEnabled(true);
                phaseAlignButton.setText("NOT ALIGNED \u2717");
                phaseAlignButton.setTextColor(Color.RED);
                break;
            case IDLE:
            default:
                phaseAlignButton.setEnabled(true);
                phaseAlignButton.setText("ALIGN PHASES");
                phaseAlignButton.setTextColor(Color.BLACK);
                break;
        }
    }

    private long seekBarValueToExposureNs(int value) {
        // Convert 0-10 values ranging from 1/32 to 1/16,000 of a second.
        int[] steps = {32, 60, 125, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000};
        int denominator = steps[10 - value];
        double exposureSec = 1. / denominator;
        return (long) (exposureSec * 1_000_000_000);
    }

    private String prettyExposureValue(long exposureNs) {
        return String.format("1/%.0f", 1. / TimeUtils.nanosToSeconds((double) exposureNs));
    }

    private int seekBarValueToSensitivity(int value) {
        // Convert 0-10 values to 0-800 sensor sensitivities.
        return (value * 800) / 10;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (Constants.USE_FULL_SCREEN_IMMERSIVE) {
                findViewById(android.R.id.content)
                        .setSystemUiVisibility(
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }
    }

    /**
     * Create {@link #cameraController}, and subscribe to status change events.
     */
    private void initCameraController() {
        cameraController =
                new CameraController(
                        cameraCharacteristics,
                        Constants.SAVE_RAW ? rawImageResolution : null,
                        Constants.SAVE_YUV ? yuvImageResolution : null,
                        phaseAlignController,
                        this,
                        softwareSyncController.softwareSync);
    }

    private void configureCaptureSession() {
        Log.d(TAG, "Creating capture session.");

        List<Surface> outputSurfaces = new ArrayList<>();
        Log.d(TAG, "Surfaceview size: " + surfaceView.getWidth() + ", " + surfaceView.getHeight());
        Log.d(TAG, "viewfinderSurface valid? " + viewfinderSurface.isValid());
        outputSurfaces.add(viewfinderSurface);

        // MROB. Added MediaRecorder surface
        try {
            createRecorderSurface();
            outputSurfaces.add(surface);
        } catch (IOException e) {
            e.printStackTrace();
        }
        outputSurfaces.addAll(cameraController.getOutputSurfaces());
        if (cameraController.getOutputSurfaces().isEmpty()) {
            Log.i(TAG, "No ImageReader surfaces (video + viewfinder only).");
        }

        Log.d(TAG, "Outputs " + cameraController.getOutputSurfaces());

        try {
            CameraCaptureSession.StateCallback sessionCallback =
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            Log.d(TAG, "camera capture configured.");
                            captureSession = cameraCaptureSession;
                            cameraController.configure(cameraDevice); // pass in device.
                            startPreview();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                            Log.d(TAG, "camera capture configure failed.");
                        }
                    };
            cameraDevice.createCaptureSession(outputSurfaces, sessionCallback, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Unable to reconfigure capture request", e);
        }

        // PATCH:
        // Auto calculate period after preview starts with 2 sec delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (getPeriodButton != null) {
                Log.d(TAG, "Preview started, automatically calculating period.");
                getPeriodButton.performClick();
            }
        }, 2000);

    }

    private void startPreview(boolean wantAutoExp) {
        Log.d(TAG, "Starting preview.");

        try {
            CaptureRequest.Builder previewRequestBuilder =
                    cameraController
                            .getRequestFactory()
                            .makePreview(
                                    viewfinderSurface,
                                    cameraController.getOutputSurfaces(),
                                    currentSensorExposureTimeNs,
                                    currentSensorSensitivity, wantAutoExp);

            captureSession.stopRepeating();
            captureSession.setRepeatingRequest(
                    previewRequestBuilder.build(),
                    cameraController.getSynchronizerCaptureCallback(),
                    cameraHandler);

        } catch (CameraAccessException e) {
            Log.w(TAG, "Unable to create preview.");
        }
    }

    private void startPreview() {
        startPreview(false);
    }

    /**
     * Create directory and return file
     * returning video file
     */
    private String getOutputMediaFilePath() throws IOException {
//    // External sdcard file location
//    File mediaStorageDir = new File(Environment.getExternalStorageDirectory(),
//            "MROB_VID");
//    // Create storage directory if it does not exist
//    if (!mediaStorageDir.exists()) {
//      if (!mediaStorageDir.mkdirs()) {
//        Log.d(TAG, "Oops! Failed create "
//                + "MROB_VID" + " directory");
//        return null;
//      }
//    }

        File sdcard = Environment.getExternalStorageDirectory();

        Path dir = Files.createDirectories(Paths.get(sdcard.getAbsolutePath(), SUBDIR_NAME, "VID"));

        lastTimeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.getDefault()).format(new Date());
        String mediaFile;
        mediaFile = dir.toString() + File.separator + "VID_" + lastTimeStamp + ".mp4";
        return mediaFile;

    }

    private void createRecorderSurface() throws IOException {
        surface = MediaCodec.createPersistentInputSurface();

        MediaRecorder recorder = setUpMediaRecorder(surface, false);
        recorder.prepare();
        recorder.release();
        deleteUnusedVideo();
    }

    private MediaRecorder setUpMediaRecorder(Surface surface) throws IOException {
        return setUpMediaRecorder(surface, true);
    }

    private MediaRecorder setUpMediaRecorder(Surface surface, boolean specifyOutput) throws IOException {
        MediaRecorder recorder = new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        /*
         * create video output file
         */
        /*
         * set output file in media recorder
         */

        lastVideoPath = getOutputMediaFilePath();
        recorder.setOutputFile(lastVideoPath);

        CamcorderProfile profile = CamcorderProfile.get(resolvedVideoProfileQuality);
        recorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        int bitRate = VIDEO_BITRATE_OVERRIDE != null ? VIDEO_BITRATE_OVERRIDE : profile.videoBitRate;
        recorder.setVideoEncodingBitRate(bitRate);

        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
//    int rotation = getWindowManager().getDefaultDisplay().getRotation();
/*    switch (mSensorOrientation) {
      case SENSOR_ORIENTATION_INVERSE_DEGREES:
        mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
        break;
    }*/
        recorder.setInputSurface(surface);
        return recorder;
    }

    public void setVideoRecording(boolean videoRecording) {
        isVideoRecording = videoRecording;
    }

    public boolean isVideoRecording() {
        return isVideoRecording;
    }

    public void startVideo(boolean wantAutoExp) {
        Log.d(TAG, "Starting video.");
        Toast.makeText(this, "Started recording video", Toast.LENGTH_LONG).show();

        lastVideoSeqId = null;
        lastVideoCsvSensorTimestampNs = Long.MIN_VALUE;
        videoCsvTimestampQueue.clear();
        isVideoRecording = true;
        boolean encoderRunning = false;
        try {
            lastVideoPath = getOutputMediaFilePath();
            String filename = lastTimeStamp + ".csv";
            try {
                mLogger = new CSVLogger(SUBDIR_NAME, filename, this);
            } catch (IOException e) {
                e.printStackTrace();
            }
            CamcorderProfile profile = CamcorderProfile.get(resolvedVideoProfileQuality);
            int bitRate =
                    VIDEO_BITRATE_OVERRIDE != null ? VIDEO_BITRATE_OVERRIDE : profile.videoBitRate;
            int frameRate = profile.videoFrameRate;
            if (frameRate <= 0) {
                frameRate = 30;
            }
            if (mp4SurfaceEncoder == null) {
                mp4SurfaceEncoder = new Mp4SurfaceEncoder();
            }
            mp4SurfaceEncoder.startEncoding(
                    surface,
                    lastVideoPath,
                    profile.videoFrameWidth,
                    profile.videoFrameHeight,
                    bitRate,
                    frameRate,
                    mLogger,
                    videoCsvTimestampQueue);
            encoderRunning = true;
            Log.d(TAG, "Mp4SurfaceEncoder started for " + lastVideoPath);
            CaptureRequest.Builder previewRequestBuilder =
                    cameraController
                            .getRequestFactory()
                            .makeVideo(
                                    surface,
                                    viewfinderSurface,
                                    cameraController.getOutputSurfaces(),
                                    currentSensorExposureTimeNs,
                                    currentSensorSensitivity,
                                    wantAutoExp);

            captureSession.stopRepeating();

            lastVideoSeqId =
                    captureSession.setRepeatingRequest(
                            previewRequestBuilder.build(),
                            cameraController.getSynchronizerCaptureCallback(),
                            cameraHandler);
        } catch (CameraAccessException e) {
            Log.w(TAG, "Unable to create video request.");
            lastVideoSeqId = null;
            isVideoRecording = false;
            if (encoderRunning) {
                try {
                    mp4SurfaceEncoder.stopAndRelease(() -> {});
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    mp4SurfaceEncoder.releaseQuietly();
                }
            }
            if (mLogger != null) {
                mLogger.close();
                setLogger(null);
            }
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Video encoder start failed", e);
            lastVideoSeqId = null;
            isVideoRecording = false;
            if (mp4SurfaceEncoder != null) {
                mp4SurfaceEncoder.releaseQuietly();
            }
            if (mLogger != null) {
                mLogger.close();
                setLogger(null);
            }
        }
    }

    public void stopVideo() {
        Toast.makeText(this, "Stopped recording video", Toast.LENGTH_LONG).show();
        startPreview();
    }

    private void stopPreview() {
        Log.d(TAG, "Stopping preview.");
        if (captureSession == null) {
            return;
        }
        try {
            captureSession.stopRepeating();
            Log.d(TAG, "Done: session is now ready.");
        } catch (CameraAccessException e) {
            Log.d(TAG, "Could not stop repeating.");
        }
    }

    private void checkPermissions() {
        List<String> requests = new ArrayList<>(3);

        if (checkSelfPermission(permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requests.add(permission.CAMERA);
        }
        if (checkSelfPermission(permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requests.add(permission.READ_EXTERNAL_STORAGE);
        }
        if (checkSelfPermission(permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requests.add(permission.WRITE_EXTERNAL_STORAGE);
        }
        if (checkSelfPermission(permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            requests.add(permission.INTERNET);
        }
        if (checkSelfPermission(permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            requests.add(permission.ACCESS_WIFI_STATE);
        }

        if (requests.size() > 0) {
            String[] requestsArray = new String[requests.size()];
            requestsArray = requests.toArray(requestsArray);
            requestPermissions(requestsArray, /*requestCode=*/ 0);
        } else {
            permissionsGranted = true;
        }
    }

    /**
     * Wait for permissions to continue onCreate.
     */
    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length < 3) {
            Log.e(TAG, "Wrong number of permissions returned: " + grantResults.length);
            Toast.makeText(this, R.string.error_msg_no_permission, Toast.LENGTH_LONG).show();
            finish();
        }
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                permissionsGranted = false;
                Log.e(TAG, "Permission not granted");
                Toast.makeText(this, R.string.error_msg_no_permission, Toast.LENGTH_LONG).show();
                return;
            }
        }

        // All permissions granted. Continue startup.
        onCreateWithPermission();
    }

    // PATCH: collective reset
    /**
     * Restarts the application using AlarmManager for robust scheduling.
     *
     * @param delayMs The delay in milliseconds before restarting.
     */
    public void restartApp(long delayMs) {
        Log.d(TAG, "Scheduling app restart via AlarmManager in " + delayMs + "ms.");

        // 1. Set up the AlarmManager to restart the app
        Intent restartIntent = new Intent(this, RestartAppBroadcastReceiver.class);
        restartIntent.setAction(RestartAppBroadcastReceiver.ACTION_RESTART_APP);

        // FLAG_IMMUTABLE is required for API 31+
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                getApplicationContext(), 0, restartIntent, flags);

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            // Schedule the alarm to trigger after the delay.
            // Use setExact for better precision on newer Android versions if possible,
            // but set() is sufficient and doesn't require extra permissions.
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    pendingIntent);

            Log.d(TAG, "AlarmManager set. Finishing current activity.");
        } else {
            Log.e(TAG, "AlarmManager service not available!");
            runOnUiThread(() -> Toast.makeText(this, "Failed to schedule restart!", Toast.LENGTH_LONG).show());
            return; // Don't proceed if alarm manager is not available
        }

        // 2. Gracefully finish the current activity.
        // The AlarmManager will wake up the BroadcastReceiver to restart the app later.
        finish();
    }

    @Override
    protected void onDestroy() {
        if (mp4SurfaceEncoder != null) {
            mp4SurfaceEncoder.quitSafely();
            mp4SurfaceEncoder = null;
        }
        super.onDestroy();
    }
}
