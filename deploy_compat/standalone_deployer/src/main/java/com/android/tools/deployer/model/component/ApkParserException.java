package com.android.tools.deployer.model.component;

/** Reports malformed or unreadable APK metadata during standalone parsing. */
public class ApkParserException extends RuntimeException {
   public ApkParserException(String message) {
      super(message);
   }

   public ApkParserException(Exception parent) {
      super(parent);
   }
}
