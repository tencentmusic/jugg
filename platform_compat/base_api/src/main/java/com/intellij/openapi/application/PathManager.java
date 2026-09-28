package com.intellij.openapi.application;

import org.jetbrains.annotations.NotNull;

import java.io.File;

public class PathManager {

    /** Supplied by the IDE runtime; the base API only provides a compile-time signature. */
    @NotNull
    public static String getHomePath() {
        throw new UnsupportedOperationException("IDE runtime only");
    }

    @NotNull
    public static String getSystemPath() {
        String userHome = System.getProperty("user.home");
        return new File(userHome + File.separator + ".jugg").getAbsolutePath();
    }
}
