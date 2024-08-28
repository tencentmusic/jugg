package com.sickworm.intellij.jugg.hotfix;

import static android.os.Build.VERSION.SDK_INT;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.ArrayMap;
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Refer from lightning :)
 * Install overlays in code_caches
 *
 * @noinspection ALL
 */
@SuppressLint("PrivateApi")
class ResourcesPatchLoader {

    private static final String TAG = HotfixLoader.TAG + "#ResourcesPatchLoader";

    private final Context baseContext;
    private final List<File> internalResFiles;

    private static boolean isResourcesPatchesInstalled = false;

    // original object
    private Collection<WeakReference<Resources>> references = null;
    private Object currentActivityThread = null;
    private AssetManager newAssetManager = null;

    // method
    private Method addAssetPathMethod = null;
    private Method ensureStringBlocksMethod = null;

    // field
    private Field assetsField = null;
    private Field resourcesImplFiled = null;
    private Field resDir = null;
    private Field packagesFiled = null;
    private Field resourcePackagesFiled = null;
    private Field publicSourceDirField = null;
    private Field stringBlocksField = null;

    public ResourcesPatchLoader(Context base) {
        this.baseContext = base;
        this.internalResFiles = collectResourceApks();
    }

    void install() {
        LogUtils.i(TAG, "install resources, size = " + internalResFiles.size());

        try {
            prepare();
            for (File internalResFile : internalResFiles) {
                monkeyPatchExistingResources(internalResFile);
            }
        } catch (Throwable ex) {
            throw new RuntimeException(TAG + " : error while install incremental resource: ", ex);
        }

        isResourcesPatchesInstalled = true;
    }


    @SuppressLint("ObsoleteSdkInt")
    private void prepare() throws Throwable {
        LogUtils.d(TAG, "prepare...");

        //   - Replace mResDir to point to the incremental resource file instead of the .apk. This is used as the asset path for new Resources objects.
        //   - Set Application#mLoadedApk to the found LoadedApk instance

        // Find the ActivityThread instance for the current thread
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        currentActivityThread = ReflectUtil.getActivityThread(baseContext, activityThread);

        LogUtils.d(TAG, "prepare: currentActivityThread = " + currentActivityThread);

        // API version 8 has PackageInfo, 10 has LoadedApk. 9, I don't know.
        Class<?> loadedApkClass;
        try {
            loadedApkClass = Class.forName("android.app.LoadedApk");
        } catch (ClassNotFoundException e) {
            loadedApkClass = Class.forName("android.app.ActivityThread$PackageInfo");
        }

        resDir = ReflectUtil.findField(loadedApkClass, "mResDir");
        packagesFiled = ReflectUtil.findField(activityThread, "mPackages");
        if (Build.VERSION.SDK_INT < 27) {
            resourcePackagesFiled = ReflectUtil.findField(activityThread, "mResourcePackages");
        }

        LogUtils.d(TAG, "prepare: resDir = " + resDir);
        LogUtils.d(TAG, "prepare: packagesFiled = " + packagesFiled);
        LogUtils.d(TAG, "prepare: resourcePackagesFiled = " + resourcePackagesFiled);

        // Create a new AssetManager instance and point it to the resources
        final AssetManager assets = baseContext.getAssets();
        addAssetPathMethod = ReflectUtil.findMethod(assets, "addAssetPath", String.class);

        LogUtils.d(TAG, "prepare: origin assets = " + assets);

        // Kitkat needs this method call, Lollipop doesn't. However, it doesn't seem to cause any harm
        // in L, so we do it unconditionally.
        try {
            stringBlocksField = ReflectUtil.findField(assets, "mStringBlocks");
            ensureStringBlocksMethod = ReflectUtil.findMethod(assets, "ensureStringBlocks");
        } catch (Throwable ignored) {
            // Ignored.
        }

        // Use class fetched from instance to avoid some ROMs that use customized AssetManager
        // class. (e.g. Baidu OS)
        newAssetManager = (AssetManager) ReflectUtil.findConstructor(assets).newInstance();

        LogUtils.d(TAG, "prepare: new assets = " + newAssetManager);

        // Iterate over all known Resources objects
        if (SDK_INT >= Build.VERSION_CODES.KITKAT) {
            //pre-N
            // Find the singleton instance of ResourcesManager
            final Class<?> resourcesManagerClass = Class.forName("android.app.ResourcesManager");
            final Method mGetInstance = ReflectUtil.findMethod(resourcesManagerClass, "getInstance");
            final Object resourcesManager = mGetInstance.invoke(null);
            try {
                Field fMActiveResources = ReflectUtil.findField(resourcesManagerClass, "mActiveResources");
                final ArrayMap<?, WeakReference<Resources>> activeResources19 = (ArrayMap<?, WeakReference<Resources>>) fMActiveResources.get(resourcesManager);
                references = activeResources19.values();
            } catch (NoSuchFieldException ignore) {
                // N moved the resources to mResourceReferences
                final Field mResourceReferences = ReflectUtil.findField(resourcesManagerClass, "mResourceReferences");
                references = (Collection<WeakReference<Resources>>) mResourceReferences.get(resourcesManager);
            }
        } else {
            final Field fMActiveResources = ReflectUtil.findField(activityThread, "mActiveResources");
            final HashMap<?, WeakReference<Resources>> activeResources7 =
                    (HashMap<?, WeakReference<Resources>>) fMActiveResources.get(currentActivityThread);
            references = activeResources7.values();
        }
        // check resource
        if (references == null) {
            throw new IllegalStateException("resource references is null");
        }

        LogUtils.i(TAG, "prepare: finish collecting all Resources.");
        for (WeakReference<Resources> wr : references) {
            if (wr != null) {
                LogUtils.i(TAG, "prepare: each Resources = " + wr.get());
            }
        }

        final Resources resources = baseContext.getResources();

        // fix jianGuo pro has private field 'mAssets' with Resource
        // try use mResourcesImpl first
        if (SDK_INT >= Build.VERSION_CODES.N) {
            try {
                // N moved the mAssets inside an mResourcesImpl field
                resourcesImplFiled = ReflectUtil.findField(resources, "mResourcesImpl");
            } catch (Throwable ignore) {
                // for safety
                assetsField = ReflectUtil.findField(resources, "mAssets");
            }
        } else {
            assetsField = ReflectUtil.findField(resources, "mAssets");
        }

        try {
            publicSourceDirField = ReflectUtil.findField(ApplicationInfo.class, "publicSourceDir");
        } catch (NoSuchFieldException ignore) {
            // Ignored.
        }
    }

