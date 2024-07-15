package com.intellij.openapi.diagnostic;

import java.util.Collection;
import java.util.Iterator;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.apache.log4j.Level;

@SuppressWarnings("CallToPrintStackTrace")
public abstract class Logger {

    public Logger() {
    }

    public static void setFactory(@NotNull Class<? extends Factory> factory) {
    }

    public static void setFactory(@NotNull Factory factory) {
    }

    public static Factory getFactory() {
        return null;
    }

    public static boolean isInitialized() {
        return false;
    }

    public static @NotNull Logger getInstance(@NotNull String category) {

        return new Logger() {
            @Override
            public boolean isDebugEnabled() {
                return true;
            }

            @Override
            public void debug(String var1) {
                System.out.println(var1);
            }

            @Override
            public void debug(@Nullable Throwable var1) {
                if (var1 != null) {
                    var1.printStackTrace();
                }
            }

            @Override
            public void debug(String var1, @Nullable Throwable var2) {
                System.out.println(var1);
            }

            @Override
            public void info(String var1) {
                System.out.println(var1);
            }

            @Override
            public void info(String var1, @Nullable Throwable var2) {
                System.out.println(var1);
                if (var2 != null) {
                    var2.printStackTrace();
                }
            }

            @Override
            public void warn(String var1, @Nullable Throwable var2) {
                System.out.println(var1);
                if (var2 != null) {
                    var2.printStackTrace();
                }
            }

            @Override
            public void error(String var1, @Nullable Throwable var2, @NotNull String... var3) {
                System.out.println(var1);
                if (var2 != null) {
                    var2.printStackTrace();
                }
            }

            @Override
            public void setLevel(@NotNull Level var1) {

            }
        };
    }

    public static @NotNull Logger getInstance(@NotNull Class<?> cl) {
        return getInstance(cl.getName());
    }

    public abstract boolean isDebugEnabled();

    public abstract void debug(String var1);

    public abstract void debug(@Nullable Throwable var1);

    public abstract void debug(String var1, @Nullable Throwable var2);

    public void debug(@NotNull String message, @NotNull Object... details) {
        if (this.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append(message);
            Object[] var4 = details;
            int var5 = details.length;

            for(int var6 = 0; var6 < var5; ++var6) {
                Object detail = var4[var6];
                sb.append(detail);
            }

            this.debug(sb.toString());
        }

    }

    public void debugValues(@NotNull String header, @NotNull Collection<?> values) {
        if (this.isDebugEnabled()) {
            StringBuilder text = new StringBuilder();
            text.append(header).append(" (").append(values.size()).append(")");
            if (!values.isEmpty()) {
                text.append(":");
                Iterator var4 = values.iterator();

                while(var4.hasNext()) {
                    Object value = var4.next();
                    text.append("\n");
                    text.append(value);
                }
            }

            this.debug(text.toString());
        }

    }

    public final void infoWithDebug(@NotNull Throwable t) {
        this.infoWithDebug(t.toString(), t);
    }

    public final void infoWithDebug(@NotNull String message, @NotNull Throwable t) {
        this.info(message);
        this.debug(t);
    }

    public final void warnWithDebug(@NotNull Throwable t) {
        this.warnWithDebug(t.toString(), t);
    }

    public final void warnWithDebug(@NotNull String message, @NotNull Throwable t) {
        this.warn(message);
        this.debug(t);
    }

    public boolean isTraceEnabled() {
        return this.isDebugEnabled();
    }

    public void trace(String message) {
        this.debug(message);
    }

    public void trace(@Nullable Throwable t) {
        this.debug(t);
    }

    public void info(@NotNull Throwable t) {
        this.info(t.getMessage(), t);
    }

    public abstract void info(String var1);

    public abstract void info(String var1, @Nullable Throwable var2);

    public void warn(String message) {
        this.warn(message, (Throwable)null);
    }

    public void warn(@NotNull Throwable t) {
        this.warn(t.getMessage(), t);
    }

    public abstract void warn(String var1, @Nullable Throwable var2);

    public void error(String message) {
        this.error(message, new Throwable(message), new String[0]);
    }

    public void error(Object message) {
        this.error(String.valueOf(message));
    }

    public void error(String message, @NotNull String... details) {
        this.error(message, new Throwable(message), details);
    }

    public void error(String message, @Nullable Throwable t) {
        this.error(message, t, new String[0]);
    }

    public void error(@NotNull Throwable t) {
        this.error(t.getMessage(), t, new String[0]);
    }

    public abstract void error(String var1, @Nullable Throwable var2, @NotNull String... var3);

    @Contract("false,_->fail")
    public boolean assertTrue(boolean value, @Nullable Object message) {
        if (!value) {
            String resultMessage = "Assertion failed";
            if (message != null) {
                resultMessage = resultMessage + ": " + message;
            }

            this.error(resultMessage, new Throwable(resultMessage));
        }

        return value;
    }

    @Contract("false->fail")
    public boolean assertTrue(boolean value) {
        return value || this.assertTrue(false, (Object)null);
    }

    /** @deprecated */
    @Deprecated
    public abstract void setLevel(@NotNull Level var1);


    public interface Factory {
        @NotNull Logger getLoggerInstance(@NotNull String var1);
    }

    private static final class DefaultFactory implements Factory {
        private DefaultFactory() {
        }

        public @NotNull Logger getLoggerInstance(@NotNull String category) {
            return Logger.getInstance(category);
        }
    }
}
