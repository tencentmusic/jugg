package com.android.tools.deployer.model;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class FileDiff {
   public final ApkEntry oldFile;
   public final ApkEntry newFile;
   public final Status status;

   public FileDiff(ApkEntry oldFile, ApkEntry newFile, Status status) {
      this.oldFile = oldFile;
      this.newFile = newFile;
      this.status = status;
   }

   public String getName() {
      return this.oldFile == null ? this.newFile.getName() : this.oldFile.getName();
   }

   public static enum Status {
      CREATED,
      MODIFIED,
      DELETED,
      RESOURCE_NOT_IN_OVERLAY;
   }
}
