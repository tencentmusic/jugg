package com.android.tools.deployer.common;

import com.android.tools.deployer.model.FileDiff;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public enum ChangeType {
   UNKNOWN,
   DEX,
   RESOURCE,
   NATIVE_LIBRARY,
   MANIFEST;

   public static ChangeType getType(FileDiff diff) {
      String name = diff.getName();
      if (name.endsWith(".dex")) {
         return DEX;
      } else if (!name.startsWith("res/") && !name.startsWith("assets/") && !name.equals("resources.arsc")) {
         if (name.startsWith("lib/") && name.endsWith(".so")) {
            return NATIVE_LIBRARY;
         } else {
            return name.equals("AndroidManifest.xml") ? MANIFEST : UNKNOWN;
         }
      } else {
         return RESOURCE;
      }
   }
}
