package com.android.tools.deployer.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Represents the APK metadata consumed by Quail install and overlay operations. */
public final class Apk implements Serializable {
   public final String name;
   public final String checksum;
   public final String path;
   public final String packageName;
   public final boolean debuggable;
   public final List<String> libraryAbis;
   public final List<String> targetPackages;
   public final Map<String, ApkEntry> apkEntries;
   public final List<String> sdkLibraries;

   private Apk(Builder builder) {
      this.name = builder.name;
      this.checksum = builder.checksum;
      this.path = builder.path;
      this.packageName = builder.packageName;
      this.debuggable = builder.debuggable;
      this.libraryAbis = Collections.unmodifiableList(new ArrayList<>(builder.libraryAbis));
      this.targetPackages = Collections.unmodifiableList(new ArrayList<>(builder.targetPackages));
      this.sdkLibraries = Collections.unmodifiableList(new ArrayList<>(builder.sdkLibraries));
      this.apkEntries = Collections.unmodifiableMap(new LinkedHashMap<>(builder.apkEntries));
      this.apkEntries.values().forEach(entry -> entry.setApk(this));
   }

   public static Builder builder() {
      return new Builder();
   }

   /** Builds an immutable APK model while preserving archive entry order. */
   public static final class Builder {
      private final List<String> libraryAbis = new ArrayList<>();
      private final Map<String, ApkEntry> apkEntries = new LinkedHashMap<>();
      private String name = "";
      private String checksum = "";
      private String path = "";
      private String packageName = "";
      private boolean debuggable;
      private List<String> targetPackages = Collections.emptyList();
      private List<String> sdkLibraries = Collections.emptyList();

      public Builder setName(String name) {
         this.name = name;
         return this;
      }

      public Builder setChecksum(String checksum) {
         this.checksum = checksum;
         return this;
      }

      public Builder setPath(String path) {
         this.path = path;
         return this;
      }

      public Builder setPackageName(String packageName) {
         this.packageName = packageName;
         return this;
      }

      public Builder setTargetPackages(List<String> targetPackages) {
         this.targetPackages = targetPackages;
         return this;
      }

      public Builder setSdkLibraries(List<String> sdkLibraries) {
         this.sdkLibraries = sdkLibraries;
         return this;
      }

      public Builder setDebuggable(boolean debuggable) {
         this.debuggable = debuggable;
         return this;
      }

      public Builder addLibraryAbi(String abi) {
         this.libraryAbis.add(abi);
         return this;
      }

      public Builder addApkEntry(String name, long checksum) {
         this.apkEntries.put(name, new ApkEntry(name, checksum, null));
         return this;
      }

      public Builder addApkEntry(ZipUtils.ZipEntry zipEntry) {
         this.apkEntries.put(zipEntry.name, new ApkEntry(zipEntry));
         return this;
      }

      public Apk build() {
         return new Apk(this);
      }
   }
}
