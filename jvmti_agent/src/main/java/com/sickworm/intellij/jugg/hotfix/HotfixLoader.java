package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;
import com.sickworm.intellij.jugg.instrument.DexPathListFixer;
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class HotfixLoader {

    public static final String TAG = "jugg-agent";

    public static File codeCacheDir; // dir where Apply Changes save files
    public static File overlayFilesDir; // dir where Apply Changes save files

    public static void init(final Context base) {
        codeCacheDir = base.getCodeCacheDir();
        overlayFilesDir = new File(codeCacheDir, ".overlay");
    }

    public static boolean isNeedEnableHotfix() {
        boolean isEnableHotfix = false;
        if (HotfixLoader.overlayFilesDir.exists()) {
            File[] files = HotfixLoader.overlayFilesDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        File flagFile = new File(file, BuildConfig.ENABLE_COMPAT_DEPLOY_FLAG_FILE);
                        if (flagFile.exists()) {
                            isEnableHotfix = true;
                        }
                    }
                }
            }
        }
        return isEnableHotfix;
    }

    public static void install(final Context base) {
        LogUtils.i(TAG, "installDexPatches start...");
        if (DexPathListFixer.isNoNeedFixFlagExists(base) || DexPathListFixer.isNeedFixFlagExists(base)) {
            LogUtils.i(TAG, "installDexPatches already load by JVMTI agent, skip.");
        } else {
            new DexPatchLoader(base).install();
        }
        LogUtils.i(TAG, "installDexPatches finish.");
        LogUtils.i(TAG, "installResources start...");
        new ResourcesPatchLoader(base).install();
        LogUtils.i(TAG, "installResources finish.");
    }

    public static void installDex(final Context base) {
        new DexPatchLoader(base).install();
    }
}
