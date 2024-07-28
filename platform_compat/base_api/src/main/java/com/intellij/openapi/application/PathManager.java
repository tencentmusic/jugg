package com.intellij.openapi.application;

import org.jetbrains.annotations.NotNull;

import java.io.File;

public class PathManager {

    @NotNull
    public static String getSystemPath() {
        String userHome = System.getProperty("user.home");
        return new File(userHome + File.separator + ".jugg").getAbsolutePath();
    }
}
