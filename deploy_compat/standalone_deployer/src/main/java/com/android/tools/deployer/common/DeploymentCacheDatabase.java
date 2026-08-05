package com.android.tools.deployer.common;

import com.android.tools.deployer.model.Apk;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Maps;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class DeploymentCacheDatabase {
   public static final int DEFAULT_SIZE = 25;
   private final Cache<String, Entry> db;
   File persistFile;

   public DeploymentCacheDatabase(int size) {
      this(size, (File)null);
   }

   public DeploymentCacheDatabase(File persistFile) {
      this(25, persistFile);
   }

   public DeploymentCacheDatabase(int size, File persistFile) {
      this.persistFile = null;
      this.db = CacheBuilder.newBuilder().maximumSize((long)size).build();
      this.persistFile = persistFile;
      if (persistFile != null) {
         try {
            ObjectInputStream in = new ObjectInputStream(new FileInputStream(persistFile));

            try {
               HashMap<String, Entry> entries = (HashMap)in.readObject();

               for(Map.Entry<String, Entry> e : entries.entrySet()) {
                  this.db.put((String)e.getKey(), (Entry)e.getValue());
               }
            } catch (Throwable var8) {
               try {
                  in.close();
               } catch (Throwable var7) {
                  var8.addSuppressed(var7);
               }

               throw var8;
            }

            in.close();
         } catch (FileNotFoundException var9) {
         } catch (ClassNotFoundException | IOException e) {
            throw new IllegalStateException("Cannot load deployment cache", e);
         }

      }
   }

   public Entry get(String serial, String appId) {
      String key = String.format("%s:%s", serial, appId);
      return (Entry)this.db.getIfPresent(key);
   }

   public boolean store(String serial, String appId, List<Apk> newInstalledApks, OverlayId overlayId) {
      String key = String.format("%s:%s", serial, appId);
      this.db.put(key, new Entry(newInstalledApks, overlayId));
      this.writeToFile();
      return true;
   }

   public boolean invalidate(String serial, String appId) {
      String key = String.format("%s:%s", serial, appId);
      this.db.invalidate(key);
      this.writeToFile();
      return true;
   }

   public boolean writeToFile() {
      if (this.persistFile == null) {
         return false;
      } else {
         if (this.persistFile.exists()) {
            this.persistFile.delete();
         }

         try {
            ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(this.persistFile));

            try {
               HashMap<String, Entry> entries = Maps.newHashMap(this.db.asMap());
               out.writeObject(entries);
               out.flush();
            } catch (Throwable var5) {
               try {
                  out.close();
               } catch (Throwable var4) {
                  var5.addSuppressed(var4);
               }

               throw var5;
            }

            out.close();
      } catch (IOException e) {
         throw new IllegalStateException("Cannot write deployment cache", e);
      }

         return true;
      }
   }

   public static class Entry implements Serializable {
      private final List<Apk> apks;
      private final OverlayId oid;

      public List<Apk> getApks() {
         return this.apks;
      }

      public OverlayId getOverlayId() {
         return this.oid;
      }

      public OverlayId.Contents getOverlayContents() {
         return this.oid.getOverlayContents();
      }

      private Entry(List<Apk> apks, OverlayId overlayId) {
         this.apks = apks;
         this.oid = overlayId;
      }
   }
}
