package com.android.tools.deployer.common;

import com.android.tools.deployer.model.Apk;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.UnmodifiableIterator;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class OverlayId implements Serializable {
   public static final String SCHEMA_VERSION = "1.0";
   private final ImmutableList<Apk> installedApks;
   private final Contents overlayContents;
   private final String sha;
   private final boolean baseInstall;

   public OverlayId(List<Apk> apks) throws DeployerException {
      this.installedApks = ImmutableList.sortedCopyOf(Comparator.comparing((apk) -> apk.name), apks);
      this.overlayContents = new Contents(ImmutableSortedMap.of());
      this.sha = computeShaHex(this.getRepresentation());
      this.baseInstall = true;
   }

   private OverlayId(ImmutableList<Apk> installedApks, ImmutableSortedMap<String, Long> overlayFiles) throws DeployerException {
      this.installedApks = installedApks;
      this.overlayContents = new Contents(overlayFiles);
      this.sha = computeShaHex(this.getRepresentation());
      this.baseInstall = false;
   }

   public List<Apk> getInstalledApks() {
      return this.installedApks;
   }

   public Contents getOverlayContents() {
      return this.overlayContents;
   }

   public String getRepresentation() {
      StringBuilder rep = new StringBuilder();
      rep.append("Apply Changes Overlay ID\n");
      rep.append("Schema Version ");
      rep.append("1.0");
      rep.append("\n");
      UnmodifiableIterator var2 = this.installedApks.iterator();

      while(var2.hasNext()) {
         Apk apk = (Apk)var2.next();
         rep.append(String.format("Real APK %s has checksum of %s\n", apk.name, apk.checksum));
      }

      for(Map.Entry<String, Long> delta : this.overlayContents.contents.entrySet()) {
         rep.append(String.format(Locale.US, " Has overlayfile %s with checksum %d\n", delta.getKey(), delta.getValue()));
      }

      return rep.toString();
   }

   public boolean isBaseInstall() {
      return this.baseInstall;
   }

   public String getSha() {
      return this.sha;
   }

   private static String computeShaHex(String input) throws DeployerException {
      MessageDigest md = null;

      try {
         md = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException var7) {
         throw DeployerException.operationNotSupported("SHA-256 not supported on host");
      }

      StringBuilder sb = new StringBuilder();

      for(byte b : md.digest(input.getBytes(StandardCharsets.UTF_8))) {
         sb.append(String.format("%02x", b));
      }

      return sb.toString();
   }

   public static Builder builder(OverlayId prevOverlayId) {
      return new Builder(prevOverlayId);
   }

   public static class Builder {
      private final ImmutableList<Apk> installedApks;
      private final SortedMap<String, Long> overlayFiles;

      private Builder(OverlayId prevOverlayId) {
         this.installedApks = prevOverlayId.installedApks;
         this.overlayFiles = new TreeMap(prevOverlayId.overlayContents.contents);
      }

      public Builder addOverlayFile(String file, long checksum) {
         this.overlayFiles.put(file, checksum);
         return this;
      }

      public Builder removeOverlayFile(String file) {
         this.overlayFiles.remove(file);
         return this;
      }

      public OverlayId build() throws DeployerException {
         return new OverlayId(this.installedApks, ImmutableSortedMap.copyOfSorted(this.overlayFiles));
      }
   }

   public static class Contents implements Serializable {
      private final Map<String, Long> contents;

      private Contents(Map<String, Long> contents) {
         this.contents = contents;
      }

      public int size() {
         return this.contents.size();
      }

      public Long getFileChecksum(String path) {
         return (Long)this.contents.getOrDefault(path, -1L);
      }

      public boolean containsFile(String path) {
         return this.contents.containsKey(path);
      }

      public Set<String> allFiles() {
         return this.contents.keySet();
      }
   }
}
