package com.android.tools.deployer.model;

import com.android.tools.deployer.model.component.ApkParserException;
import com.android.tools.tracer.Trace;
import com.sickworm.intellij.jugg.apk.manifest.BinaryXmlParser;
import com.sickworm.intellij.jugg.apk.manifest.ManifestActivityInfo;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class ApkParser {
   public static final int EOCD_SIGNATURE = 101010256;
   private static final byte[] SIGNATURE_BLOCK_MAGIC = "APK Sig Block 42".getBytes();
   private static final long USHRT_MAX = 65535L;
   public static final int EOCD_SIZE = 22;
   public static final String NO_MANIFEST_MSG = "Missing AndroidManifest.xml entry";
   private static final String NO_MANIFEST_MSG_DETAILS = "in '%s'";

   public static List<Apk> parsePaths(List<String> paths) throws ApkParserException {
      try (Trace ignored = Trace.begin("parseApks")) {
         List<Apk> newFiles = new ArrayList();

         for(String apkPath : paths) {
            newFiles.add(parse(apkPath));
         }

         return newFiles;
      }
   }

   public static ManifestActivityInfo getApkDetails(String path) throws IOException {
      ZipFile zipFile = new ZipFile(path);

      ManifestActivityInfo manifestInfo;
      try {
         ZipEntry manifestEntry = zipFile.getEntry("AndroidManifest.xml");
         if (manifestEntry == null) {
            StringBuilder msg = new StringBuilder("Missing AndroidManifest.xml entry");
            msg.append(" ");
            msg.append(String.format(Locale.US, "in '%s'", path));
            throw new IOException(msg.toString());
         }

         InputStream stream = zipFile.getInputStream(manifestEntry);
         manifestInfo = BinaryXmlParser.parseBinaryFromStream(stream);
      } catch (Throwable var6) {
         try {
            zipFile.close();
         } catch (Throwable var5) {
            var6.addSuppressed(var5);
         }

         throw var6;
      }

      zipFile.close();
      return manifestInfo;
   }

   private static File getApkFileFromPath(String apkPath) throws IOException {
      if (apkPath.startsWith("jar:")) {
         int separatorIndex = apkPath.lastIndexOf(33);
         if (separatorIndex != -1) {
            String subPath = apkPath.substring(separatorIndex + 1);
            FileSystem fileSystem = FileSystems.newFileSystem(URI.create(apkPath), Collections.emptyMap());

            File var5;
            try {
               Path outputApk = Files.createTempFile("extracted", ".apk");
               Files.copy(fileSystem.getPath(subPath), outputApk, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
               var5 = outputApk.toFile();
            } catch (Throwable var7) {
               if (fileSystem != null) {
                  try {
                     fileSystem.close();
                  } catch (Throwable var6) {
                     var7.addSuppressed(var6);
                  }
               }

               throw var7;
            }

            if (fileSystem != null) {
               fileSystem.close();
            }

            return var5;
         }
      }

      return new File(apkPath);
   }

   public static Apk parse(String apkPath) throws ApkParserException {
      try {
         File file = getApkFileFromPath(apkPath);
         String absolutePath = file.getAbsolutePath();
         RandomAccessFile raf = new RandomAccessFile(absolutePath, "r");

         String digest;
         List<ZipUtils.ZipEntry> zipEntries;
         try {
            FileChannel fileChannel = raf.getChannel();

            try {
               ApkArchiveMap map = new ApkArchiveMap();
               findCDLocation(fileChannel, map, apkPath);
               findSignatureLocation(fileChannel, map);
               digest = generateDigest(raf, map);
               zipEntries = readZipEntries(raf, map);
            } catch (Throwable var13) {
               if (fileChannel != null) {
                  try {
                     fileChannel.close();
                  } catch (Throwable var12) {
                     var13.addSuppressed(var12);
                  }
               }

               throw var13;
            }

            if (fileChannel != null) {
               fileChannel.close();
            }
         } catch (Throwable var14) {
            try {
               raf.close();
            } catch (Throwable var11) {
               var14.addSuppressed(var11);
            }

            throw var14;
         }

         raf.close();
         ManifestActivityInfo manifest = getApkDetails(absolutePath);
         String splitName = manifest.featureSplit();
         String apkFileName = splitName == null ? "base.apk" : "split_" + splitName + ".apk";
         List<String> targetPackages = manifest.instrumentationTargetPackage() == null
               ? Collections.emptyList()
               : Arrays.asList(manifest.instrumentationTargetPackage());
         Apk.Builder builder = Apk.builder().setName(apkFileName).setChecksum(digest).setPath(absolutePath)
               .setPackageName(manifest.packageName()).setDebuggable("true".equals(manifest.debuggable()))
               .setTargetPackages(targetPackages).setSdkLibraries(Collections.emptyList());

         for(ZipUtils.ZipEntry entry : zipEntries) {
            if (entry.name.startsWith("lib/")) {
               String[] paths = entry.name.split("/");
               if (paths.length > 1) {
                  builder.addLibraryAbi(paths[1]);
               }
            }

            builder.addApkEntry(entry);
         }

         return builder.build();
      } catch (Exception e) {
         throw new ApkParserException(e);
      }
   }

   public static void findSignatureLocation(FileChannel channel, ApkArchiveMap map) {
      try {
         ByteBuffer signatureBlockMagicNumber = ByteBuffer.allocate(SIGNATURE_BLOCK_MAGIC.length);
         channel.read(signatureBlockMagicNumber, map.cdOffset - (long)SIGNATURE_BLOCK_MAGIC.length);
         signatureBlockMagicNumber.rewind();
         if (!signatureBlockMagicNumber.equals(ByteBuffer.wrap(SIGNATURE_BLOCK_MAGIC))) {
            return;
         }

         ByteBuffer sizeBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
         channel.read(sizeBuffer, map.cdOffset - (long)SIGNATURE_BLOCK_MAGIC.length - 8L);
         sizeBuffer.rewind();
         long lowerSignatureBlockSize = sizeBuffer.getLong();
         sizeBuffer.rewind();
         channel.read(sizeBuffer, map.cdOffset - 8L - lowerSignatureBlockSize);
         sizeBuffer.rewind();
         long upperSignatureBlocSize = sizeBuffer.getLong();
         if (lowerSignatureBlockSize != upperSignatureBlocSize) {
            return;
         }

         map.signatureBlockOffset = map.cdOffset - 8L - lowerSignatureBlockSize;
         map.signatureBlockSize = lowerSignatureBlockSize;
      } catch (IOException var8) {
      }

   }

   public static void findCDLocation(FileChannel channel, ApkArchiveMap map, String path) throws IOException, ApkParserException {
      long fileSize = channel.size();
      if (fileSize < 22L) {
         throw new ApkParserException("File " + path + " is too small to be a valid zip file");
      } else {
         ByteBuffer eocdBuffer = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
         channel.read(eocdBuffer, fileSize - 22L);
         eocdBuffer.rewind();
         if (!readEOCD(map, eocdBuffer)) {
            ByteBuffer endofFileBuffer = ByteBuffer.allocate((int)Math.min(fileSize, 65557L)).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(endofFileBuffer, fileSize - (long)endofFileBuffer.capacity());
            endofFileBuffer.position(endofFileBuffer.capacity() - 22);

            while(!readEOCD(map, endofFileBuffer)) {
               if (endofFileBuffer.position() - 5 < 0) {
                  throw new ApkParserException("Unable to find " + path + "'s ECOD signature");
               }

               endofFileBuffer.position(endofFileBuffer.position() - 5);
            }

         }
      }
   }

   private static boolean readEOCD(ApkArchiveMap map, ByteBuffer buffer) {
      if (buffer.getInt() != 101010256) {
         return false;
      } else {
         buffer.position(buffer.position() + 8);
         map.cdSize = ZipUtils.uintToLong(buffer.getInt());
         map.cdOffset = ZipUtils.uintToLong(buffer.getInt());
         return true;
      }
   }

   private static List<ZipUtils.ZipEntry> readZipEntries(RandomAccessFile randomAccessFile, ApkArchiveMap map) throws IOException {
      ByteBuffer buffer;
      if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
         byte[] cdContent = new byte[(int)map.cdSize];
         randomAccessFile.seek(map.cdOffset);
         randomAccessFile.readFully(cdContent);
         buffer = ByteBuffer.wrap(cdContent);
      } else {
         buffer = randomAccessFile.getChannel().map(MapMode.READ_ONLY, map.cdOffset, map.cdSize);
      }

      return ZipUtils.readZipEntries(buffer);
   }

   private static String generateDigest(RandomAccessFile randomAccessFile, ApkArchiveMap map) throws IOException {
      byte[] sigContent;
      if (map.signatureBlockOffset != -1L) {
         sigContent = new byte[(int)map.signatureBlockSize];
         randomAccessFile.seek(map.signatureBlockOffset);
         randomAccessFile.readFully(sigContent);
      } else {
         sigContent = new byte[(int)map.cdSize];
         randomAccessFile.seek(map.cdOffset);
         randomAccessFile.readFully(sigContent);
      }

      ByteBuffer buffer = ByteBuffer.wrap(sigContent);
      return ZipUtils.digest(buffer);
   }

   public static class ApkArchiveMap {
      public static final long UNINITIALIZED = -1L;
      long cdOffset = -1L;
      long cdSize = -1L;
      long signatureBlockOffset = -1L;
      long signatureBlockSize = -1L;

      public long getCdOffset() {
         return this.cdOffset;
      }

      public long getSignatureBlockOffset() {
         return this.signatureBlockOffset;
      }
   }
}
