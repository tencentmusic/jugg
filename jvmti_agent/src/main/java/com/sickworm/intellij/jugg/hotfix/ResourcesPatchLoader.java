package com.sickworm.intellij.jugg.hotfix;

import static android.os.Build.VERSION.SDK_INT;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.ArrayMap;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Install resources.ap_ on sd-card while runtime
 */
class ResourcesPatchLoader {

    private static final String TAG = IncrementalCompile.TAG + "#ResourcesPatchLoader";
    public static final String RESOURCES_FILE_NAME = "resources.ap_";
    public static final String INTERNAL_RES_DIR_NAME = "res";

    private final Context baseContext;
    private final Application application;
    private final File incrementalDir;
    private final File internalResDir;
    private final File internalResFile;
    private final File incrementalResFile;

    private static boolean isResourcesPatchesInstalled = false;

    // original object
    private Collection<WeakReference<Resources>> references = null;
    private Object currentActivityThread = null;
    private AssetManager newAssetManager = null;

    // method
    private Method addAssetPathMethod = null;
    private Method ensureStringBlocksMethod = null;

    // field
    private Field assetsFiled = null;
    private Field resourcesImplFiled = null;
    private Field resDir = null;
    private Field packagesFiled = null;
    private Field resourcePackagesFiled = null;
    private Field publicSourceDirField = null;
    private Field stringBlocksField = null;

    static ResourcesPatchLoader create(Application application, Context base) {
        return new ResourcesPatchLoader(application, base);
    }

    private ResourcesPatchLoader(Application application, Context base) {
        if (application == null || base == null) {
            throw new IllegalArgumentException("Error while create instance of ResourcesPatchLoader , application == null || base == null");
        }
        this.baseContext = base;
        this.application = application;
        this.incrementalDir = new File(IncrementalCompile.rootDir(), IncrementalCompile.INCREMENTAL_DIR_NAME);

        L.i(TAG, "ResourcesPatchLoader: rootDir: " + IncrementalCompile.rootDir());
        L.i(TAG, "ResourcesPatchLoader: rootDir.exist: " + IncrementalCompile.rootDir().exists());

        String internalDir = IncrementalCompile.baseApkStamp();
        File internalIncrementalResDir = new File(base.getFilesDir(),internalDir);

        if(!internalIncrementalResDir.exists()) {
            L.i(TAG, "install: internalIncrementalResDir not exist, try to make directory : " + internalIncrementalResDir);
            if (!internalIncrementalResDir.mkdir()) {
                throw new RuntimeException("Failed to create directory : " + internalIncrementalResDir);
            }
        }

        internalResDir = new File(internalIncrementalResDir, INTERNAL_RES_DIR_NAME);
        internalResFile = new File(internalResDir, RESOURCES_FILE_NAME);
        incrementalResFile = new File(incrementalDir, RESOURCES_FILE_NAME);
    }

    private boolean installFromCIBuild(Context base) {


        if(!internalResDir.exists()) {
            L.i(TAG, "install: internalResDir not exist, try to make directory : " + internalResDir);
            if (!internalResDir.mkdir()) {
                throw new RuntimeException("Failed to create directory : " + internalResDir);
            }
        }

        File internalResFile = new File(internalResDir, ResourcesPatchLoader.RESOURCES_FILE_NAME);

        try {

            if (internalResFile.exists()) {
                boolean ret = internalResFile.delete();
                if (!ret) {
                    Log.i(IncrementalCompile.TAG, "installFromCIBuild copyFile: error while deleting existing file");
                    return false;
                } else {
                    Log.i(IncrementalCompile.TAG, "installFromCIBuild copyFile: deleting existing file success");
                }
            }

            try {
                boolean ret = internalResFile.createNewFile();
                if (!ret) {
                    Log.i(IncrementalCompile.TAG, "installFromCIBuild copyFile: error while create new file");
                    return false;
                }
            } catch (IOException e) {
                Log.i(IncrementalCompile.TAG,
                        "installFromCIBuild copyFile: error while create new file: " + e.getMessage());
                return false;
            }


            FileUtil.copyFromAssetsToFile(base,IncrementalCompile.INCREMENTAL_DIR_NAME+"/"+ResourcesPatchLoader.RESOURCES_FILE_NAME,internalResFile);
        }catch (Exception ex) {
            L.e(TAG, "copyFromAssetsToFile error",ex);
            return true;
        }
        return false;
    }

