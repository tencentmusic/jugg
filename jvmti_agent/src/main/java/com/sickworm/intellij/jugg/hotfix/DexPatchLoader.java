package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
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

        // embeddedClassesDir must be added first: installDexInternal prepends each dex one by one,
        // so the last-added file ends up at the front. To ensure final order is
        // [overlay..., embedded..., original], we must collect embedded before overlay.
        final List<File> dstDexFiles = new ArrayList<>();
        if (HotfixLoader.embeddedClassesDir.exists()) {
            final File[] dstFiles = HotfixLoader.embeddedClassesDir.listFiles();
            if (dstFiles != null) {
                for (File file : dstFiles) {
                    if (file.getName().endsWith(DEX_FILE_SUFFIX)) {
                        dstDexFiles.add(file);
                    }
                }
            }
        }
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

    /**
     * Installs only the embedded classes dex files (embeddedClassesDir), inserting them
     * immediately after the last overlay dex element in the existing pathList.dexElements.
     *
     * This is used when JVMTI agent has already loaded overlay dex files, and we only need
     * to supplement the embedded dex files without duplicating overlay entries.
     *
     * Insertion position is determined by scanning current dexElements for entries whose
     * dexFile path belongs to overlayFilesDir, then inserting embedded elements right after
     * the last such entry. If no overlay element is found, embedded elements are prepended.
     *
     * Throws on any reflection failure so callers can fall back to install().
     */
    void installEmbeddedClassesOnly() throws Throwable {
        LogUtils.i(TAG, "installEmbeddedClassesOnly: start");

        if (!HotfixLoader.embeddedClassesDir.exists()) {
            LogUtils.i(TAG, "installEmbeddedClassesOnly: embeddedClassesDir not exists, skip");
            return;
        }
        final List<File> embeddedDexFiles = new ArrayList<>();
        final File[] dstFiles = HotfixLoader.embeddedClassesDir.listFiles();
        if (dstFiles != null) {
            for (File file : dstFiles) {
                if (file.getName().endsWith(DEX_FILE_SUFFIX)) {
                    embeddedDexFiles.add(file);
                }
            }
        }
        if (embeddedDexFiles.isEmpty()) {
            LogUtils.i(TAG, "installEmbeddedClassesOnly: no embedded dex files, skip");
            return;
        }

        final ClassLoader classLoader = AndroidNClassLoader.inject(originClassLoader, baseContext);
        LogUtils.i(TAG, "installEmbeddedClassesOnly: classLoader=" + classLoader.getClass().getSimpleName()
                + ", originClassLoader=" + originClassLoader.getClass().getSimpleName());
        final Class<?> baseDexClassLoaderClass = Class.forName("dalvik.system.BaseDexClassLoader");
        final Object pathList = ReflectUtil.getField(baseDexClassLoaderClass, "pathList", classLoader);
        final Object[] currentElements = (Object[]) ReflectUtil.getField(pathList.getClass(), "dexElements", pathList);

        // Find the index after the last overlay dex element.
        // If no overlay element found, insertIndex=0 means prepend all embedded elements.
        // Use canonical path to resolve symlinks (/data/data vs /data/user/0 are symlinked
        // on Android, but getAbsolutePath() returns different strings).
        final String overlayCanonicalPath = HotfixLoader.overlayFilesDir.getCanonicalPath();
        LogUtils.i(TAG, "installEmbeddedClassesOnly: overlayCanonicalPath=" + overlayCanonicalPath
                + ", currentElements.length=" + currentElements.length);
        final Field dexFileField = ReflectUtil.findField(currentElements.getClass().getComponentType(), "dexFile");
        int lastOverlayIndex = -1;
        for (int i = 0; i < currentElements.length; i++) {
            Object dexFile = dexFileField.get(currentElements[i]);
            if (dexFile == null) continue;
            String name = (String) dexFile.getClass().getMethod("getName").invoke(dexFile);
            if (name != null && new File(name).getCanonicalPath().startsWith(overlayCanonicalPath)) {
                lastOverlayIndex = i;
            }
        }
        final int insertIndex = lastOverlayIndex + 1;
        LogUtils.i(TAG, "installEmbeddedClassesOnly: lastOverlayIndex=" + lastOverlayIndex
                + ", insertIndex=" + insertIndex + ", embeddedCount=" + embeddedDexFiles.size());

        // Build all new elements from embedded dex files
        final List<Object> newElementsList = new ArrayList<>();
        for (File file : embeddedDexFiles) {
            LogUtils.d(TAG, "installEmbeddedClassesOnly: build element for " + file.getName());
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
            Object[] elements = (Object[]) ReflectUtil.getField(obj.getClass(), "dexElements", obj);
            for (int i = 0; i < Array.getLength(elements); i++) {
                newElementsList.add(Array.get(elements, i));
            }
        }

        // Insert newElements into currentElements at insertIndex:
        // result = currentElements[0..insertIndex) + newElements + currentElements[insertIndex..]
        final int newCount = newElementsList.size();
        final Object combined = Array.newInstance(
                currentElements.getClass().getComponentType(),
                currentElements.length + newCount);
        System.arraycopy(currentElements, 0, combined, 0, insertIndex);
        for (int i = 0; i < newCount; i++) {
            Array.set(combined, insertIndex + i, newElementsList.get(i));
        }
        System.arraycopy(currentElements, insertIndex, combined, insertIndex + newCount,
                currentElements.length - insertIndex);

        ReflectUtil.setField(pathList.getClass(), "dexElements", pathList, combined);
        final int total = currentElements.length + newCount;
        // Log first and last element paths for quick order verification.
        String firstName = "?", lastName = "?";
        try {
            Object firstDex = dexFileField.get(Array.get(combined, 0));
            Object lastDex = dexFileField.get(Array.get(combined, total - 1));
            if (firstDex != null) firstName = (String) firstDex.getClass().getMethod("getName").invoke(firstDex);
            if (lastDex != null) lastName = (String) lastDex.getClass().getMethod("getName").invoke(lastDex);
        } catch (Throwable ignored) {}
        LogUtils.i(TAG, "installEmbeddedClassesOnly: finish, total=" + total
                + ", first=" + firstName + ", last=" + lastName);
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
