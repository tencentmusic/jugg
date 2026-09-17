package com.sickworm.intellij.jugg.instrument;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.ResourcesKey;
import android.content.res.Resources;
import android.os.Build;
import com.sickworm.intellij.jugg.hotfix.HotfixLoader;
import com.sickworm.intellij.jugg.hotfix.LogUtils;
import com.sickworm.intellij.jugg.hotfix.NativeLibraryPathInstaller;
import com.sickworm.intellij.jugg.hotfix.ReflectUtil;
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/** @noinspection unused*/
public class InstrumentationHooks {

    public static final String TAG = "jugg-jvmti";
    private static final HashSet<String> loggedAssetManagerDecisions = new HashSet<>();
    private static final AtomicBoolean classpathResourceHookEntered = new AtomicBoolean();
    private static volatile ClassLoader classpathResourceHostClassLoader;
    private static volatile File classpathResourceOverlayRoot;

    public static void initializeDirectResourceOverlays(String dataDir) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ResourceOverlays.initialize(dataDir);
        }
    }

    public static void prepareResourceOverlays(LoadedApk loadedApk) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            ResourceOverlays.prepare(loadedApk.getApplicationInfo());
        } catch (Exception e) {
            LogUtils.w(TAG, "Could not prepare Direct resource overlays: " + e);
        }
    }

    public static Resources addResourceOverlays(Resources resources) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                ResourceOverlays.addResourceOverlays(resources);
            } catch (Exception e) {
                LogUtils.w(TAG, "Could not load Direct resource overlays: " + e);
            }
        }
        return resources;
    }

    public static void handleAttachBaseContextEntry(ContextWrapper contextWrapper, Context base)
        throws Exception {
        if (!(contextWrapper instanceof Application)) {
            return;
        }
        LogUtils.i(TAG, "handleAttachBaseContextEntry contextWrapper: " + contextWrapper);
        try {
            ApplyChangesOverlayPolicy.recordHostApplicationInfo(base.getApplicationInfo());
            boolean isNeedFix = DexPathListFixer.isNeedFix(base);
            LogUtils.i(TAG, "handleAttachBaseContextEntry isNeedFix: " + isNeedFix);
            HotfixLoader.init(base);
            if (isNeedFix) {
                HotfixLoader.installDex(base);
                LogUtils.i(TAG, "handleAttachBaseContextEntry fix finished");
            }
            NativeLibraryPathInstaller.install(base);
        } catch (Exception e) {
            LogUtils.e(TAG, "handleAttachBaseContextEntry", e);
            throw e;
        }
    }

    @SuppressLint("StaticFieldLeak")
    private static Context base;

    public static void handleNewApplicationEntry(Instrumentation instrumentation, ClassLoader classLoader, String className, Context base) {
        try {
            recordClasspathResourceHost(classLoader, base);
            ApplyChangesOverlayPolicy.recordHostApplicationInfo(base.getApplicationInfo());
            boolean isNeedFix = DexPathListFixer.isNeedFix(base);
            LogUtils.i(TAG, "handleAttachBaseContextEntry isNeedFix: " + isNeedFix);
            HotfixLoader.init(base);
            if (isNeedFix) {
                HotfixLoader.installDex(base);
                InstrumentationHooks.base = base;
                LogUtils.i(TAG, "handleAttachBaseContextEntry fix finished");
            }
            NativeLibraryPathInstaller.install(base);
        } catch (Exception e) {
            LogUtils.e(TAG, "handleAttachBaseContextEntry", e);
            throw new RuntimeException(e);
        }
    }

    public static void handleNewApplicationEntry2(Instrumentation instrumentation, Class<?> clazz, Context base) {
        try {
            recordClasspathResourceHost(base.getClassLoader(), base);
            ApplyChangesOverlayPolicy.recordHostApplicationInfo(base.getApplicationInfo());
            boolean isNeedFix = DexPathListFixer.isNeedFix(base);
            LogUtils.i(TAG, "handleAttachBaseContextEntry2 isNeedFix: " + isNeedFix);
            HotfixLoader.init(base);
            if (isNeedFix) {
                HotfixLoader.install(base);
                InstrumentationHooks.base = base;
                LogUtils.i(TAG, "handleAttachBaseContextEntry2 fix finished");
            }
            NativeLibraryPathInstaller.install(base);
        } catch (Exception e) {
            LogUtils.e(TAG, "handleAttachBaseContextEntry2", e);
            throw new RuntimeException(e);
        }
    }

    public static Application handleInstantiateApplicationExit(Application application)
        throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        LogUtils.i(TAG, "handleInstantiateApplicationExit");
        if (base == null) {
            // no need fix
            return application;
        }
        return (Application) base.getClassLoader().loadClass(application.getClass().getName()).newInstance();
    }

    public static URL classLoaderGetResource(ClassLoader classLoader, String name) {
        if (classpathResourceHookEntered.compareAndSet(false, true)) {
            LogUtils.i(TAG, "Classpath resource hook in");
        }
        File overlayRoot = classpathResourceOverlayRoot;
        if (name == null || overlayRoot == null || !isHostClassLoader(classLoader)) {
            return null;
        }
        try {
            File overlayFile = new File(overlayRoot, name);
            if (overlayFile.isFile()) {
                LogUtils.i(TAG, "Classpath resource overlay hit: file:" + name);
                return overlayFile.toURI().toURL();
            }

            File resourceApk = new File(overlayRoot, BuildConfig.RESOURCE_APK_NAME);
            if (!resourceApk.isFile() || !hasZipEntry(resourceApk, name)) {
                return null;
            }
            LogUtils.i(TAG, "Classpath resource overlay hit: resource_ap_:" + name);
            return new URL("jar:" + resourceApk.toURI().toURL() + "!/" + name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void recordClasspathResourceHost(ClassLoader classLoader, Context base) {
        classpathResourceHostClassLoader = classLoader;
        classpathResourceOverlayRoot = new File(base.getCodeCacheDir(), ".overlay/base.apk");
    }

    private static boolean isHostClassLoader(ClassLoader classLoader) {
        ClassLoader host = classpathResourceHostClassLoader;
        for (ClassLoader current = classLoader; host != null && current != null; current = current.getParent()) {
            if (current == host) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasZipEntry(File zipFile, String name) throws Exception {
        try (ZipFile zip = new ZipFile(zipFile)) {
            return zip.getEntry(name) != null;
        }
    }

    // One decision is taken per call. Android 14+ has both createAssetManager signatures and the
    // old one delegates to the new one, so the enter/exit pairs nest, and ResourcesManager can run
    // on any thread, so a single static field could hand one call's decision to another call.
    private static final ThreadLocal<Boolean> isNeedFixThisAssetManagerPerThread = new ThreadLocal<>();

    public static void createAssetManagerEnter(ResourcesManager assetManager, ResourcesKey resourcesKey) {
        if (isEnableHotfix()) {
            return;
        }
        String resDir = resourcesKey.mResDir;
        boolean isNeedFix = isNeedFixThisAssetManager(resourcesKey);
        isNeedFixThisAssetManagerPerThread.set(isNeedFix);
        logAssetManagerDecisionOnce("createAssetManager", resDir, isNeedFix);
    }

    public static AssetManager createAssetManagerExit(AssetManager assetManager) {
        boolean isNeedFix = Boolean.TRUE.equals(isNeedFixThisAssetManagerPerThread.get());
        isNeedFixThisAssetManagerPerThread.remove();
        if (isEnableHotfix()) {
            return assetManager;
        }
        if (isNeedFix) {
            tryFixOutSideApk(assetManager);
        } else if (FlutterAssetRefresh.shouldScheduleRefresh()) {
            // The host AssetManager is recreated by Apply Changes; running Flutter engines need it.
            FlutterAssetRefresh.scheduleRefresh();
        }
        return assetManager;
    }

    private static final ThreadLocal<Boolean> isNeedFixThisAssetManagerNewPerThread = new ThreadLocal<>();

    public static void createAssetManagerNewEnter(ResourcesManager assetManager, ResourcesKey resourcesKey, ResourcesManager.ApkAssetsSupplier apkAssetsSupplier) {
        if (isEnableHotfix()) {
            return;
        }
        String resDir = resourcesKey.mResDir;
        boolean isNeedFix = isNeedFixThisAssetManager(resourcesKey);
        isNeedFixThisAssetManagerNewPerThread.set(isNeedFix);
        logAssetManagerDecisionOnce("createAssetManagerNew", resDir, isNeedFix);
    }

    public static AssetManager createAssetManagerNewExit(AssetManager assetManager) {
        boolean isNeedFix = Boolean.TRUE.equals(isNeedFixThisAssetManagerNewPerThread.get());
        isNeedFixThisAssetManagerNewPerThread.remove();
        if (isEnableHotfix()) {
            return assetManager;
        }
        if (!isNeedFix) {
            // The host AssetManager is recreated by Apply Changes; running Flutter engines need it.
            if (FlutterAssetRefresh.shouldScheduleRefresh()) {
                FlutterAssetRefresh.scheduleRefresh();
            }
            return assetManager;
        }

        tryFixOutSideApk(assetManager);
        return assetManager;
    }

    /**
     * Apply Changes can inject the host overlay into AssetManager for non-host package resources.
     * WebView provider initialization can fail if its context contains the host overlay package id.
     * <p>
     * Solution: Keep overlays only for host APK resources and remove them from standalone package contexts.
     */
    private static boolean isNeedFixThisAssetManager(ResourcesKey resourcesKey) {
        return ApplyChangesOverlayPolicy.shouldRemoveApplyChangesOverlay(resourcesKey);
    }

    /** Path fragment shared by Apply Changes overlays and Jugg Direct overlays. */
    static final String APPLY_CHANGES_OVERLAY_MARKER = "/code_cache/.overlay/";

    private static boolean isApplyChangesOverlay(String path) {
        return path.contains(APPLY_CHANGES_OVERLAY_MARKER);
    }

    private static synchronized void logAssetManagerDecisionOnce(String hookName, String resDir, boolean shouldFix) {
        String action = shouldFix ? "fix" : "skip";
        String key = hookName + ":" + action + ":" + resDir;
        if (!loggedAssetManagerDecisions.add(key)) {
            return;
        }
        LogUtils.i(TAG, "assetManager hook action=" + action +
            ", package=" + parsePackageName(resDir) +
            ", resDir=" + resDir +
            ", hook=" + hookName);
    }

    private static String parsePackageName(String resDir) {
        if (resDir == null) {
            return "unknown";
        }
        String[] parts = resDir.split("/");
        for (String part : parts) {
            int suffixIndex = part.lastIndexOf('-');
            if (suffixIndex > 0 && part.indexOf('.') > 0) {
                return part.substring(0, suffixIndex);
            }
        }
        return resDir;
    }

    private static void tryFixOutSideApk(AssetManager assetManager) {
        try {
            Method getApkAssetsMethod = ReflectUtil.findMethod(assetManager, "getApkAssets");
            ApkAssets[] apkAssets = (ApkAssets[]) getApkAssetsMethod.invoke(assetManager);

            Method setApkAssetsMethod = ReflectUtil.findMethod(assetManager, "setApkAssets", ApkAssets[].class, boolean.class);

            ArrayList<ApkAssets> newApkAssets = new ArrayList<>();
            //noinspection DataFlowIssue
            for (ApkAssets apkAsset : apkAssets) {
                String assetPath = apkAsset.getAssetPath();
                if (!isApplyChangesOverlay(assetPath)) {
                    newApkAssets.add(apkAsset);
                }
            }

            setApkAssetsMethod.invoke(assetManager, newApkAssets.toArray(new ApkAssets[0]), false);
        } catch (Throwable e) {
            LogUtils.e(TAG, "tryFixOutSideApk failed", e);
        }
    }

    /**
     * Exit hook of ContextImpl.createPackageContext(). A FlutterEngine captures the AssetManager of
     * exactly such a package context, and that context does not inherit the overlay loaders of the
     * live Resources, so it is fixed here before anyone keeps the native handle of it.
     */
    public static Context handleCreatePackageContextExit(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || isEnableHotfix()) {
            return context;
        }
        if (FlutterAssetRefresh.shouldPrepareHostPackageContext(context)) {
            FlutterAssetRefresh.prepareHostPackageContext(context);
        }
        return context;
    }

    public static void sendMessageEnter(ActivityThread activityThread, int what, Object obj, int arg1, int arg2, boolean async) {
        if (isEnableHotfix()) {
            return;
        }
        LogUtils.d(TAG, "sendMessageEnter what: " + what);
        Android15ApplyChangesFixer.sendMessageEnter(activityThread, what, obj, arg1, arg2, async);
    }

    public static void sendMessageExit() {
        if (isEnableHotfix()) {
            return;
        }
        LogUtils.d(TAG, "sendMessageExit");
        Android15ApplyChangesFixer.sendMessageExit();
    }

    public static void handleApplicationInfoChangedExit() {
        if (isEnableHotfix()) {
            return;
        }
        LogUtils.d(TAG, "handleApplicationInfoChangedExit");
        Android15ApplyChangesFixer.restartActivityIfNeeded();
    }

    private static boolean isEnableHotfixCheckFlag = false;
    private static boolean isEnableHotfixCache = false;

    private synchronized static boolean isEnableHotfix() {
        if (HotfixLoader.overlayFilesDir == null) {
            return false;
        }
        if (!isEnableHotfixCheckFlag) {
            try {
                isEnableHotfixCache = HotfixLoader.isNeedEnableHotfix();
                isEnableHotfixCheckFlag = true;
                LogUtils.i(TAG, "isEnableHotfixCache: " + isEnableHotfixCache);
            } catch (Exception e) {
                // not enable yet
                LogUtils.i(TAG, "isEnableHotfixCache not init yet, ignore");
            }
        }
        return isEnableHotfixCache;
    }
}
