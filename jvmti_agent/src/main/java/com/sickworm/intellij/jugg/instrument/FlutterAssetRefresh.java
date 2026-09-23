package com.sickworm.intellij.jugg.instrument;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.ApkAssets;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.sickworm.intellij.jugg.hotfix.LogUtils;
import com.sickworm.intellij.jugg.hotfix.ReflectUtil;
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pushes the current host AssetManager into every live Flutter engine.
 * <p>
 * Apply Changes swaps the host Resources to a new ResourcesImpl/AssetManager, but FlutterEngine
 * captures one AssetManager at construction and its native APKAssetProvider caches the matching
 * AAssetManager, so the running engine keeps reading pre-overlay assets. Flutter exposes
 * FlutterJNI.updateJavaAssetManager() to replace that resolver.
 * <p>
 * Everything here is reflective and best-effort: missing Flutter classes, missing engines and
 * failed refreshes must never affect the Android resource flow.
 */
public final class FlutterAssetRefresh {

    private static final String TAG = InstrumentationHooks.TAG;
    private static final String FLUTTER_ENGINE_CLASS = "io.flutter.embedding.engine.FlutterEngine";
    private static final String FLUTTER_INJECTOR_CLASS = "io.flutter.FlutterInjector";
    private static final String ENGINE_MAP_FIELD = "idToEngine";
    private static final String FLUTTER_JNI_FIELD = "flutterJNI";
    private static final String DART_EXECUTOR_FIELD = "dartExecutor";
    private static final String DART_ASSET_MANAGER_FIELD = "assetManager";
    private static final String DEFAULT_ASSET_BUNDLE_PATH = "flutter_assets";
    private static final String REFRESH_FAILURE_MESSAGE =
            "Jugg: Flutter assets were not refreshed. Restart the app to apply the changes.";

    private static final AtomicBoolean refreshScheduled = new AtomicBoolean();
    // Managers that already carry an appended overlay directory entry; identity based and
    // weak, so the same Resources never grow a second overlay entry and nothing is retained.
    private static final Set<AssetManager> enhancedAssetManagers =
            Collections.newSetFromMap(new WeakHashMap<AssetManager, Boolean>());

    private FlutterAssetRefresh() {
    }

