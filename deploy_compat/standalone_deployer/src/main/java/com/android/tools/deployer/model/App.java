package com.android.tools.deployer.model;

import com.android.tools.deployer.model.component.ApkParserException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Holds the APKs and baseline profiles required by the standalone install path. */
public final class App {
   private final String appId;
   private final List<Apk> apks;
   private final List<BaselineProfile> baselineProfiles;

   private App(String appId, List<Apk> apks, List<BaselineProfile> baselineProfiles) {
      this.appId = appId;
      this.apks = Collections.unmodifiableList(new ArrayList<>(apks));
      this.baselineProfiles = Collections.unmodifiableList(new ArrayList<>(baselineProfiles));
   }

   public static App fromPaths(String appId, List<Path> paths) throws ApkParserException {
      List<Apk> apks = new ArrayList<>();
      List<BaselineProfile> baselineProfiles = new ArrayList<>();
      for (Path path : paths) {
         if (path.toString().endsWith(".apk")) {
            apks.add(ApkParser.parse(path.toAbsolutePath().toString()));
         } else if (path.toString().endsWith(".dm")) {
            if (baselineProfiles.isEmpty()) {
               baselineProfiles.add(new BaselineProfile(Integer.MIN_VALUE, Integer.MAX_VALUE, new ArrayList<>()));
            }
            baselineProfiles.get(0).getPaths().add(path);
         } else {
            throw new IllegalStateException("Unknown path type (neither apk nor dm):" + path);
         }
      }
      String resolvedAppId = appId != null ? appId : apks.isEmpty() ? "" : apks.get(0).packageName;
      return new App(resolvedAppId, apks, baselineProfiles);
   }

   public String getAppId() {
      return this.appId;
   }

   public List<Apk> getApks() {
      return this.apks;
   }

   public List<Apk> getApksForPackageManager(String abi) {
      return this.apks;
   }

   public boolean isDebuggable() {
      return this.apks.stream().anyMatch(apk -> apk.debuggable);
   }

   public List<Path> getBaselineProfile(int api) {
      for (BaselineProfile baselineProfile : this.baselineProfiles) {
         if (baselineProfile.getMinApi() <= api && api <= baselineProfile.getMaxApi()) {
            return baselineProfile.getPaths();
         }
      }
      return Collections.emptyList();
   }
}
