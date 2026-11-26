package com.android.tools.deployer;

import com.android.tools.deployer.common.DeployerException;
import com.android.tools.deployer.model.Apk;
import com.android.tools.deployer.model.ApkEntry;
import com.android.tools.deployer.model.FileDiff;
import com.android.tools.idea.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts changed APK entries into the resource payload consumed by OptimisticApkSwapper. */
public final class ApkEntryExtractor {
   private final Predicate<String> filter;

   public ApkEntryExtractor(Predicate<String> filter) {
      this.filter = filter;
   }

   public SortedMap<ApkEntry, ByteString> extractFromDiffs(List<FileDiff> diffs, Apk targetApk) {
      SortedMap<ApkEntry, ByteString> extracted = new TreeMap<>((a, b) ->
            a.getQualifiedPath().compareTo(b.getQualifiedPath()));
      for (FileDiff diff : diffs) {
         if (shouldExtract(diff)) extract(diff.newFile, targetApk, extracted);
      }
      return extracted;
   }

   private boolean shouldExtract(FileDiff diff) {
      return diff.newFile != null && diff.status != FileDiff.Status.DELETED && this.filter.test(diff.newFile.getName());
   }

   private void extract(ApkEntry entry, Apk targetApk, SortedMap<ApkEntry, ByteString> extracted) {
      try (ZipFile zipFile = new ZipFile(entry.getApk().path)) {
         ZipEntry zipEntry = zipFile.getEntry(entry.getName());
         if (zipEntry == null) throw DeployerException.entryNotFound(entry.getName(), entry.getApk().path);
         try (InputStream input = zipFile.getInputStream(zipEntry)) {
            extracted.put(new ApkEntry(entry.getName(), entry.getChecksum(), targetApk),
                  ByteString.copyFrom(input.readAllBytes()));
         }
      } catch (IOException error) {
         throw DeployerException.entryUnzipFailed(error);
      }
   }
}