    /** Returns whether the current app can use the Android 11+ Flutter asset refresh path. */
    public static boolean shouldScheduleRefresh() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        try {
            return isFlutterApplication(currentApplication());
        } catch (Throwable e) {
            LogUtils.d(TAG, "Flutter asset refresh not scheduled: " + reason(e));
            return false;
        }
    }

    /**
     * Schedules one main-thread refresh, merging repeated hook calls from the same batch.
     * <p>
     * The caller runs inside the ResourcesManager.createAssetManager call stack, where the new
     * AssetManager is not installed on the Application Resources yet, so the refresh is deferred.
     */
    public static void scheduleRefresh() {
        if (!refreshScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!new Handler(Looper.getMainLooper()).post(() -> {
                refreshScheduled.set(false);
                refreshOnMainThread();
            })) {
                refreshScheduled.set(false);
            }
        } catch (Throwable e) {
            refreshScheduled.set(false);
            LogUtils.w(TAG, "Flutter asset refresh not scheduled: " + e);
        }
    }

    private static void refreshOnMainThread() {
        Context context;
        List<Object> engines;
        try {
            context = currentApplication();
            if (context == null) {
                return;
            }
            Class<?> engineClass = findEngineClass(context.getClassLoader());
            if (engineClass == null) {
                return;
            }
            engines = snapshotEngines(engineClass);
            if (engines == null || engines.isEmpty()) {
                return;
            }
            if (overlayApkPaths().isEmpty()) {
                LogUtils.d(TAG, "Flutter asset refresh skipped: no Apply Changes overlay for raw assets");
                return;
            }
        } catch (Throwable e) {
            // The overlay or the engines are not confirmed yet, so this stays a normal no-op.
            LogUtils.w(TAG, "Flutter asset refresh skipped before overlay and engines were confirmed: " + e);
            return;
        }
        // The overlay and the engines are confirmed now: the batch below owns every engine of the
        // snapshot and reports the whole batch when one of them cannot be refreshed.
        try {
            AssetManager assetManager = flutterAssetManager(context);
            refreshEngines(context, engines, assetManager, assetBundlePath(context.getClassLoader()));
        } catch (Throwable e) {
            reportFailures(context, Collections.singletonList("batch failed: " + reason(e)));
        }
    }

    /**
     * Refreshes every engine of the given snapshot and reports the batch once, so a failing engine
     * neither hides nor duplicates the result of the others.
     *
     * @return a description of every engine that failed, empty when nothing failed
     */
    static List<String> refreshEngines(Context context, List<Object> engines, AssetManager assetManager,
            String assetBundlePath) {
        List<String> failures = new ArrayList<>();
        for (Object engine : engines) {
            String failure = refreshEngine(engine, assetManager, assetBundlePath);
            if (failure != null) {
                failures.add(failure);
            }
        }
        if (failures.isEmpty()) {
            LogUtils.d(TAG, "Flutter assets refreshed for " + engines.size() + " engine(s)");
        } else {
            reportFailures(context, failures);
        }
        return failures;
    }

    /** Reports one failed batch to the log and to the user. */
    private static void reportFailures(Context context, List<String> failures) {
        LogUtils.w(TAG, "Flutter assets not refreshed for " + failures.size() +
                " engine(s), restart the app to apply them: " + failures);
        try {
            Toast.makeText(context, REFRESH_FAILURE_MESSAGE, Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            LogUtils.w(TAG, "Flutter asset refresh notification failed: " + e);
        }
    }

    /** Returns whether the package context should be prepared for the current Flutter app. */
    public static boolean shouldPrepareHostPackageContext(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return isFlutterApplication(currentApplication());
        } catch (Throwable e) {
            LogUtils.d(TAG, "Flutter package context update not scheduled: " + reason(e));
            return false;
        }
    }

    /**
     * Fixes the AssetManager of a package context that belongs to the host APK, which is what a
     * FlutterEngine captures its AssetManager from. The context does not inherit the overlay
     * loaders of the live Resources, and the engine keeps the native handle of that AssetManager
     * for its whole life, long before any Dart launch could be told about the overlay.
     */
    static void prepareHostPackageContext(Context context) {
        try {
            AssetManager assetManager = context.getResources().getAssets();
            if (!hasHostApkPath(apkPaths(assetManager))) {
                LogUtils.d(TAG, "Host package context AssetManager unchanged: not a host APK");
                return;
            }
            applyOverlayAssets(assetManager, overlayApkPaths());
            LogUtils.d(TAG, "Host package context AssetManager serves the overlay for raw assets");
        } catch (Throwable e) {
            LogUtils.w(TAG, "Host package context AssetManager update failed: " + reason(e));
        }
    }

    /** Returns whether the context can resolve Flutter through its application class loader. */
    static boolean isFlutterApplication(Context context) {
        return context != null && findEngineClass(context.getClassLoader()) != null;
    }

    /** Returns true when any of the given ApkAssets paths is one of the host APKs. */
    static boolean hasHostApkPath(List<String> apkPaths) {
        for (String apkPath : apkPaths) {
            if (ApplyChangesOverlayPolicy.isHostApkPath(apkPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the AssetManager to hand to Flutter, verified to serve the overlay for raw assets.
     * Flutter captures the AssetManager of the package context it creates, and the package context
     * hook has already enhanced that very instance.
     */
    static AssetManager flutterAssetManager(Context context) throws Exception {
        List<String> overlayPaths = overlayApkPaths();
        if (overlayPaths.isEmpty()) {
            return null;
        }
        AssetManager assetManager = context.createPackageContext(context.getPackageName(), 0).getAssets();
        if (!applyOverlayAssets(assetManager, overlayPaths)) {
            throw new IllegalStateException(assetManager + " cannot serve overlay raw assets");
        }
        return assetManager;
    }

    /**
     * Loads every overlay directory as an apk asset and appends it to the AssetManager. The appended
     * entry is the last one, which is where the reverse lookup of AssetManager2::OpenNonAsset finds
     * the overlaid files first.
     */
    static boolean applyOverlayAssets(AssetManager assetManager, List<String> overlayPaths) throws Exception {
        if (overlayPaths.isEmpty() || enhancedAssetManagers.contains(assetManager)) {
            return true;
        }
        List<ApkAssets> merged = new ArrayList<>();
        for (ApkAssets apkAssets : getApkAssets(assetManager)) {
            merged.add(apkAssets);
        }
        for (String overlayPath : overlayPaths) {
            merged.add(loadOverlayAssets(overlayPath));
        }
        ReflectUtil.findMethod(assetManager, "setApkAssets", ApkAssets[].class, boolean.class)
                .invoke(assetManager, merged.toArray(new ApkAssets[0]), true);
        enhancedAssetManagers.add(assetManager);
        return true;
    }

    /** Loads an overlay directory through Android's native directory assets provider. */
    @TargetApi(Build.VERSION_CODES.R)
    private static ApkAssets loadOverlayAssets(String overlayPath) throws Exception {
        ResourcesProvider provider = ResourcesProvider.loadFromDirectory(overlayPath, null);
        Object apkAssets = ReflectUtil.findMethod(provider, "getApkAssets").invoke(provider);
        if (apkAssets instanceof ApkAssets) {
            return (ApkAssets) apkAssets;
        }
        if (apkAssets instanceof List && !((List<?>) apkAssets).isEmpty()) {
            return (ApkAssets) ((List<?>) apkAssets).get(0);
        }
        throw new IllegalStateException(overlayPath + " produced no apk assets");
    }

    /**
     * Returns the apk asset paths the AssetManager must have: its own ones plus the overlay
     * directories that are missing, appended last so the reverse raw asset lookup reaches them
     * first. Idempotent, so repeated calls never grow the list.
     */
    static List<String> assetPathsForOverlay(List<String> currentPaths, List<String> overlayPaths) {
        List<String> targetPaths = new ArrayList<>(currentPaths);
        for (String overlayPath : overlayPaths) {
            if (!targetPaths.contains(overlayPath)) {
                targetPaths.add(overlayPath);
            }
        }
        return targetPaths;
    }

    /** Returns the apk assets of the given AssetManager. */
    private static ApkAssets[] getApkAssets(AssetManager assetManager) throws Exception {
        ApkAssets[] apkAssets = (ApkAssets[]) ReflectUtil.findMethod(assetManager, "getApkAssets")
                .invoke(assetManager);
        return apkAssets == null ? new ApkAssets[0] : apkAssets;
    }

    /** Returns the overlay directories committed by Apply Changes, empty for a compatible deploy. */
    private static List<String> overlayApkPaths() {
        List<String> overlayPaths = new ArrayList<>();
        try {
            Context context = currentApplication();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || context == null) {
                return overlayPaths;
            }
            File[] apkDirs = new File(context.getCodeCacheDir(), ".overlay")
                    .listFiles(file -> file.isDirectory() && file.getName().endsWith(".apk"));
            if (apkDirs == null) {
                return overlayPaths;
            }
            for (File apkDir : apkDirs) {
                // A compatible deploy keeps the resource APK path, and its loader must stay untouched.
                if (new File(apkDir, BuildConfig.ENABLE_COMPAT_DEPLOY_FLAG_FILE).exists()) {
                    return Collections.emptyList();
                }
                overlayPaths.add(apkDir.getAbsolutePath());
            }
        } catch (Throwable e) {
            LogUtils.d(TAG, "Overlay directory lookup failed: " + reason(e));
        }
        return overlayPaths;
    }

    private static String refreshEngine(Object engine, AssetManager assetManager, String assetBundlePath) {
        try {
            Object flutterJNI = ReflectUtil.getField(engine.getClass(), FLUTTER_JNI_FIELD, engine);
            if (!(Boolean) ReflectUtil.findMethod(flutterJNI, "isAttached").invoke(flutterJNI)) {
                return describe(engine) + " failed: no native shell to update";
            }
            if (isExecutingDart(engine)) {
                ReflectUtil.findMethod(flutterJNI, "updateJavaAssetManager", AssetManager.class, String.class)
                        .invoke(flutterJNI, assetManager, assetBundlePath);
                LogUtils.d(TAG, describe(engine) + " updated through FlutterJNI");
            } else {
                replaceDartAssetManager(engine, assetManager);
                LogUtils.d(TAG, describe(engine) + " will start Dart with the new AssetManager");
            }
            return null;
        } catch (Throwable e) {
            return describe(engine) + " failed: " + reason(e);
        }
    }

    /**
     * Points the executor at the new AssetManager. Flutter rebuilds its APK asset provider from
     * this field inside FlutterJNI.runBundleAndSnapshotFromLibrary() when Dart starts, so an engine
     * that has not started Dart yet is covered by replacing the field and never by a JNI update,
     * which that same launch would overwrite.
     */
    private static void replaceDartAssetManager(Object engine, AssetManager assetManager) throws Exception {
        Object dartExecutor = ReflectUtil.getField(engine.getClass(), DART_EXECUTOR_FIELD, engine);
        Field field = ReflectUtil.findField(dartExecutor, DART_ASSET_MANAGER_FIELD);
        field.set(dartExecutor, assetManager);
        if (field.get(dartExecutor) != assetManager) {
            throw new IllegalStateException(DART_EXECUTOR_FIELD + "." + DART_ASSET_MANAGER_FIELD + " not writable");
        }
    }

    /** Returns whether the engine already runs Dart; unknown versions are treated as running. */
    private static boolean isExecutingDart(Object engine) {
        try {
            Object dartExecutor = ReflectUtil.getField(engine.getClass(), DART_EXECUTOR_FIELD, engine);
            return (Boolean) ReflectUtil.findMethod(dartExecutor, "isExecutingDart").invoke(dartExecutor);
        } catch (Throwable e) {
            LogUtils.d(TAG, "Flutter Dart state lookup failed: " + e);
            return true;
        }
    }

    /** Unwraps the reflection wrapper so the reported reason is the real failure. */
    private static String reason(Throwable e) {
        Throwable cause = e;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.toString();
    }

    /** Returns the Flutter engine class, or null when the app does not use Flutter. */
    static Class<?> findEngineClass(ClassLoader classLoader) {
        try {
            return Class.forName(FLUTTER_ENGINE_CLASS, false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable e) {
            LogUtils.w(TAG, "Flutter engine class lookup failed: " + e);
            return null;
        }
    }

    /**
     * Returns every engine the embedding still tracks, including uncached and spawned ones, or null
     * when the registry contract is not readable; a broken contract is not "this app has no
     * Flutter", so it is logged before the caller stops.
     */
    static List<Object> snapshotEngines(Class<?> engineClass) {
        try {
            Map<?, ?> engines = (Map<?, ?>) ReflectUtil.findField(engineClass, ENGINE_MAP_FIELD).get(null);
            if (engines == null) {
                return Collections.emptyList();
            }
            synchronized (engines) {
                return new ArrayList<>(engines.values());
            }
        } catch (Throwable e) {
            LogUtils.w(TAG, "Flutter engine registry " + ENGINE_MAP_FIELD + " is not readable: " + e);
            return null;
        }
    }

    /** Returns the path of every ApkAssets of the given AssetManager. */
    private static List<String> apkPaths(AssetManager assetManager) throws Exception {
        ApkAssets[] apkAssets = (ApkAssets[]) ReflectUtil.findMethod(assetManager, "getApkAssets")
                .invoke(assetManager);
        List<String> apkPaths = new ArrayList<>();
        if (apkAssets != null) {
            for (ApkAssets apkAsset : apkAssets) {
                apkPaths.add(apkAsset.getAssetPath());
            }
        }
        return apkPaths;
    }

    /** Reads the asset bundle directory from the Flutter loader, falling back to the default. */
    private static String assetBundlePath(ClassLoader classLoader) {
        try {
            Class<?> injectorClass = Class.forName(FLUTTER_INJECTOR_CLASS, false, classLoader);
            Object injector = ReflectUtil.findMethod(injectorClass, "instance").invoke(null);
            Object loader = ReflectUtil.findMethod(injectorClass, "flutterLoader").invoke(injector);
            Object path = ReflectUtil.findMethod(loader, "findAppBundlePath").invoke(loader);
            if (path instanceof String && !((String) path).isEmpty()) {
                return (String) path;
            }
        } catch (Throwable e) {
            LogUtils.d(TAG, "Flutter asset bundle path lookup failed: " + e);
        }
        return DEFAULT_ASSET_BUNDLE_PATH;
    }

    private static Context currentApplication() throws Exception {
        Object activityThread = ReflectUtil.getActivityThread(null, null);
        if (activityThread == null) {
            return null;
        }
        return (Context) ReflectUtil.findMethod(activityThread, "currentApplication").invoke(activityThread);
    }

    private static String describe(Object engine) {
        return engine.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(engine));
    }
}
