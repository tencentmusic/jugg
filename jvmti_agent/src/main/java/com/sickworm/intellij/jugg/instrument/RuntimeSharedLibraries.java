package com.sickworm.intellij.jugg.instrument;

import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.os.Build;
import com.sickworm.intellij.jugg.hotfix.LogUtils;
import com.sickworm.intellij.jugg.hotfix.ReflectUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Preserves successful host shared-library additions across ResourcesLoader rebuilds. */
final class RuntimeSharedLibraries {
    private static final Set<String> libraries = new LinkedHashSet<>();
    private static final ThreadLocal<AssetManager> requestedManager = new ThreadLocal<>();
    private static final ThreadLocal<String> requestedPath = new ThreadLocal<>();
    private static volatile String hostApkPath;

    private RuntimeSharedLibraries() {
    }

    /**
     * Records only the host ApplicationInfo.sourceDir to identify host AssetManagers for library replay.
     * The overlay policy's broader path set can overlap foreign managers through shared libraries/RROs.
     * This is an ownership marker; the library paths to restore are recorded separately by afterAdd().
     */
    static void recordHostApkPath(String path) {
        hostApkPath = path;
    }

    static void beforeAdd(AssetManager manager, String path) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        requestedManager.set(manager);
        requestedPath.set(path);
    }

    /** A positive cookie confirms that the original framework call accepted the path. */
    static void afterAdd(int cookie) {
        AssetManager manager = requestedManager.get();
        String path = requestedPath.get();
        requestedManager.remove();
        requestedPath.remove();
        if (cookie <= 0 || manager == null || path == null) {
            return;
        }
        try {
            if (!containsHost(apkPaths(manager))) {
                return;
            }
            synchronized (libraries) {
                libraries.add(path);
            }
        } catch (Throwable e) {
            LogUtils.w(InstrumentationHooks.TAG, "Could not record runtime shared library " + path + ": " + e);
        }
    }

    /** Replays the original shared-library API before a rebuilt host manager reaches consumers. */
    static void restore(AssetManager manager) {
        List<String> snapshot;
        synchronized (libraries) {
            if (libraries.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(libraries);
        }
        Set<String> existing;
        try {
            existing = apkPaths(manager);
            if (!containsHost(existing)) {
                return;
            }
        } catch (Throwable e) {
            LogUtils.w(InstrumentationHooks.TAG, "Could not inspect rebuilt AssetManager shared libraries: " + e);
            return;
        }
        // Known limitation: same-path assets in shared libraries and host overlays may resolve
        // differently from a full Gradle build across framework versions and rebuild/restart timing.
        // Preserve framework ordering; forcing loader priority also changes full-build semantics.
        for (String path : snapshot) {
            if (!existing.contains(path)) {
                restoreLibrary(manager, path);
            }
        }
    }

    private static void restoreLibrary(AssetManager manager, String path) {
        try {
            int cookie = (Integer) ReflectUtil.findMethod(manager, "addAssetPathAsSharedLibrary", String.class)
                    .invoke(manager, path);
            if (cookie <= 0) {
                throw new IllegalStateException("framework returned cookie=" + cookie);
            }
            LogUtils.d(InstrumentationHooks.TAG, "Restored runtime shared library: manager=" + manager +
                    ", path=" + path + ", cookie=" + cookie);
        } catch (Throwable e) {
            LogUtils.w(InstrumentationHooks.TAG, "Could not restore runtime shared library " + path +
                    ", manager=" + manager + ": " + e + ", cause=" + e.getCause());
        }
    }

    private static Set<String> apkPaths(AssetManager manager) throws Exception {
        ApkAssets[] assets = (ApkAssets[]) ReflectUtil.findMethod(manager, "getApkAssets").invoke(manager);
        Set<String> paths = new LinkedHashSet<>();
        for (ApkAssets asset : assets) {
            paths.add(asset.getAssetPath());
        }
        return paths;
    }

    private static boolean containsHost(Set<String> paths) {
        // Framework libraries and RROs are shared with foreign packages; require the host base APK.
        String host = hostApkPath;
        return host != null && paths.contains(host);
    }
}
