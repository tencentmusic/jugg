package com.android.tools.deployer.model;

import java.nio.file.Path;
import java.util.List;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class BaselineProfile {
   private final int minApi;
   private final int maxApi;
   private final List<Path> paths;

   public BaselineProfile(int minApi, int maxApi, List<Path> paths) {
      this.minApi = minApi;
      this.maxApi = maxApi;
      this.paths = paths;
   }

   public int getMaxApi() {
      return this.maxApi;
   }

   public int getMinApi() {
      return this.minApi;
   }

   public List<Path> getPaths() {
      return this.paths;
   }
}
