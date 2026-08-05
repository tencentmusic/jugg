package com.android.tools.deployer.common;

import com.android.tools.deployer.model.Apk;
import com.android.tools.deployer.model.ApkEntry;
import com.android.tools.tracer.Trace;
import com.android.utils.ILogger;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class PatchGenerator {
   private ILogger logger;

   public PatchGenerator(ILogger logger) {
      this.logger = logger;
   }

   public Patch generate(Apk remoteApk, Apk localApk) throws IOException {
      String sourcePath = remoteApk.path;
      long destinationSize = Files.size(Paths.get(localApk.path));
      List<ApkMap.Area> dirtyAreas = this.generateDirtyMap(remoteApk, localApk);
      long patchSize = 0L;

      for(ApkMap.Area dirtyArea : dirtyAreas) {
         patchSize += dirtyArea.size();
      }

      if (patchSize > 41943040L) {
         return new Patch(PatchGenerator.Patch.Status.SizeThresholdExceeded);
      } else {
         ByteBuffer data = ByteBuffer.wrap(new byte[Math.toIntExact(patchSize)]);
         ByteBuffer instructions = ByteBuffer.wrap(new byte[dirtyAreas.size() * 8]).order(ByteOrder.LITTLE_ENDIAN);
         Trace.begin("building patch");
         FileChannel fileChannel = FileChannel.open(Paths.get(localApk.path), StandardOpenOption.READ);

         try {
            for(ApkMap.Area dirtyArea : dirtyAreas) {
               instructions.putInt((int)dirtyArea.start);
               instructions.putInt((int)dirtyArea.size());
               data.limit((int)((long)data.position() + dirtyArea.size()));
               fileChannel.read(data, dirtyArea.start);
            }
         } catch (Throwable var15) {
            if (fileChannel != null) {
               try {
                  fileChannel.close();
               } catch (Throwable var14) {
                  var15.addSuppressed(var14);
               }
            }

            throw var15;
         }

         if (fileChannel != null) {
            fileChannel.close();
         }

         Trace.end();
         data.rewind();
         instructions.rewind();
         return new Patch(data, instructions, sourcePath, destinationSize);
      }
   }

   public Patch generateCleanPatch(Apk remoteApk, Apk localApk) throws IOException {
      String sourcePath = remoteApk.path;
      long destinationSize = Files.size(Paths.get(localApk.path));
      return new Patch((ByteBuffer)null, (ByteBuffer)null, sourcePath, destinationSize);
   }

   private List<ApkMap.Area> generateDirtyMap(Apk remoteApk, Apk localApk) throws IOException {
      Trace.begin("marking dirty");
      ApkMap dirtyMap = new ApkMap(Files.size(Paths.get(localApk.path)));

      for(ApkEntry remoteEntry : remoteApk.apkEntries.values()) {
         ApkEntry localEntry = (ApkEntry)localApk.apkEntries.get(remoteEntry.getName());
         if (localEntry != null && Arrays.equals(remoteEntry.getZipEntry().localFileHeader, localEntry.getZipEntry().localFileHeader)) {
            ApkMap.Area cleanArea = new ApkMap.Area(localEntry.getZipEntry().start, localEntry.getZipEntry().approx_end);
            dirtyMap.markClean(cleanArea);
         }
      }

      Trace.end();
      this.logger.info("Num dirty areas %d", new Object[]{dirtyMap.getDirtyAreas().size()});
      return dirtyMap.getDirtyAreas();
   }

   public static class Patch {
      public final Status status;
      public final ByteBuffer data;
      public final ByteBuffer instructions;
      public final String sourcePath;
      public final long destinationSize;

      Patch(ByteBuffer data, ByteBuffer instructions, String sourcePath, long destinationSize) {
         this.data = data;
         this.instructions = instructions;
         this.sourcePath = sourcePath;
         this.destinationSize = destinationSize;
         this.status = PatchGenerator.Patch.Status.Ok;
      }

      Patch(Status status) {
         this.data = null;
         this.instructions = null;
         this.sourcePath = null;
         this.destinationSize = 0L;
         this.status = status;
      }

      public static enum Status {
         Ok,
         SizeThresholdExceeded;
      }
   }
}
