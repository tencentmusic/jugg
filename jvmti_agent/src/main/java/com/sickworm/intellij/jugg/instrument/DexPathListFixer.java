package com.sickworm.intellij.jugg.instrument;

import android.content.Context;
import com.sickworm.intellij.jugg.hotfix.ReflectUtil;
import com.sickworm.intellij.jugg.hotfix.LogUtils;
import dalvik.system.DexFile;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fix dex classes not loaded in HarmonyOS 4.2
 */
public class DexPathListFixer {

    private static final String TAG = InstrumentationHooks.TAG;

    public static boolean isNeedFix(Context base) throws NoSuchFieldException, IllegalAccessException, IOException {
        File noNeedFixFlagFile = new File(base.getCodeCacheDir(), ".no_need_fix_dex_path_list");
        if (noNeedFixFlagFile.exists()) {
            LogUtils.d(TAG, "DexPathListFixer already checked, no need fix");
            return false;
        }

        File needFixFlagFile = new File(base.getCodeCacheDir(), ".need_fix_dex_path_list");
        if (needFixFlagFile.exists()) {
            LogUtils.d(TAG, "DexPathListFixer already checked, need fix");
            return true;
        }

        List<File> dexFiles = getApplyChangesDexFiles(base);
        if (dexFiles.isEmpty()) {
            LogUtils.d(TAG, "No DEX files in dir, no need fix dex path list");
            return false;
        }

        final ClassLoader originalClassLoader = base.getClassLoader();
        final Field pathListField = ReflectUtil.findField(originalClassLoader, "pathList");
        final Object pathList = pathListField.get(originalClassLoader);
        assert pathList != null;
        final Object[] dexElements = (Object[]) ReflectUtil.getField(pathList.getClass(), "dexElements", pathList);
        LogUtils.d(TAG, "dexFiles size: " + dexFiles.size() + ", dexElements size: " + dexElements.length);

        Set<String> dexFileNames = new HashSet<>();
        for (File dexFile : dexFiles) {
            dexFileNames.add(dexFile.getName());
        }

        final Field dexFileField = ReflectUtil.findField(dexElements.getClass().getComponentType(), "dexFile");
        boolean hasProperlyInjectDexFiles = false;
        for (Object dexElement : dexElements) {
            final DexFile dexFile = (DexFile) dexFileField.get(dexElement);
            if (dexFile == null || dexFile.getName() == null) {
                continue;
            }
            String fileName = new File(dexFile.toString()).getName();
            if (dexFileNames.contains(fileName)) {
                hasProperlyInjectDexFiles = true;
                //noinspection ResultOfMethodCallIgnored
                noNeedFixFlagFile.createNewFile();
                break;
            }
        }

        if (!hasProperlyInjectDexFiles) {
            //noinspection ResultOfMethodCallIgnored
            needFixFlagFile.createNewFile();
        }

        return !hasProperlyInjectDexFiles;
    }

    private static List<File> getApplyChangesDexFiles(Context base) {
        File overlayDir = new File(base.getCodeCacheDir(), ".overlay");
        List<File> dexFiles = new ArrayList<>();
        if (overlayDir.exists()) {
            File[] files = overlayDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String fileName = file.getName();
                    if (fileName.endsWith(".dex")) {
                        dexFiles.add(file);
                    }
                }
            }
        }
        return dexFiles;
    }

}
