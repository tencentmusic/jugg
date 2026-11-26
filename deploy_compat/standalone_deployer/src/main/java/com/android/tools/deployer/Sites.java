package com.android.tools.deployer;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class Sites {
   public static String appData(String pkg) {
      return "/data/data/" + pkg + "/";
   }

   public static String appCodeCache(String pkg) {
      return appData(pkg) + "code_cache/";
   }

   public static String appStudio(String pkg) {
      return appCodeCache(pkg) + ".studio/";
   }

   public static String appLog(String pkg) {
      return appData(pkg) + ".agent-logs/";
   }

   public static String appStartupAgent(String pkg) {
      return appCodeCache(pkg) + "startup_agents/";
   }

   public static String appOverlays(String pkg) {
      return appCodeCache(pkg) + ".overlay/";
   }

   public static String appLiveLiteral(String pkg) {
      return appCodeCache(pkg) + ".ll/";
   }

   public static String deviceStudioFolder() {
      return "/data/local/tmp/.studio/";
   }

   public static String installerExecutableFolder() {
      return deviceStudioFolder() + "bin/";
   }

   public static String installerTmpFolder() {
      return deviceStudioFolder() + "tmp/";
   }

   public static String installerBinary() {
      return "installer";
   }

   public static String installerPath() {
      String var10000 = installerExecutableFolder();
      return var10000 + installerBinary();
   }
}
