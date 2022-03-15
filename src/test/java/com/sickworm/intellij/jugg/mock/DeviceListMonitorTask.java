package com.sickworm.intellij.jugg.mock;

import com.android.ddmlib.AdbHelper;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.Log;
import com.android.ddmlib.TimeoutException;
import com.android.ddmlib.AdbHelper.AdbResponse;
import com.android.ddmlib.IDevice.DeviceState;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Copied from DeviceListMonitorTask and modified
 */
@VisibleForTesting
public class DeviceListMonitorTask {
    private static final String ADB_TRACK_DEVICES_COMMAND = "host:track-devices";
    private final byte[] mLengthBuffer = new byte[4];
    private SocketChannel mAdbConnection = null;
    private boolean mMonitoring = false;
    private int mConnectionAttempt = 0;
    private int mRestartAttemptCount = 0;
    private Stopwatch mAdbDisconnectionStopwatch;
    private boolean mInitialDeviceListDone = false;
    private volatile boolean mQuit;

    public DeviceListMonitorTask() {
    }

    public Map<String, DeviceState> getDeviceList() {
        if (this.mAdbConnection == null) {
            Log.d("DeviceMonitor", "Opening adb connection");

            try {
                this.mAdbConnection = AndroidDebugBridge.openConnection();
            } catch (IOException var4) {
                Log.d("DeviceMonitor", "Unable to open connection to ADB server: " + var4);
            }

            if (this.mAdbConnection == null) {
                ++this.mConnectionAttempt;
                if (this.mConnectionAttempt == 1) {
                    Log.e("DeviceMonitor", "Cannot reach ADB server, attempting to reconnect.");
                    this.mAdbDisconnectionStopwatch = Stopwatch.createStarted();
                    if (AndroidDebugBridge.isUserManagedAdbMode()) {
                        Log.i("DeviceMonitor", "Will not automatically restart the ADB server because ddmlib is in user managed mode");
                    }
                }

//                if (!AndroidDebugBridge.isUserManagedAdbMode() && this.mConnectionAttempt > 10) {
//                    if (!this.mBridge.startAdb(20000L, TimeUnit.MILLISECONDS)) {
//                        ++this.mRestartAttemptCount;
//                    } else {
//                        Log.i("DeviceMonitor", "adb restarted");
//                        this.mRestartAttemptCount = 0;
//                    }
//                }

                Uninterruptibles.sleepUninterruptibly(1L, TimeUnit.SECONDS);
            } else {
                if (this.mConnectionAttempt > 0) {
                    Log.i("DeviceMonitor", "ADB connection re-established after " + this.mAdbDisconnectionStopwatch.elapsed(TimeUnit.SECONDS) + " seconds.");
                    this.mAdbDisconnectionStopwatch.reset();
                } else {
                    Log.i("DeviceMonitor", "Connected to adb for device monitoring");
                }

                this.mConnectionAttempt = 0;
            }
        }

        try {
            if (this.mAdbConnection != null && !this.mMonitoring) {
                this.mMonitoring = this.sendDeviceListMonitoringRequest();
            }

            if (this.mMonitoring) {
                int length = AdbSocketUtils.readLength(this.mAdbConnection, this.mLengthBuffer);
                if (length >= 0) {
                    Map<String, DeviceState> devices = this.processIncomingDeviceData(length);
                    this.mInitialDeviceListDone = true;
                    return devices;
                }
            }
        } catch (AsynchronousCloseException var2) {
            Log.e("DeviceMonitor", var2);
        } catch (TimeoutException | IOException var3) {
            this.handleExceptionInMonitorLoop(var3);
        }

        return Collections.emptyMap();
    }

    private boolean sendDeviceListMonitoringRequest() throws TimeoutException, IOException {
        byte[] request = AdbHelper.formAdbRequest("host:track-devices");

        try {
            AdbHelper.write(this.mAdbConnection, request);
            AdbResponse resp = AdbHelper.readAdbResponse(this.mAdbConnection, false);
            if (!resp.okay) {
                Log.e("DeviceMonitor", "adb refused request: " + resp.message);
            }

            return resp.okay;
        } catch (IOException var3) {
            Log.e("DeviceMonitor", "Sending Tracking request failed!");
            this.mAdbConnection.close();
            throw var3;
        }
    }

    private void handleExceptionInMonitorLoop(Exception e) {
        if (!this.mQuit) {
            if (e instanceof TimeoutException) {
                Log.e("DeviceMonitor", "Adb connection Error: timeout");
            } else {
                Log.e("DeviceMonitor", "Adb connection Error:" + e.getMessage());
            }

            this.mMonitoring = false;
            if (this.mAdbConnection != null) {
                try {
                    this.mAdbConnection.close();
                } catch (IOException var3) {
                }

                this.mAdbConnection = null;
            }
        }

    }

    private Map<String, DeviceState> processIncomingDeviceData(int length) throws IOException {
        Map result;
        if (length <= 0) {
            result = Collections.emptyMap();
        } else {
            String response = AdbSocketUtils.read(this.mAdbConnection, new byte[length]);
            result = parseDeviceListResponse(response);
        }

        return result;
    }

    @VisibleForTesting
    public static Map<String, DeviceState> parseDeviceListResponse(String result) {
        Map<String, DeviceState> deviceStateMap = Maps.newHashMap();
        String[] devices = result == null ? new String[0] : result.split("\n");
        String[] var3 = devices;
        int var4 = devices.length;

        for(int var5 = 0; var5 < var4; ++var5) {
            String d = var3[var5];
            String[] param = d.split("\t");
            if (param.length == 2) {
                deviceStateMap.put(param[0], DeviceState.getState(param[1]));
            }
        }

        return deviceStateMap;
    }

    boolean isMonitoring() {
        return this.mMonitoring;
    }

    boolean hasInitialDeviceList() {
        return this.mInitialDeviceListDone;
    }

    int getConnectionAttemptCount() {
        return this.mConnectionAttempt;
    }

    int getRestartAttemptCount() {
        return this.mRestartAttemptCount;
    }

    public void stop() {
        this.mQuit = true;
        if (this.mAdbConnection != null) {
            try {
                this.mAdbConnection.close();
            } catch (IOException var2) {
            }
        }

    }

    interface UpdateListener {
        void connectionError(Exception e);

        void deviceListUpdate(Map<String, DeviceState> devices);
    }
}
