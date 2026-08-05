package com.android.tools.deployer.model;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class ModelException extends Exception {
   public ModelException(String message) {
      super(message);
   }

   public ModelException(String message, Throwable cause) {
      super(message, cause);
   }
}
