package com.android.tools.deployer.common;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public abstract class Canceller {
   public static final String REASON = "User cancelled deployment.";
   public static final Canceller NO_OP = new Canceller() {
      public boolean cancelled() {
         return false;
      }
   };

   public abstract boolean cancelled();
}
