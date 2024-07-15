package com.android.tools.idea.run;

import java.io.File;
import org.jetbrains.annotations.NotNull;

public class ApkFileUnit {
    private final @NotNull String myModuleName;
    private final @NotNull File myApkFile;

    public ApkFileUnit(@NotNull String moduleName, @NotNull File apkFile) {
        this.myModuleName = moduleName;
        this.myApkFile = apkFile;
    }

    public @NotNull String getModuleName() {
        return this.myModuleName;
    }

    public @NotNull File getApkFile() {
        return this.myApkFile;
    }
}
