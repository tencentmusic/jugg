package com.android.tools.deployer.common;

import com.android.tools.deployer.model.Apk;
import com.android.tools.deployer.model.ApkEntry;
import com.android.tools.deployer.model.FileDiff;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class ApkDiffer {
   public List<FileDiff> specDiff(DeploymentCacheDatabase.Entry cacheEntry, List<Apk> newApks) throws DeployerException {
      if (cacheEntry == null) {
         throw DeployerException.remoteApkNotFound();
      } else {
         DiffFunction compare = (oldFile, newFile) -> {
            Optional<FileDiff> normalDiff = standardDiff(oldFile, newFile);
            if (normalDiff.isPresent()) {
               return normalDiff;
            } else {
               boolean inOverlay = cacheEntry.getOverlayContents().containsFile(newFile.getQualifiedPath());
               boolean isResource = newFile.getName().startsWith("res");
               return !inOverlay && isResource ? Optional.of(new FileDiff((ApkEntry)null, newFile, FileDiff.Status.RESOURCE_NOT_IN_OVERLAY)) : Optional.empty();
            }
         };
         return this.diff(cacheEntry.getApks(), newApks, compare);
      }
   }

   public List<FileDiff> diff(List<Apk> oldApks, List<Apk> newApks) throws DeployerException {
      return this.diff(oldApks, newApks, ApkDiffer::standardDiff);
   }

   public List<FileDiff> diff(List<Apk> oldApks, List<Apk> newApks, DiffFunction compare) throws DeployerException {
      List<ApkEntry> oldFiles = new ArrayList();
      Map<String, Map<String, ApkEntry>> oldMap = new HashMap();
      groupFiles(oldApks, oldFiles, oldMap);
      List<ApkEntry> newFiles = new ArrayList();
      Map<String, Map<String, ApkEntry>> newMap = new HashMap();
      groupFiles(newApks, newFiles, newMap);
      if (newMap.size() != oldMap.size()) {
         throw DeployerException.apkCountMismatch();
      } else if (!newMap.keySet().equals(oldMap.keySet())) {
         throw DeployerException.apkNameMismatch();
      } else {
         List<FileDiff> diffs = new ArrayList();

         for(ApkEntry newFile : newFiles) {
            ApkEntry oldFile = (ApkEntry)((Map)oldMap.get(newFile.getApk().name)).get(newFile.getName());
            Optional<FileDiff> var10000 = compare.diff(oldFile, newFile);
            Objects.requireNonNull(diffs);
            var10000.ifPresent(diffs::add);
         }

         for(ApkEntry oldFile : oldFiles) {
            ApkEntry newFile = (ApkEntry)((Map)newMap.get(oldFile.getApk().name)).get(oldFile.getName());
            if (newFile == null) {
               Optional<FileDiff> var15 = compare.diff(oldFile, null);
               Objects.requireNonNull(diffs);
               var15.ifPresent(diffs::add);
            }
         }

         return diffs;
      }
   }

   private static void groupFiles(List<Apk> apks, List<ApkEntry> entries, Map<String, Map<String, ApkEntry>> map) {
      for(Apk apk : apks) {
         map.putIfAbsent(apk.name, apk.apkEntries);
         entries.addAll(apk.apkEntries.values());
      }

   }

   private static Optional<FileDiff> standardDiff(ApkEntry oldFile, ApkEntry newFile) {
      FileDiff.Status status = null;
      if (oldFile == null) {
         status = FileDiff.Status.CREATED;
      } else if (newFile == null) {
         status = FileDiff.Status.DELETED;
      } else if (oldFile.getChecksum() != newFile.getChecksum()) {
         status = FileDiff.Status.MODIFIED;
      }

      return status != null ? Optional.of(new FileDiff(oldFile, newFile, status)) : Optional.empty();
   }

   @FunctionalInterface
   private interface DiffFunction {
      Optional<FileDiff> diff(ApkEntry oldFile, ApkEntry newFile);
   }
}
