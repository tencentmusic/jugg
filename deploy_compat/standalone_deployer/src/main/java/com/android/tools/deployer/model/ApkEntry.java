package com.android.tools.deployer.model;

import com.google.common.annotations.VisibleForTesting;
import java.io.Serializable;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class ApkEntry implements Serializable {
   private final String name;
   private final long checksum;
   private final ZipUtils.ZipEntry entry;
   private Apk apk;

   ApkEntry(ZipUtils.ZipEntry entry) {
      this.name = entry.name;
      this.checksum = entry.crc;
      this.entry = entry;
   }

   @VisibleForTesting
   public ApkEntry(String name, long checksum, Apk apk) {
      this.name = name;
      this.checksum = checksum;
      this.entry = null;
      this.apk = apk;
   }

   public String getName() {
      return this.name;
   }

   public long getChecksum() {
      return this.checksum;
   }

   public ZipUtils.ZipEntry getZipEntry() {
      return this.entry;
   }

   public Apk getApk() {
      return this.apk;
   }

   public String getQualifiedPath() {
      return String.format("%s/%s", this.apk.name, this.name);
   }

   void setApk(Apk apk) {
      this.apk = apk;
   }
}
