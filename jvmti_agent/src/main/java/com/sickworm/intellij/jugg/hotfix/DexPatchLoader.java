package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Refer from lightning :)
 * Dex Hotfix implementation
 */
class DexPatchLoader {

    private static final String TAG = HotfixLoader.TAG + "#DexPatchLoader";
    public static final String DEX_FILE_SUFFIX = ".dex";

    private final Context baseContext;
    private final BaseDexClassLoader originClassLoader;
    private final String nativeLibraryPath;

    public DexPatchLoader(Context base) {
        this.baseContext = base;
        this.originClassLoader = (BaseDexClassLoader) base.getClassLoader();
        this.nativeLibraryPath = base.getApplicationInfo().nativeLibraryDir;
    }

    void install() {
        LogUtils.i(TAG, "install: baseContext = " + baseContext);

        final List<File> dstDexFiles = new ArrayList<>();
        if (HotfixLoader.overlayFilesDir.exists()) {
            final File[] dstFiles = HotfixLoader.overlayFilesDir.listFiles();
            if (dstFiles != null) {
                for (File file : dstFiles) {
                    if (file.getName().endsWith(DEX_FILE_SUFFIX)) {
                        dstDexFiles.add(file);
                    }
                }
            }
        }

        ClassLoader classLoader;
        LogUtils.d(TAG, "install: before inject base context's classloader = " + baseContext.getClassLoader());
        try {
            classLoader = AndroidNClassLoader.inject(originClassLoader, baseContext);
            LogUtils.d(TAG, "install: create AndroidNClassLoader finish , classLoader = " + classLoader);
            LogUtils.d(TAG, "install: after inject base context's classloader = " + baseContext.getClassLoader());
        } catch (Throwable ex) {
            LogUtils.d(TAG, "install: error while inject AndroidNClassLoader : " + ex);
            throw new RuntimeException(TAG + " : Error while install incremental dex files：", ex);
        }

        // for Harmony OS, create AndroidNClassLoader is just enough.
        installDexInternal(dstDexFiles, classLoader, nativeLibraryPath);

        LogUtils.d(TAG, "installDexPatches: dex files installed finish.");
    }

    private static void installDexInternal(List<File> dstDexFiles, ClassLoader classLoader, String nativeLibraryPath) {
        LogUtils.i(TAG, "installDexInternal: start inject all incremental dex to classloader..., size = " + dstDexFiles.size());
        try {
            for (File file : dstDexFiles) {
                LogUtils.d(TAG, "installDexInternal: inject for dex = " + file.getName());

                final Class<?> baseDexClassLoaderClass = Class.forName("dalvik.system.BaseDexClassLoader");
                final Object pathList = ReflectUtil.getField(baseDexClassLoaderClass, "pathList", classLoader);
                final Object baseElements = ReflectUtil.getField(pathList.getClass(), "dexElements", pathList);

                DexClassLoader dexClassLoader;
                try {
                    dexClassLoader = new DexClassLoader(file.getAbsolutePath(), null, nativeLibraryPath, classLoader);
                } catch (NullPointerException e) {
                    // compat for 8.0
                    File optimizedDirectory = new File(HotfixLoader.codeCacheDir, "opt/" + file.getName());
                    recreateDirectory(optimizedDirectory);
                    dexClassLoader = new DexClassLoader(file.getAbsolutePath(), optimizedDirectory.getAbsolutePath(), nativeLibraryPath, classLoader);
                }

                Object obj = ReflectUtil.getField(baseDexClassLoaderClass, "pathList", dexClassLoader);
                Object dexElements = ReflectUtil.getField(obj.getClass(), "dexElements", obj);
                Object combineElements = ReflectUtil.combineArray(dexElements, baseElements);
                ReflectUtil.setField(pathList.getClass(), "dexElements", pathList, combineElements);
            }
        } catch (Throwable ex) {
            LogUtils.i(TAG, "installDexInternal: Error while install inc dex : " + ex);
            throw new RuntimeException(TAG + " : Error while install incremental dex files：", ex);
        }
        LogUtils.i(TAG, "installDexInternal: end inject all incremental dex to classloader.");
    }

    private static void recreateDirectory(File dir) {
        if (dir.exists()) {
            deleteDirectory(dir);
        }
        if (!dir.mkdirs()) {
            throw new RuntimeException("create dir failed: " + dir.getAbsolutePath());
        }
    }

    private static void deleteDirectory(File rootFile) {
        if (rootFile.isDirectory()) {
            File[] files = rootFile.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
            if (!rootFile.delete()) {
                throw new RuntimeException("delete dir failed: " + rootFile.getAbsolutePath());
            }
        } else {
            if (!rootFile.delete()) {
                throw new RuntimeException("delete file failed: " + rootFile.getAbsolutePath());
            }
        }
    }
}
