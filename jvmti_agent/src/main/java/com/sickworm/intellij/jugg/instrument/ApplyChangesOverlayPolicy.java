package com.sickworm.intellij.jugg.instrument;

import com.sickworm.intellij.jugg.hotfix.LogUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;

/**
 * Decides whether host Apply Changes overlays should be removed from a newly created AssetManager.
 */
public final class ApplyChangesOverlayPolicy {

    private static final String DATA_APP_PREFIX = "/data/app";
    private static final String[] HOST_APK_FIELDS = new String[]{
            "sourceDir",
            "splitSourceDirs",
            "publicSourceDir"
    };
    private static final String RESOURCES_KEY_RES_DIR_FIELD = "mResDir";

    private static final LinkedHashSet<String> hostApkPaths = new LinkedHashSet<>();

    private ApplyChangesOverlayPolicy() {
    }

    public static synchronized void recordHostApplicationInfo(Object applicationInfo) {
        LinkedHashSet<String> paths = collectApplicationApkPaths(applicationInfo);
        if (paths.isEmpty()) {
            return;
        }
        hostApkPaths.clear();
        hostApkPaths.addAll(paths);
        LogUtils.i(InstrumentationHooks.TAG, "ApplyChangesOverlayPolicy hostApkPaths=" + hostApkPaths);
    }

    public static synchronized boolean shouldRemoveApplyChangesOverlay(Object resourcesKey) {
        String resDir = asString(readField(resourcesKey, RESOURCES_KEY_RES_DIR_FIELD));
        if (resDir == null) {
            return false;
        }
        if (hostApkPaths.isEmpty()) {
            return !resDir.startsWith(DATA_APP_PREFIX);
        }
        return !hostApkPaths.contains(resDir);
    }

    static synchronized void clearHostApplicationInfo() {
        hostApkPaths.clear();
    }

    private static LinkedHashSet<String> collectApplicationApkPaths(Object applicationInfo) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        appendValues(paths, invokeNoArg(applicationInfo, "getAllApkPaths"));
        if (!paths.isEmpty()) {
            return paths;
        }
        for (String fieldName : HOST_APK_FIELDS) {
            appendValues(paths, readField(applicationInfo, fieldName));
        }
        return paths;
    }

    private static void appendValues(LinkedHashSet<String> output, Object value) {
        if (value == null) {
            return;
        }
        if (!value.getClass().isArray()) {
            addPath(output, value);
            return;
        }
        int length = Array.getLength(value);
        for (int index = 0; index < length; index++) {
            addPath(output, Array.get(value, index));
        }
    }

    private static void addPath(LinkedHashSet<String> output, Object value) {
        String path = asString(value);
        if (path != null) {
            output.add(path);
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = findNoArgMethod(target.getClass(), methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Method findNoArgMethod(Class<?> startClass, String methodName) throws NoSuchMethodException {
        Class<?> currentClass = startClass;
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredMethod(methodName);
            } catch (NoSuchMethodException e) {
                currentClass = currentClass.getSuperclass();
            }
        }
        return startClass.getMethod(methodName);
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        Class<?> currentClass = target.getClass();
        while (currentClass != null) {
            try {
                Field field = currentClass.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                currentClass = currentClass.getSuperclass();
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }
}
