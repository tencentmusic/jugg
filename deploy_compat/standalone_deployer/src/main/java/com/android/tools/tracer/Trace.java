package com.android.tools.tracer;

/** Provides no-op tracing because standalone deployer does not ship Android Studio Perfetto services. */
public final class Trace implements AutoCloseable {
   private static final Trace INSTANCE = new Trace();

   private Trace() {
   }

   public static Trace begin(String name) {
      return INSTANCE;
   }

   public static void begin(long pid, long tid, long timestamp, String name) {
   }

   public static void end() {
   }

   public static void end(long pid, long tid, long timestamp) {
   }

   @Override
   public void close() {
   }
}
