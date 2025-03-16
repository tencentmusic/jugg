package android.app;

import android.content.pm.ApplicationInfo;

public class IApplicationThread {

    public static class Stub {
        public void scheduleApplicationInfoChanged(ApplicationInfo ai) {
            throw new RuntimeException("Stub!");
        }
    }
}