    private boolean installFromLocalBuild() {
        if (!incrementalDir.exists() || !incrementalDir.isDirectory() || !incrementalResFile.exists()) {
            L.i(TAG, "install: incremental resources.ap_ not exist on sdcard, skip install, clear pre-installed resources");
            if (internalResDir.exists()) {
                L.i(TAG, "install: internal res dir exist , delete it : " + internalResDir);
                FileUtil.deleteAllFilesUnderDirectory(internalResDir);
            }
            return true;
        }

        if (!internalResDir.exists()) {
            L.i(TAG, "install: internal res dir not exist , try to create it : " + internalResDir);
            if (!internalResDir.mkdir()) {
                throw new RuntimeException(TAG + " : error while create directory : " + incrementalDir);
            }
        }

        L.i(TAG, "install: copy resources.ap_ from sdcard to app internal directory, FROM: " + incrementalResFile + ", TO: " + internalResDir);

        boolean ret = FileUtil.copyFile(incrementalResFile, internalResDir);

        L.i(TAG, "install: copy resources.ap_ result =  " + ret);

        if (!ret || !internalResFile.exists()) {
            throw new RuntimeException(TAG + " : error while copy incremental resources file , FROM: " + incrementalResFile + ", TO: " + internalResDir);
        }
        return false;
    }

    void install() {

        L.i(TAG, "install resources ...");

        boolean breakEarly;

        if(IncrementalCompile.isCIBuild()) {
            breakEarly = installFromCIBuild(baseContext);
        }else {
            breakEarly = installFromLocalBuild();
        }

        if(breakEarly) {
            return;
        }

        try {
            prepare();
            monkeyPatchExistingResources();
        } catch (Throwable ex) {
            throw new RuntimeException(TAG + " : error while install incremental resource: " , ex);
        }

        isResourcesPatchesInstalled = true;
    }


    private void prepare() throws Throwable {

        L.i(TAG, "prepare...");

        //   - Replace mResDir to point to the incremental resource file instead of the .apk. This is used as the asset path for new Resources objects.
        //   - Set Application#mLoadedApk to the found LoadedApk instance

        // Find the ActivityThread instance for the current thread
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        currentActivityThread = ReflectUtil.getActivityThread(baseContext, activityThread);

        L.i(TAG, "prepare: currentActivityThread = " + currentActivityThread);

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

        L.i(TAG, "prepare: resDir = " + resDir);
        L.i(TAG, "prepare: packagesFiled = " + packagesFiled);
        L.i(TAG, "prepare: resourcePackagesFiled = " + resourcePackagesFiled);

        // Create a new AssetManager instance and point it to the resources
        final AssetManager assets = baseContext.getAssets();
        addAssetPathMethod = ReflectUtil.findMethod(assets, "addAssetPath", String.class);

        L.i(TAG, "prepare: origin assets = " + assets);

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

        L.i(TAG, "prepare: new assets = " + newAssetManager);

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

        L.i(TAG, "prepare: finish collecting all Resources.");
        for (WeakReference<Resources> wr : references) {
            if (wr != null) {
                L.i(TAG, "prepare: each Resources = " + wr.get());
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
                assetsFiled = ReflectUtil.findField(resources, "mAssets");
            }
        } else {
            assetsFiled = ReflectUtil.findField(resources, "mAssets");
        }

        try {
            publicSourceDirField = ReflectUtil.findField(ApplicationInfo.class, "publicSourceDir");
        } catch (NoSuchFieldException ignore) {
            // Ignored.
        }
    }

