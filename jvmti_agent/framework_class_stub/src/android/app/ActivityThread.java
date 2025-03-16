package android.app;

import android.content.pm.ApplicationInfo;

public class ActivityThread {

    public static String currentPackageName() {
        throw new RuntimeException("Stub!");
    }

    public static ActivityThread currentActivityThread() {
        throw new RuntimeException("Stub!");
    }

    public ApplicationThread getApplicationThread() {
        throw new RuntimeException("Stub!");
    }

    public final LoadedApk peekPackageInfo(String packageName, boolean includeCode) {
        throw new RuntimeException("Stub!");
    }

    private class ApplicationThread extends IApplicationThread.Stub {

        public void scheduleApplicationInfoChanged(ApplicationInfo ai) {
            throw new RuntimeException("Stub!");
        }
    }
}