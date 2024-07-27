package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;
import java.io.File;

public class HotfixLoader {

    public static final String TAG = "jugg-jvmti";

    public static File overlayFilesDir; // dir where Apply Changes save files

    public static void install(final Context base) {
        File codeCacheDir = base.getCodeCacheDir();
        overlayFilesDir = new File(codeCacheDir, ".overlay");
        new DexPatchLoader(base).install();
    }

}
