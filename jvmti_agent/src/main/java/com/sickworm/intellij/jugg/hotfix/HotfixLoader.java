package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;
import java.io.File;

public class HotfixLoader {

    public static final String TAG = "jugg-agent";

    public static File codeCacheDir; // dir where Apply Changes save files
    public static File overlayFilesDir; // dir where Apply Changes save files

    public static void init(final Context base) {
        codeCacheDir = base.getCodeCacheDir();
        overlayFilesDir = new File(codeCacheDir, ".overlay");
    }

    public static boolean isNeedEnableHotfix() {
        File flagFile = new File(overlayFilesDir, ".jugg_compat_deploy_enable");
        File flagFile2 = new File(codeCacheDir, ".jugg_jvmti_not_available");
        LogUtils.i(TAG, "isNeedEnableHotfix " + flagFile.getName() + " exists " + flagFile.exists());
        LogUtils.i(TAG, "isNeedEnableHotfix " + flagFile2.getName() + " exists " + flagFile2.exists());
        return flagFile.exists() || flagFile2.exists();
    }

    public static void install(final Context base) {
        LogUtils.i(TAG, "installDexPatches start...");
        new DexPatchLoader(base).install();
        LogUtils.i(TAG, "installDexPatches finish.");
        LogUtils.i(TAG, "installResources start...");
        new ResourcesPatchLoader(base).install();
        LogUtils.i(TAG, "installResources finish.");
    }

    public static void installDex(final Context base) {
        new DexPatchLoader(base).install();
    }
}
