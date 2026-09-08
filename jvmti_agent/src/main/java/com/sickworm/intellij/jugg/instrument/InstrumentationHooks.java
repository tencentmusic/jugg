package com.sickworm.intellij.jugg.instrument;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.ResourcesKey;
import android.util.SparseArray;
import com.sickworm.intellij.jugg.hotfix.HotfixLoader;
import com.sickworm.intellij.jugg.hotfix.LogUtils;
import com.sickworm.intellij.jugg.hotfix.ReflectUtil;
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/** @noinspection unused*/
public class InstrumentationHooks {

    public static final String TAG = "jugg-jvmti";
    private static final HashSet<String> loggedAssetManagerDecisions = new HashSet<>();
    private static final AtomicBoolean classpathResourceHookEntered = new AtomicBoolean();
    private static volatile ClassLoader classpathResourceHostClassLoader;
    private static volatile File classpathResourceOverlayRoot;
    private static final ThreadLocal<ResourcesKey> createAssetManagerResourcesKey = new ThreadLocal<>();
    private static final ThreadLocal<ResourcesKey> createAssetManagerNewResourcesKey = new ThreadLocal<>();

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
            if (isNeedFix) {
                HotfixLoader.init(base);
                HotfixLoader.installDex(base);
                LogUtils.i(TAG, "handleAttachBaseContextEntry fix finished");
            }
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
            if (isNeedFix) {
                HotfixLoader.init(base);
                HotfixLoader.installDex(base);
                InstrumentationHooks.base = base;
                LogUtils.i(TAG, "handleAttachBaseContextEntry fix finished");
            }
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
            if (isNeedFix) {
                HotfixLoader.init(base);
                HotfixLoader.install(base);
                InstrumentationHooks.base = base;
                LogUtils.i(TAG, "handleAttachBaseContextEntry2 fix finished");
            }
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

    private static boolean isNeedFixThisAssetManager = false;

    public static void createAssetManagerEnter(ResourcesManager assetManager, ResourcesKey resourcesKey) {
        createAssetManagerResourcesKey.set(resourcesKey);
        if (isEnableHotfix()) {
            return;
        }
        String resDir = resourcesKey.mResDir;
        isNeedFixThisAssetManager = isNeedFixThisAssetManager(resourcesKey);
        logAssetManagerDecisionOnce("createAssetManager", resDir, isNeedFixThisAssetManager);
    }

    public static AssetManager createAssetManagerExit(AssetManager assetManager) {
        ResourcesKey resourcesKey = takeResourcesKey(createAssetManagerResourcesKey);
        if (isEnableHotfix()) {
            repairMissingWebViewPackageId(assetManager, resourcesKey, "createAssetManager");
            return assetManager;
        }
        if (isNeedFixThisAssetManager) {
            tryFixOutSideApk(assetManager, resourcesKey, "createAssetManager");
        }
        repairMissingWebViewPackageId(assetManager, resourcesKey, "createAssetManager");
        return assetManager;
    }

    private static boolean isNeedFixThisAssetManagerNew = false;

    public static void createAssetManagerNewEnter(ResourcesManager assetManager, ResourcesKey resourcesKey, ResourcesManager.ApkAssetsSupplier apkAssetsSupplier) {
        createAssetManagerNewResourcesKey.set(resourcesKey);
        if (isEnableHotfix()) {
            return;
        }
        String resDir = resourcesKey.mResDir;
        isNeedFixThisAssetManagerNew = isNeedFixThisAssetManager(resourcesKey);
        logAssetManagerDecisionOnce("createAssetManagerNew", resDir, isNeedFixThisAssetManagerNew);
    }

