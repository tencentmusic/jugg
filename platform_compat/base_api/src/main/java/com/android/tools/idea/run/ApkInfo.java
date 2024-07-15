//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.android.tools.idea.run;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

public final class ApkInfo {
    private final @NotNull List<ApkFileUnit> myFiles;
    private final @NotNull String myApplicationId;
    private final @NotNull Set<AppInstallOption> myRequiredInstallOptions;

    public ApkInfo(@NotNull File file, @NotNull String applicationId) {
        this(file, applicationId, new HashSet<>());
    }

    public ApkInfo(@NotNull File file, @NotNull String applicationId, @NotNull Set<AppInstallOption> requiredInstallOptions) {
        super();
        this.myFiles = new ArrayList<>();
        myFiles.add(new ApkFileUnit("", file));
        this.myApplicationId = applicationId;
        this.myRequiredInstallOptions = requiredInstallOptions;
    }

    public ApkInfo(@NotNull List<ApkFileUnit> files, @NotNull String applicationId) {
        super();
        this.myFiles = files;
        this.myApplicationId = applicationId;
        this.myRequiredInstallOptions = new HashSet<>();
    }

    public @NotNull File getFile() {
        return this.myFiles.get(0).getApkFile();
    }

    public @NotNull List<ApkFileUnit> getFiles() {
        return this.myFiles;
    }

    public @NotNull String getApplicationId() {
        return this.myApplicationId;
    }

    public @NotNull Set<AppInstallOption> getRequiredInstallOptions() {
        return this.myRequiredInstallOptions;
    }

    public enum AppInstallOption {
        GRANT_ALL_PERMISSIONS(23),
        FORCE_QUERYABLE(30);

        public final int minSupportedApiLevel;

        AppInstallOption(int minSupportedApiLevel) {
            this.minSupportedApiLevel = minSupportedApiLevel;
        }
    }
}
