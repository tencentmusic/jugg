package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;
import android.os.Build;
import dalvik.system.BaseDexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Prepends {@code code_cache/.jugg_native/<abi>} to the app ClassLoader native search path.
 * Aligns with Freeline/Tinker DexPathList injection for API 26+.
 */
public final class NativeLibraryPathInstaller {

    private static final String TAG = HotfixLoader.TAG + "#NativeLibraryPathInstaller";
    private static final String NATIVE_DIR_NAME = ".jugg_native";
    private static final String ENABLED_FLAG = ".enabled";
    private static final String FAILED_FLAG = ".jugg_native_inject_failed";

    private NativeLibraryPathInstaller() {
    }

    public static void install(Context base) {
        if (base == null) {
            LogUtils.w(TAG, "native library path install skipped: context is null");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            LogUtils.w(TAG, "native library path install skipped: API " +
                    Build.VERSION.SDK_INT + " < 26");
            return;
        }
        File nativeRoot = nativeRoot(base);
        if (nativeRoot == null) {
            return;
        }
        if (!isEnabled(nativeRoot)) {
            LogUtils.i(TAG, "native library path install skipped: SO hot update disabled");
            return;
        }
        File abiDir = resolveAbiDir(nativeRoot);
        if (abiDir == null) {
            return;
        }
        ClassLoader classLoader = base.getClassLoader();
        if (!(classLoader instanceof BaseDexClassLoader)) {
            markFailed(nativeRoot.getParentFile(), "classloader is not BaseDexClassLoader");
            return;
        }
        try {
            installPath((BaseDexClassLoader) classLoader, abiDir);
            deleteQuietly(new File(nativeRoot.getParentFile(), FAILED_FLAG));
            LogUtils.i(TAG, "native library path installed: " + abiDir.getAbsolutePath());
        } catch (Throwable throwable) {
            markFailed(nativeRoot.getParentFile(), String.valueOf(throwable));
            LogUtils.w(TAG, "native library path install failed: " + throwable);
        }
    }

    private static File nativeRoot(Context base) {
        File codeCache = HotfixLoader.codeCacheDir;
        if (codeCache == null) {
            codeCache = base.getCodeCacheDir();
        }
        if (codeCache == null) {
            return null;
        }
        File nativeRoot = new File(codeCache, NATIVE_DIR_NAME);
        if (!nativeRoot.isDirectory()) {
            return null;
        }
        return nativeRoot;
    }

    private static boolean isEnabled(File nativeRoot) {
        return new File(nativeRoot, ENABLED_FLAG).isFile();
    }

    private static File resolveAbiDir(File nativeRoot) {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null) {
            return null;
        }
        for (String abi : abis) {
            if (abi == null || abi.isEmpty()) {
                continue;
            }
            File dir = new File(nativeRoot, abi);
            if (hasNativeLibs(dir)) {
                return dir;
            }
        }
        return null;
    }

    private static boolean hasNativeLibs(File dir) {
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("lib") && file.getName().endsWith(".so")) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void installPath(BaseDexClassLoader classLoader, File folder) throws Exception {
        Object dexPathList = ReflectUtil.findField(classLoader, "pathList").get(classLoader);
        Field nativeLibraryDirectoriesField = ReflectUtil.findField(dexPathList, "nativeLibraryDirectories");
        List<File> originalDirs = (List<File>) nativeLibraryDirectoriesField.get(dexPathList);
        List<File> appDirs = new ArrayList<>();
        if (originalDirs != null) {
            appDirs.addAll(originalDirs);
        }
        if (!appDirs.isEmpty() && sameDir(appDirs.get(0), folder)) {
            return;
        }
        removeDir(appDirs, folder);
        appDirs.add(0, folder);
        nativeLibraryDirectoriesField.set(dexPathList, appDirs);

        List<File> allDirs = new ArrayList<>(appDirs);
        Field systemDirsField = ReflectUtil.findField(dexPathList, "systemNativeLibraryDirectories");
        List<File> systemDirs = (List<File>) systemDirsField.get(dexPathList);
        if (systemDirs != null) {
            allDirs.addAll(systemDirs);
        }
        Method makePathElements = ReflectUtil.findMethod(dexPathList, "makePathElements", List.class);
        Object[] elements = (Object[]) makePathElements.invoke(dexPathList, allDirs);
        Field pathElementsField = ReflectUtil.findField(dexPathList, "nativeLibraryPathElements");
        pathElementsField.set(dexPathList, elements);
    }

    private static void removeDir(List<File> dirs, File folder) {
        String target = canonical(folder);
        dirs.removeIf(file -> file != null && target.equals(canonical(file)));
    }

    private static boolean sameDir(File left, File right) {
        return canonical(left).equals(canonical(right));
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    private static void markFailed(File codeCache, String reason) {
        if (codeCache == null) {
            return;
        }
        File flag = new File(codeCache, FAILED_FLAG);
        try (FileOutputStream output = new FileOutputStream(flag)) {
            output.write(reason.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Best-effort flag for the next native deploy round.
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            LogUtils.w(TAG, "unable to delete " + file.getAbsolutePath());
        }
    }
}