    // internalResFile
    private void monkeyPatchExistingResources() throws Throwable {

        L.i(TAG, "monkeyPatchExistingResources...");

        if (internalResFile == null || !internalResFile.isFile() || !internalResFile.exists()) {
            L.i(TAG, "monkeyPatchExistingResources: internal incremental resources file is NOT exist");
            return;
        }

        final ApplicationInfo applicationInfo = baseContext.getApplicationInfo();

        L.i(TAG, "monkeyPatchExistingResources: applicationInfo = " + applicationInfo);

        final Field[] packagesFields;
        if (Build.VERSION.SDK_INT < 27) {
            packagesFields = new Field[]{packagesFiled, resourcePackagesFiled};
        } else {
            packagesFields = new Field[]{packagesFiled};
        }

        L.i(TAG, "monkeyPatchExistingResources: packagesFields = " + packagesFields);
        L.i(TAG, "monkeyPatchExistingResources: start replacing all resDir field for LoadedApk...");
        for (Field field : packagesFields) {

            final Object value = field.get(currentActivityThread);
            L.i(TAG, "monkeyPatchExistingResources:     |__ replacing for field = " + field + ", current field value = " + value);

            for (Map.Entry<String, WeakReference<?>> entry : ((Map<String, WeakReference<?>>) value).entrySet()) {
                final Object loadedApk = entry.getValue().get();
                L.i(TAG, "monkeyPatchExistingResources:         |__ loadedApk = " + loadedApk);
                if (loadedApk == null) {
                    continue;
                }
                final String resDirPath = (String) resDir.get(loadedApk);
                L.i(TAG, "monkeyPatchExistingResources:         |__ loadedApk.resDir = " + resDirPath);
                if (applicationInfo.sourceDir.equals(resDirPath)) {
                    resDir.set(loadedApk, internalResFile.getAbsolutePath());
                    L.i(TAG, "monkeyPatchExistingResources:         |__ loadedApk.resDir updated to : " + internalResFile.getAbsolutePath());
                }
            }
        }

        L.i(TAG, "monkeyPatchExistingResources: try to call AssetManager.addAssetPath()...");

        // Create a new AssetManager instance and point it to the resources installed under
        if (((Integer) addAssetPathMethod.invoke(newAssetManager, internalResFile.getAbsolutePath())) == 0) {
            throw new IllegalStateException("Could not create new AssetManager");
        }

        try {
            L.i(TAG, "monkeyPatchExistingResources: try to call AssetManager.ensureStringBlocks()...");
            // Kitkat needs this method call, Lollipop doesn't. However, it doesn't seem to cause any harm
            // in L, so we do it unconditionally.
            if (stringBlocksField != null && ensureStringBlocksMethod != null) {
                stringBlocksField.set(newAssetManager, null);
                ensureStringBlocksMethod.invoke(newAssetManager);
            }
        } catch (Throwable ex) {
            L.i(TAG, "monkeyPatchExistingResources: error while invoke AssetManager.ensureStringBlocks(): " + ex.getMessage());
        }

        L.i(TAG, "monkeyPatchExistingResources: start replacing for all resources...");
        for (WeakReference<Resources> wr : references) {
            if (wr != null) {
                L.i(TAG, "monkeyPatchExistingResources: each Resources = " + wr.get());
            }
        }

        for (WeakReference<Resources> wr : references) {

            final Resources resources = wr.get();
            L.i(TAG, "monkeyPatchExistingResources: patching resources : " + resources);
            if (resources == null) {
                continue;
            }
            // Set the AssetManager of the Resources instance to our brand new one
            try {
                //pre-N
                L.i(TAG, "monkeyPatchExistingResources:     [ attempt 1 ]set the AssetManager of the Resources instance to our brand new one");
                assetsFiled.set(resources, newAssetManager);
                L.i(TAG, "monkeyPatchExistingResources:     [ attempt 1 ] success");
            } catch (Throwable ignore) {
                L.i(TAG, "monkeyPatchExistingResources:     [ attempt 1 ] failed: " + ignore);
                try {
                    L.i(TAG, "monkeyPatchExistingResources:     [ attempt 2 ] set the AssetManager of the Resources instance to our brand new one");
                    // N
                    final Object resourceImpl = resourcesImplFiled.get(resources);
                    // for Huawei HwResourcesImpl
                    final Field implAssets = ReflectUtil.findField(resourceImpl, "mAssets");
                    implAssets.set(resourceImpl, newAssetManager);
                    L.i(TAG, "monkeyPatchExistingResources:     [ attempt 2 ] success");
                } catch (Throwable ignore2) {
                    L.i(TAG, "monkeyPatchExistingResources:     [ attempt 2 ] failed: " + ignore2);
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
        L.i(TAG, "clearPreloadTypedArrayIssue: try to clear typedArray cache!");
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
            L.i(TAG, "clearPreloadTypedArrayIssue: clear typedArray cache finish!");
        } catch (Throwable ignored) {
            L.e(TAG, "clearPreloadTypedArrayIssue failed, ignore error: " + ignored);
        }
    }


    public static boolean isResourcesPatchesInstalled() {
        return isResourcesPatchesInstalled;
    }
}
