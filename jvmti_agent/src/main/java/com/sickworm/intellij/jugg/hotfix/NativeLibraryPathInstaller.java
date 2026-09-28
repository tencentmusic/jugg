package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import dalvik.system.BaseDexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Prepends committed APK-scoped native overlays to the app ClassLoader search path. */
public final class NativeLibraryPathInstaller {
    private static final String TAG = HotfixLoader.TAG + "#NativeLibraryPathInstaller";
    private static final String OVERLAY_DIR_NAME = ".overlay";
    private static final String LEGACY_DIR_NAME = ".jugg_native";
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
            LogUtils.w(TAG, "native library path install skipped: API " + Build.VERSION.SDK_INT + " < 26");
            return;
        }
        File codeCache = HotfixLoader.codeCacheDir != null ? HotfixLoader.codeCacheDir : base.getCodeCacheDir();
        if (codeCache == null) {
            return;
        }
        List<File> patchDirs = findPatchDirs(base, codeCache);
        if (patchDirs.isEmpty()) {
            return;
        }
        ClassLoader classLoader = base.getClassLoader();
        if (!(classLoader instanceof BaseDexClassLoader)) {
            markFailed(codeCache, "classloader is not BaseDexClassLoader");
            return;
        }
        try {
            installPaths((BaseDexClassLoader) classLoader, patchDirs, codeCache);
            deleteQuietly(new File(codeCache, FAILED_FLAG));
            LogUtils.i(TAG, "native library paths installed: " + patchDirs);
        } catch (Throwable throwable) {
            markFailed(codeCache, String.valueOf(throwable));
            LogUtils.w(TAG, "native library path install failed: " + throwable);
        }
    }

    private static List<File> findPatchDirs(Context base, File codeCache) {
        List<File> result = new ArrayList<>();
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        if (abis == null || abis.length == 0) {
            return result;
        }
        File overlayRoot = new File(codeCache, OVERLAY_DIR_NAME);
        if (new File(overlayRoot, "id").isFile()) {
            for (String apkName : orderedApkNames(base.getApplicationInfo(), overlayRoot)) {
                for (String abi : abis) {
                    if (isSafeAbi(abi)) {
                        File dir = new File(new File(new File(overlayRoot, apkName), "lib"), abi);
                        if (hasNativeLibs(dir)) result.add(dir);
                    }
                }
            }
        }
        // Old deployments remain readable until the next clean/reinstall.
        File legacyRoot = new File(codeCache, LEGACY_DIR_NAME);
        if (new File(legacyRoot, ENABLED_FLAG).isFile()) {
            for (String abi : abis) {
                if (isSafeAbi(abi)) {
                    File dir = new File(legacyRoot, abi);
                    if (hasNativeLibs(dir)) result.add(dir);
                }
            }
        }
        return result;
    }

    private static List<String> orderedApkNames(ApplicationInfo appInfo, File overlayRoot) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (appInfo != null) {
            addApkName(names, appInfo.sourceDir);
            if (appInfo.splitSourceDirs != null) {
                for (String path : appInfo.splitSourceDirs) addApkName(names, path);
            }
        }
        File[] children = overlayRoot.listFiles();
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) {
                if (child.isDirectory() && isSafeApkName(child.getName())) names.add(child.getName());
            }
        }
        return new ArrayList<>(names);
    }

    private static void addApkName(LinkedHashSet<String> names, String path) {
        if (path == null) return;
        String name = new File(path).getName();
        if (isSafeApkName(name)) names.add(name);
    }

    private static boolean isSafeApkName(String name) {
        return name.matches("[A-Za-z0-9_.-]+\\.apk") && !name.contains("..");
    }

    private static boolean isSafeAbi(String abi) {
        return "arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi) || "armeabi".equals(abi)
                || "x86_64".equals(abi) || "x86".equals(abi);
    }

    private static boolean hasNativeLibs(File dir) {
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files == null) return false;
        for (File file : files) {
            if (file.isFile() && file.getName().matches("lib[^/]+\\.so")) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void installPaths(BaseDexClassLoader classLoader, List<File> patches, File codeCache) throws Exception {
        Object pathList = ReflectUtil.findField(classLoader, "pathList").get(classLoader);
        Field dirsField = ReflectUtil.findField(pathList, "nativeLibraryDirectories");
        List<File> original = (List<File>) dirsField.get(pathList);
        List<File> appDirs = new ArrayList<>();
        if (original != null) appDirs.addAll(original);
        String patchRoot = canonical(codeCache) + File.separator;
        appDirs.removeIf(file -> file != null && canonical(file).startsWith(patchRoot)
                && (canonical(file).contains(File.separator + OVERLAY_DIR_NAME + File.separator)
                || canonical(file).contains(File.separator + LEGACY_DIR_NAME + File.separator)));
        appDirs.addAll(0, patches);
        dirsField.set(pathList, appDirs);

        List<File> allDirs = new ArrayList<>(appDirs);
        Field systemDirsField = ReflectUtil.findField(pathList, "systemNativeLibraryDirectories");
        List<File> systemDirs = (List<File>) systemDirsField.get(pathList);
        if (systemDirs != null) allDirs.addAll(systemDirs);
        Method makePathElements = ReflectUtil.findMethod(pathList, "makePathElements", List.class);
        Object[] elements = (Object[]) makePathElements.invoke(pathList, allDirs);
        ReflectUtil.findField(pathList, "nativeLibraryPathElements").set(pathList, elements);
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    private static void markFailed(File codeCache, String reason) {
        File flag = new File(codeCache, FAILED_FLAG);
        try (FileOutputStream output = new FileOutputStream(flag)) {
            output.write(reason.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // The host can inspect this best-effort flag after startup.
        }
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) LogUtils.w(TAG, "unable to delete " + file.getAbsolutePath());
    }
}