    public static AssetManager createAssetManagerNewExit(AssetManager assetManager) {
        ResourcesKey resourcesKey = takeResourcesKey(createAssetManagerNewResourcesKey);
        if (isEnableHotfix()) {
            repairMissingWebViewPackageId(assetManager, resourcesKey, "createAssetManagerNew");
            return assetManager;
        }
        if (isNeedFixThisAssetManagerNew) {
            tryFixOutSideApk(assetManager, resourcesKey, "createAssetManagerNew");
        }
        repairMissingWebViewPackageId(assetManager, resourcesKey, "createAssetManagerNew");
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

    private static boolean isApplyChangesOverlay(String path) {
        return path.contains("/code_cache/.overlay/");
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

    private static void tryFixOutSideApk(AssetManager assetManager, ResourcesKey resourcesKey, String hookName) {
        try {
            logWebViewAssetState(assetManager, resourcesKey, hookName, "before-overlay-fix");
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
            logWebViewAssetState(assetManager, resourcesKey, hookName, "after-overlay-fix");
        } catch (Throwable e) {
            LogUtils.e(TAG, "tryFixOutSideApk failed", e);
        }
    }

    private static ResourcesKey takeResourcesKey(ThreadLocal<ResourcesKey> holder) {
        ResourcesKey resourcesKey = holder.get();
        holder.remove();
        return resourcesKey;
    }

    /**
     * Restores the WebView package id only when Android already supplied its APK as a shared library.
     */
    private static void repairMissingWebViewPackageId(AssetManager assetManager, ResourcesKey resourcesKey, String hookName) {
        String[] webViewAssetPaths = getWebViewAssetPaths(resourcesKey);
        if (webViewAssetPaths.length == 0) {
            return;
        }
        try {
            String webViewPackageName = getWebViewPackageName(webViewAssetPaths[0]);
            SparseArray<String> assignedPackages = getAssignedPackageIdentifiers(assetManager);
            logWebViewAssetState(assetManager, resourcesKey, hookName, "before-package-id-repair");
            if (webViewPackageName == null || containsPackage(assignedPackages, webViewPackageName)) {
                return;
            }

            Method addSharedLibrary = ReflectUtil.findMethod(
                    assetManager, "addAssetPathAsSharedLibrary", String.class);
            for (String webViewAssetPath : webViewAssetPaths) {
                int cookie = (Integer) addSharedLibrary.invoke(assetManager, webViewAssetPath);
                LogUtils.i(TAG, "WebView package id repair path=" + webViewAssetPath +
                        ", package=" + webViewPackageName + ", cookie=" + cookie + ", hook=" + hookName);
                assignedPackages = getAssignedPackageIdentifiers(assetManager);
                if (containsPackage(assignedPackages, webViewPackageName)) {
                    break;
                }
            }
            logWebViewAssetState(assetManager, resourcesKey, hookName, "after-package-id-repair");
        } catch (Throwable e) {
            LogUtils.w(TAG, "WebView package id repair failed, hook=" + hookName + ", cause=" + e);
        }
    }

    private static void logWebViewAssetState(AssetManager assetManager, ResourcesKey resourcesKey,
            String hookName, String phase) {
        String[] webViewAssetPaths = getWebViewAssetPaths(resourcesKey);
        if (webViewAssetPaths.length == 0) {
            return;
        }
        try {
            LogUtils.i(TAG, "WebView asset state phase=" + phase + ", hook=" + hookName +
                    ", resDir=" + (resourcesKey == null ? null : resourcesKey.mResDir) +
                    ", libDirs=" + Arrays.toString(readStringArray(resourcesKey, "mLibDirs")) +
                    ", overlayPaths=" + Arrays.toString(readStringArray(resourcesKey, "mOverlayPaths")) +
                    ", assignedPackages=" + getAssignedPackageIdentifiers(assetManager) +
                    ", apkAssets=" + Arrays.toString(getAssetPaths(assetManager)));
        } catch (Throwable e) {
            LogUtils.w(TAG, "WebView asset state failed, phase=" + phase + ", hook=" + hookName +
                    ", cause=" + e);
        }
    }

    private static String[] getWebViewAssetPaths(ResourcesKey resourcesKey) {
        String[] libDirs = readStringArray(resourcesKey, "mLibDirs");
        if (libDirs == null) {
            return new String[0];
        }
        ArrayList<String> paths = new ArrayList<>();
        for (String libDir : libDirs) {
            if (libDir != null && libDir.endsWith(".apk") &&
                    libDir.toLowerCase(Locale.ROOT).contains("webview")) {
                paths.add(libDir);
            }
        }
        return paths.toArray(new String[0]);
    }

    private static String[] readStringArray(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            return (String[]) ReflectUtil.findField(target, fieldName).get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static SparseArray<String> getAssignedPackageIdentifiers(AssetManager assetManager) throws Exception {
        Method method = ReflectUtil.findMethod(assetManager, "getAssignedPackageIdentifiers");
        return (SparseArray<String>) method.invoke(assetManager);
    }

    private static String[] getAssetPaths(AssetManager assetManager) throws Exception {
        Method method = ReflectUtil.findMethod(assetManager, "getApkAssets");
        ApkAssets[] apkAssets = (ApkAssets[]) method.invoke(assetManager);
        String[] paths = new String[apkAssets.length];
        for (int index = 0; index < apkAssets.length; index++) {
            paths[index] = apkAssets[index].getAssetPath();
        }
        return paths;
    }

    private static String getWebViewPackageName(String webViewAssetPath) {
        try {
            Class<?> webViewFactory = Class.forName("android.webkit.WebViewFactory");
            Method method = ReflectUtil.findMethod(webViewFactory, "getWebViewPackageName");
            return (String) method.invoke(null);
        } catch (Throwable ignored) {
            return parseInstalledPackageName(webViewAssetPath);
        }
    }

    private static String parseInstalledPackageName(String assetPath) {
        if (assetPath == null) {
            return null;
        }
        for (String part : assetPath.split("/")) {
            int suffixIndex = part.lastIndexOf('-');
            if (suffixIndex > 0 && part.indexOf('.') > 0) {
                return part.substring(0, suffixIndex);
            }
        }
        return null;
    }

    private static boolean containsPackage(SparseArray<String> packageIdentifiers, String packageName) {
        for (int index = 0; index < packageIdentifiers.size(); index++) {
            if (packageName.equals(packageIdentifiers.valueAt(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Repairs the exact AssetManager used by WebView package id lookup and records object identity.
     */
    public static void webViewGetPackageIdEnter(Object webViewDelegate, Resources resources,
            String packageName) {
        if (resources == null || packageName == null) {
            return;
        }
        AssetManager assetManager = resources.getAssets();
        try {
            SparseArray<String> assignedPackages = getAssignedPackageIdentifiers(assetManager);
            String[] webViewAssetPaths = getLoadedWebViewAssetPaths(packageName);
            logWebViewPackageIdState("before", webViewDelegate, resources, assetManager, packageName,
                    assignedPackages, webViewAssetPaths);
            if (containsPackage(assignedPackages, packageName)) {
                return;
            }

            Method addSharedLibrary = ReflectUtil.findMethod(
                    assetManager, "addAssetPathAsSharedLibrary", String.class);
            for (String webViewAssetPath : webViewAssetPaths) {
                try {
                    int cookie = (Integer) addSharedLibrary.invoke(assetManager, webViewAssetPath);
                    LogUtils.i(TAG, "WebView getPackageId repair path=" + webViewAssetPath +
                            ", package=" + packageName + ", cookie=" + cookie +
                            ", resources=" + identity(resources) +
                            ", assets=" + identity(assetManager));
                    assignedPackages = getAssignedPackageIdentifiers(assetManager);
                    if (containsPackage(assignedPackages, packageName)) {
                        break;
                    }
                } catch (Throwable e) {
                    LogUtils.w(TAG, "WebView getPackageId repair path failed, path=" + webViewAssetPath +
                            ", package=" + packageName + ", cause=" + e);
                }
            }
            logWebViewPackageIdState("after", webViewDelegate, resources, assetManager, packageName,
                    assignedPackages, webViewAssetPaths);
        } catch (Throwable e) {
            LogUtils.w(TAG, "WebView getPackageId repair failed, package=" + packageName +
                    ", resources=" + identity(resources) +
                    ", assets=" + identity(assetManager) + ", cause=" + e);
        }
    }

    private static String[] getLoadedWebViewAssetPaths(String packageName) throws Exception {
        Class<?> webViewFactory = Class.forName("android.webkit.WebViewFactory");
        Method getLoadedPackageInfo = ReflectUtil.findMethod(webViewFactory, "getLoadedPackageInfo");
        PackageInfo packageInfo = (PackageInfo) getLoadedPackageInfo.invoke(null);
        if (packageInfo == null || !packageName.equals(packageInfo.packageName) ||
                packageInfo.applicationInfo == null) {
            return new String[0];
        }

        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        try {
            Method getAllApkPaths = ReflectUtil.findMethod(applicationInfo, "getAllApkPaths");
            String[] paths = (String[]) getAllApkPaths.invoke(applicationInfo);
            return paths == null ? new String[0] : paths;
        } catch (Throwable ignored) {
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            addPath(paths, applicationInfo.sourceDir);
            addPaths(paths, applicationInfo.splitSourceDirs);
            addPaths(paths, applicationInfo.sharedLibraryFiles);
            return paths.toArray(new String[0]);
        }
    }

    private static void addPaths(LinkedHashSet<String> paths, String[] values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addPath(paths, value);
        }
    }

    private static void addPath(LinkedHashSet<String> paths, String value) {
        if (value != null && !value.isEmpty()) {
            paths.add(value);
        }
    }

    private static void logWebViewPackageIdState(String phase, Object webViewDelegate,
            Resources resources, AssetManager assetManager, String packageName,
            SparseArray<String> assignedPackages, String[] webViewAssetPaths) {
        try {
            Application application = getCurrentApplication();
            Resources applicationResources = application == null ? null : application.getResources();
            AssetManager applicationAssets = applicationResources == null ? null :
                    applicationResources.getAssets();
            LogUtils.i(TAG, "WebView getPackageId state phase=" + phase +
                    ", delegate=" + identity(webViewDelegate) +
                    ", package=" + packageName +
                    ", resources=" + identity(resources) +
                    ", assets=" + identity(assetManager) +
                    ", application=" + identity(application) +
                    ", applicationResources=" + identity(applicationResources) +
                    ", applicationAssets=" + identity(applicationAssets) +
                    ", assignedPackages=" + assignedPackages +
                    ", repairPaths=" + Arrays.toString(webViewAssetPaths) +
                    ", apkAssets=" + Arrays.toString(getAssetPaths(assetManager)));
        } catch (Throwable e) {
            LogUtils.w(TAG, "WebView getPackageId state failed, phase=" + phase +
                    ", package=" + packageName + ", cause=" + e);
        }
    }

    private static Application getCurrentApplication() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method currentApplication = ReflectUtil.findMethod(activityThread, "currentApplication");
        return (Application) currentApplication.invoke(null);
    }

    private static String identity(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
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
