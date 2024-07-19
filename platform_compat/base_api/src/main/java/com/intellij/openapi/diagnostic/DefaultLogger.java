package com.intellij.openapi.diagnostic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.apache.log4j.Level;

public class DefaultLogger extends Logger {
    private static boolean ourMirrorToStderr = true;

    public DefaultLogger(String category) {
    }

    public boolean isDebugEnabled() {
        return false;
    }

    public void debug(String message) {
    }

    public void debug(Throwable t) {
    }

    public void debug(String message, Throwable t) {
    }

    public void info(String message) {
    }

    public void info(String message, Throwable t) {
    }

    public void warn(String message, @Nullable Throwable t) {
        System.err.println("WARN: " + message);
        if (t != null) {
            t.printStackTrace(System.err);
        }

    }

    public void error(String message, @Nullable Throwable t, @NotNull String... details) {
        dumpExceptionsToStderr(message, t, details);
        throw new AssertionError(message, t);
    }

    public static void dumpExceptionsToStderr(String message, @Nullable Throwable t, @NotNull String... details) {
        if (shouldDumpExceptionToStderr()) {
            System.err.println("ERROR: " + message);
            if (t != null) {
                t.printStackTrace(System.err);
            }

            if (details.length > 0) {
                System.err.println("details: ");
                String[] var3 = details;
                int var4 = details.length;

                for(int var5 = 0; var5 < var4; ++var5) {
                    String detail = var3[var5];
                    System.err.println(detail);
                }
            }
        }

    }

    public void setLevel(@NotNull Level level) {

    }

    public static boolean shouldDumpExceptionToStderr() {
        return ourMirrorToStderr;
    }
}