    private List<File> collectResourceApks() {
        List<File> internalResFile = new ArrayList<>();
        if (!HotfixLoader.overlayFilesDir.exists()) {
            return new ArrayList<>();
        }
        File[] files = HotfixLoader.overlayFilesDir.listFiles();
        for (File file : files) {
            if (file.isDirectory()) {
                File resourceApkFile = new File(file, BuildConfig.RESOURCE_APK_NAME);
                LogUtils.d(TAG, "collectResourceApks finding " + resourceApkFile);
                if (resourceApkFile.exists()) {
                    internalResFile.add(resourceApkFile);
                }
            }
        }
        return internalResFile;
    }

    // internalResFile
    private void monkeyPatchExistingResources(File internalResFile) throws Throwable {

        LogUtils.i(TAG, "monkeyPatchExistingResources...");

        if (!internalResFile.exists()) {
            LogUtils.i(TAG, "monkeyPatchExistingResources: internal incremental resources file is NOT exist");
            return;
        }
        if (!internalResFile.isFile()) {
            throw new IllegalStateException("internal incremental resources file is not a file");
        }

        final ApplicationInfo applicationInfo = baseContext.getApplicationInfo();

        LogUtils.d(TAG, "monkeyPatchExistingResources: applicationInfo = " + applicationInfo);

        final Field[] packagesFields;
        if (Build.VERSION.SDK_INT < 27) {
            packagesFields = new Field[] { packagesFiled, resourcePackagesFiled };
        } else {
            packagesFields = new Field[] { packagesFiled };
        }

        LogUtils.d(TAG, "monkeyPatchExistingResources: packagesFields = " + packagesFields);
        LogUtils.d(TAG, "monkeyPatchExistingResources: start replacing all resDir field for LoadedApk...");
        for (Field field : packagesFields) {

            final Object value = field.get(currentActivityThread);
            LogUtils.d(TAG, "monkeyPatchExistingResources:     |__ replacing for field = " + field + ", current field value = " + value);

            for (Map.Entry<String, WeakReference<?>> entry : ((Map<String, WeakReference<?>>) value).entrySet()) {
                final Object loadedApk = entry.getValue().get();
                LogUtils.d(TAG, "monkeyPatchExistingResources:         |__ loadedApk = " + loadedApk);
                if (loadedApk == null) {
                    continue;
                }
                final String resDirPath = (String) resDir.get(loadedApk);
                LogUtils.d(TAG, "monkeyPatchExistingResources:         |__ loadedApk.resDir = " + resDirPath);
                if (applicationInfo.sourceDir.equals(resDirPath)) {
                    resDir.set(loadedApk, internalResFile.getAbsolutePath());
                    LogUtils.d(TAG, "monkeyPatchExistingResources:         |__ loadedApk.resDir updated to : " + internalResFile.getAbsolutePath());
                }
            }
        }

        LogUtils.d(TAG, "monkeyPatchExistingResources: try to call AssetManager.setApkAssets()...");

        // Create a new AssetManager instance and point it to the resources installed under
        if (((Integer) addAssetPathMethod.invoke(newAssetManager, internalResFile.getAbsolutePath())) == 0) {
            throw new IllegalStateException("Could not create new AssetManager");
        }

        try {
            LogUtils.d(TAG, "monkeyPatchExistingResources: try to call AssetManager.ensureStringBlocks()...");
            // Kitkat needs this method call, Lollipop doesn't. However, it doesn't seem to cause any harm
            // in L, so we do it unconditionally.
            if (stringBlocksField != null && ensureStringBlocksMethod != null) {
                stringBlocksField.set(newAssetManager, null);
                ensureStringBlocksMethod.invoke(newAssetManager);
            }
        } catch (Throwable ex) {
            LogUtils.d(TAG, "monkeyPatchExistingResources: error while invoke AssetManager.ensureStringBlocks(): " + ex.getMessage());
        }

        LogUtils.i(TAG, "monkeyPatchExistingResources: start replacing for all resources...");
        for (WeakReference<Resources> wr : references) {
            if (wr != null) {
                LogUtils.d(TAG, "monkeyPatchExistingResources: each Resources = " + wr.get());
            }
        }

        for (WeakReference<Resources> wr : references) {

            final Resources resources = wr.get();
            LogUtils.d(TAG, "monkeyPatchExistingResources: patching resources : " + resources);
            if (resources == null) {
                continue;
            }
            // Set the AssetManager of the Resources instance to our brand new one
            try {
                //pre-N
                LogUtils.d(TAG, "monkeyPatchExistingResources:     [ attempt 1 ]set the AssetManager of the Resources instance to our brand new one");
                assetsField.set(resources, newAssetManager);
                LogUtils.d(TAG, "monkeyPatchExistingResources:     [ attempt 1 ] success");
            } catch (Throwable ignore) {
                LogUtils.d(TAG, "monkeyPatchExistingResources:     [ attempt 1 ] failed: " + ignore);
                try {
                    LogUtils.d(TAG, "monkeyPatchExistingResources:     [ attempt 2 ] set the AssetManager of the Resources instance to our brand new one");
                    // N
                    final Object resourceImpl = resourcesImplFiled.get(resources);
                    // for Huawei HwResourcesImpl
                    final Field implAssets = ReflectUtil.findField(resourceImpl, "mAssets");
                    implAssets.set(resourceImpl, newAssetManager);
                    LogUtils.d(TAG, "monkeyPatchExistingResources:     [ attempt 2 ] success");
                } catch (Throwable ignore2) {
                    LogUtils.d(TAG, "monkeyPatchExistingResources:     [ attempt 2 ] failed: " + ignore2);
                    throw new RuntimeException(TAG + " : monkeyPatchExistingResources: Error after 2 attempts to hook resources", ignore2);
                }
            }

            clearPreloadTypedArrayIssue(resources);

            resources.updateConfiguration(resources.getConfiguration(), resources.getDisplayMetrics());
        }

        // Handle issues caused by WebView on Android N.
        // Issue: On Android N, if an activity contains a webview, when screen rotates
        // our resource patch may lost effects.
        // for 5.x/6.x, we found Couldn't expand RemoteView for StatusBarNotification Exception
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                if (publicSourceDirField != null) {
                    publicSourceDirField.set(applicationInfo, internalResFile.getAbsolutePath());
                }
            } catch (Throwable ignore) {
                // Ignored.
            }
        }
    }

    /**
     * Why must I do these?
     * Resource has mTypedArrayPool field, which just like Message Poll to reduce gc
     * MiuiResource change TypedArray to MiuiTypedArray, but it get string block from offset instead of assetManager
     */
    private static void clearPreloadTypedArrayIssue(Resources resources) {
        // Perform this trick not only in Miui system since we can't predict if any other
        // manufacturer would do the same modification to Android.
        // if (!isMiuiSystem) {
        //     return;
        // }
        LogUtils.i(TAG, "clearPreloadTypedArrayIssue: try to clear typedArray cache!");
        // Clear typedArray cache.
        try {
            final Field typedArrayPoolField = ReflectUtil.findField(Resources.class, "mTypedArrayPool");
            final Object origTypedArrayPool = typedArrayPoolField.get(resources);
            final Method acquireMethod = ReflectUtil.findMethod(origTypedArrayPool, "acquire");
            while (true) {
                if (acquireMethod.invoke(origTypedArrayPool) == null) {
                    break;
                }
            }
            LogUtils.i(TAG, "clearPreloadTypedArrayIssue: clear typedArray cache finish!");
        } catch (Throwable ignored) {
            LogUtils.e(TAG, "clearPreloadTypedArrayIssue failed, ignore error: " + ignored);
        }
    }


    public static boolean isResourcesPatchesInstalled() {
        return isResourcesPatchesInstalled;
    }
}
