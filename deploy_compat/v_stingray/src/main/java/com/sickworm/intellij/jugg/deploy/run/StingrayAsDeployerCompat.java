package com.sickworm.intellij.jugg.deploy.run;

import com.android.adblib.AdbSessionKt;
import com.android.adblib.ConnectedDevice;
import com.android.adblib.ConnectedDevicesTracker;
import com.android.adblib.ConnectedDevicesTrackerKt;
import com.android.ddmlib.IDevice;
import com.android.tools.deployer.DexClass;
import com.android.tools.deployer.DexComparator;
import com.android.tools.deployer.common.AdbClient;
import com.android.tools.deployer.common.DeviceHolder;
import com.android.tools.idea.adblib.AdbLibApplicationService;
import com.android.utils.ILogger;
import com.intellij.openapi.progress.ProgressManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class StingrayAsDeployerCompat extends RabbitAsDeployerCompat {

    @Override
    protected AdbClient createAdbClient(IDevice device, ILogger logger) {
        var session = AdbLibApplicationService.getInstance().getSession();
        var tracker = AdbSessionKt.getConnectedDevicesTracker(session);
        var serial = device.getSerialNumber();
        return new AdbClient(new DeviceHolder(waitForConnectedDevice(tracker, serial)), logger, session);
    }

    @Override
    protected DexComparator.ChangedClasses createDexOverlays(JuggDexOverlays overlays) {
        return new DexComparator.ChangedClasses(toDexClasses(overlays.getNewClasses()),
                toDexClasses(overlays.getModifiedClasses()));
    }

    private List<DexClass> toDexClasses(List<JuggDexClass> classes) {
        var result = new ArrayList<DexClass>(classes.size());
        for (var clazz : classes) {
            result.add(new DexClass(clazz.getName(), clazz.getChecksum(), clazz.getCode(), null));
        }
        return result;
    }

    private ConnectedDevice waitForConnectedDevice(ConnectedDevicesTracker tracker, String serial) {
        // The adblib tracker can start after ddmlib, and its coroutines belong to the IDE class loader.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            ProgressManager.checkCanceled();
            var device = ConnectedDevicesTrackerKt.device(tracker, serial);
            if (device != null) {
                return device;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for ADB device " + serial, e);
            }
        } while (System.nanoTime() - deadline < 0);
        throw new IllegalStateException("ADB did not report the selected device " + serial);
    }
}
