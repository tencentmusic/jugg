package com.android.tools.deployer.common;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IDevice.Feature;
import com.google.common.annotations.VisibleForTesting;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class ApkVerifierTracker {
   @VisibleForTesting
   public static final String SKIP_VERIFICATION_OPTION = "--skip-verification";
   @VisibleForTesting
   static final long TIME_BETWEEN_VERIFICATIONS_MS;
   private static final Map<String, Long> lastVerifiedTimeMap;

   public static synchronized String getSkipVerificationInstallationFlag(IDevice device, String packageName) {
      return getSkipVerificationInstallationFlag(device, packageName, System.currentTimeMillis());
   }

   @VisibleForTesting
   public static String getSkipVerificationInstallationFlag(IDevice device, String packageName, long currentTimeMs) {
      if (!device.supportsFeature(Feature.SKIP_VERIFICATION)) {
         return null;
      } else {
         String key = getVerifiedTimeMapKey(device, packageName);
         if (!lastVerifiedTimeMap.containsKey(key)) {
            lastVerifiedTimeMap.put(key, currentTimeMs - TIME_BETWEEN_VERIFICATIONS_MS);
         }

         long lastVerifiedTime = (Long)lastVerifiedTimeMap.get(key);
         if (currentTimeMs - lastVerifiedTime < TIME_BETWEEN_VERIFICATIONS_MS) {
            return "--skip-verification";
         } else {
            lastVerifiedTimeMap.put(key, currentTimeMs);
            return null;
         }
      }
   }

   @VisibleForTesting
   public static void clear() {
      lastVerifiedTimeMap.clear();
   }

   private static String getVerifiedTimeMapKey(IDevice device, String packageName) {
      return String.format("%s:%s", device.getSerialNumber(), packageName);
   }

   static {
      TIME_BETWEEN_VERIFICATIONS_MS = TimeUnit.HOURS.toMillis(1L);
      lastVerifiedTimeMap = new HashMap();
   }
}
