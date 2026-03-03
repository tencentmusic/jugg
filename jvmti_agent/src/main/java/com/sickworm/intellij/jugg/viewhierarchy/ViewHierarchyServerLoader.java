package com.sickworm.intellij.jugg.viewhierarchy;

import android.content.Context;

import com.sickworm.intellij.jugg.hotfix.LogUtils;

/**
 * ViewHierarchyServerLoader is a process-wide singleton entry.
 *
 * BootstrapApplication calls this class and only one successful initialization
 * is allowed.
 */
public final class ViewHierarchyServerLoader {

    private static final String TAG = "Jugg#ViewHierarchyLoader";
    private static volatile boolean sInitialized = false;

    interface ServerStarter {
        boolean start(Context context);
    }

    private static final ServerStarter DEFAULT_SERVER_STARTER = new ServerStarter() {
        @Override
        public boolean start(Context context) {
            return ViewHierarchyServer.start(context);
        }
    };
    private static volatile ServerStarter sServerStarter = DEFAULT_SERVER_STARTER;

    private ViewHierarchyServerLoader() {
    }

    public static synchronized void init(Context context) {
        if (sInitialized || context == null) {
            return;
        }
        try {
            boolean started = sServerStarter.start(context);
            if (!started) {
                LogUtils.e(TAG, "ViewHierarchyServer init failed: start returned false.");
                return;
            }
            sInitialized = true;
            LogUtils.i(TAG, "ViewHierarchyServer initialized.");
        } catch (Throwable t) {
            LogUtils.e(TAG, "ViewHierarchyServer init failed", t);
        }
    }

    static synchronized void setServerStarterForTest(ServerStarter starter) {
        sServerStarter = starter != null ? starter : DEFAULT_SERVER_STARTER;
        sInitialized = false;
    }

    static synchronized void resetForTest() {
        sServerStarter = DEFAULT_SERVER_STARTER;
        sInitialized = false;
    }
}
