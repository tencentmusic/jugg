package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;
import android.os.Build;
import com.sickworm.intellij.jugg.instrument.DexPathListFixer;
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig;

import java.io.File;

public class HotfixLoader {

    public static final String TAG = "jugg-agent";

    public static File codeCacheDir; // dir where Apply Changes save files
    public static File overlayFilesDir; // dir where Apply Changes save files
    public static File embeddedClassesDir; // dir where Apply Changes save files
    private static boolean isRuntimeSupported;

    public static void init(final Context base) {
        isRuntimeSupported = isRuntimeSupported(Build.VERSION.SDK_INT);
        if (!isRuntimeSupported) {
            LogUtils.i(TAG, "Jugg runtime is disabled on device API " + Build.VERSION.SDK_INT);
            return;
        }
        codeCacheDir = base.getCodeCacheDir();
        overlayFilesDir = new File(codeCacheDir, ".overlay");
        embeddedClassesDir = new File(codeCacheDir, ".jugg_classes_embed");
        IncrementalApkLoader.init(base);
    }

    static boolean isRuntimeSupported(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.O;
    }

    public static boolean isNeedEnableHotfix() {
        if (!isRuntimeSupported || overlayFilesDir == null) {
            return false;
        }
        if (IncrementalApkLoader.isIncrementalApk()) {
            return true;
        }

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

    public synchronized static void install(final Context base) {
        if (!isRuntimeSupported) {
            LogUtils.i(TAG, "installDexPatches skip for unsupported Jugg runtime.");
            return;
        }
        LogUtils.i(TAG, "installDexPatches start...");
        if (DexPathListFixer.isNoNeedFixFlagExists(base) || DexPathListFixer.isNeedFixFlagExists(base)) {
            if (IncrementalApkLoader.isIncrementalApk()) {
                LogUtils.i(TAG, "installDexPatches already load by JVMTI agent, but is a incremental apk, try precise embedded install.");
                try {
                    new DexPatchLoader(base).installEmbeddedClassesOnly();
                } catch (Throwable e) {
                    // Fallback: overlay dex will be duplicated but remains functionally stable
                    LogUtils.w(TAG, "installDexPatches precise install failed, fallback to full install: " + e);
                    new DexPatchLoader(base).install();
                }
            } else {
                LogUtils.i(TAG, "installDexPatches already load by JVMTI agent, skip.");
            }
        } else {
            new DexPatchLoader(base).install();
        }
        LogUtils.i(TAG, "installDexPatches finish.");
        LogUtils.i(TAG, "installResources start...");
        new ResourcesPatchLoader(base).install();
        LogUtils.i(TAG, "installResources finish.");
    }

    public synchronized static void installDex(final Context base) {
        if (!isRuntimeSupported) {
            LogUtils.i(TAG, "installDex skip for unsupported Jugg runtime.");
            return;
        }
        new DexPatchLoader(base).install();
    }
}
