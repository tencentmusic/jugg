package com.sickworm.intellij.jugg.instrument;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.ResourcesKey;
import com.sickworm.intellij.jugg.hotfix.HotfixLoader;
import com.sickworm.intellij.jugg.hotfix.LogUtils;
import com.sickworm.intellij.jugg.hotfix.ReflectUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;

/** @noinspection unused*/
public class InstrumentationHooks {

    public static final String TAG = "jugg-jvmti";
    private static final HashSet<String> loggedAssetManagerDecisions = new HashSet<>();

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

    private static boolean isNeedFixThisAssetManager = false;

    public static void createAssetManagerEnter(ResourcesManager assetManager, ResourcesKey resourcesKey) {
        if (isEnableHotfix()) {
            return;
        }
        String resDir = resourcesKey.mResDir;
        isNeedFixThisAssetManager = isNeedFixThisAssetManager(resourcesKey);
        logAssetManagerDecisionOnce("createAssetManager", resDir, isNeedFixThisAssetManager);
    }

    public static AssetManager createAssetManagerExit(AssetManager assetManager) {
        if (isEnableHotfix()) {
            return assetManager;
        }
        if (isNeedFixThisAssetManager) {
            tryFixOutSideApk(assetManager);
        }
        return assetManager;
    }

    private static boolean isNeedFixThisAssetManagerNew = false;

    public static void createAssetManagerNewEnter(ResourcesManager assetManager, ResourcesKey resourcesKey, ResourcesManager.ApkAssetsSupplier apkAssetsSupplier) {
        if (isEnableHotfix()) {
            return;
        }
        String resDir = resourcesKey.mResDir;
        isNeedFixThisAssetManagerNew = isNeedFixThisAssetManager(resourcesKey);
        logAssetManagerDecisionOnce("createAssetManagerNew", resDir, isNeedFixThisAssetManagerNew);
    }

    public static AssetManager createAssetManagerNewExit(AssetManager assetManager) {
        if (!isNeedFixThisAssetManagerNew) {
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
