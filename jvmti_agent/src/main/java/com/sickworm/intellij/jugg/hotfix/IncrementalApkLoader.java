package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;

import java.io.File;

public class IncrementalApkLoader {

    public static final String TAG = "jugg-agent";

    private static final String INCREMENTAL_DATA_ASSETS_PATH = "jugg_/";

    private static Boolean isIncrementalApk = false;

    public static void init(Context base) {
        try {
            String[] files = base.getAssets().list(INCREMENTAL_DATA_ASSETS_PATH);
            if (files == null || files.length == 0) {
                return;
            }

            LogUtils.i(TAG, "initIncrementalApk is incremental apk");
            if (HotfixLoader.overlayFilesDir.exists()) {
                FileUtil.deleteRecursively(HotfixLoader.overlayFilesDir);
            }
            if (!HotfixLoader.overlayFilesDir.mkdirs()) {
                throw new RuntimeException("initIncrementalApk mkdirs failed: " + HotfixLoader.overlayFilesDir);
            }
            for (String fileName : files) {
                File targetFile = new File(HotfixLoader.overlayFilesDir, fileName);
                FileUtil.copyFromAssetsToFile(base, INCREMENTAL_DATA_ASSETS_PATH + fileName, targetFile);
            }
            isIncrementalApk = true;
        } catch (Exception e) {
            LogUtils.e(TAG, "initIncrementalApk got exception, let it crash ", e);
            throw new RuntimeException(e);
        }
    }

    public static boolean isIncrementalApk() {
        return IncrementalApkLoader.isIncrementalApk;
    }
}
