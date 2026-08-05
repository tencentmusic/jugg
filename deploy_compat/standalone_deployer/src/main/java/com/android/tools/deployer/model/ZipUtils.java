package com.android.tools.deployer.model;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.io.BaseEncoding;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class ZipUtils {
   private static final int CENTRAL_DIRECTORY_FILE_HEADER_MAGIC = 33639248;
   private static final int CENTRAL_DIRECTORY_FILE_HEADER_SIZE = 46;
   private static final int LOCAL_DIRECTORY_FILE_HEADER_SIZE = 30;
   private static final String DIGEST_ALGORITHM = "SHA-1";

   @VisibleForTesting
   public static Map<String, ZipEntry> readZipEntries(byte[] buf) {
      ByteBuffer buffer = ByteBuffer.wrap(buf);
      return (Map)readZipEntries(buffer).stream().collect(Collectors.toMap((e) -> e.name, Functions.identity()));
   }

   public static List<ZipEntry> readZipEntries(ByteBuffer buf) {
      buf.order(ByteOrder.LITTLE_ENDIAN);
      List<ZipEntry> entries = new ArrayList();

      while(buf.remaining() >= 46 && buf.getInt() == 33639248) {
         short version = buf.getShort();
         short versionNeeded = buf.getShort();
         short flags = buf.getShort();
         short compression = buf.getShort();
         short modTime = buf.getShort();
         short modDate = buf.getShort();
         long crc = uintToLong(buf.getInt());
         long compressedSize = uintToLong(buf.getInt());
         long decompressedSize = uintToLong(buf.getInt());
         int pathLength = ushortToInt(buf.getShort());
         int extraLength = ushortToInt(buf.getShort());
         int commentLength = ushortToInt(buf.getShort());
         buf.position(buf.position() + 8);
         long start = uintToLong(buf.getInt());
         byte[] pathBytes = new byte[pathLength];
         buf.get(pathBytes);
         String name = new String(pathBytes, Charset.forName("UTF-8"));
         buf.position(buf.position() + extraLength + commentLength);
         byte[] localFileHeader = new byte[30 + pathBytes.length];
         ByteBuffer fakeEntry = ByteBuffer.wrap(localFileHeader).order(ByteOrder.LITTLE_ENDIAN);
         fakeEntry.putLong(start);
         fakeEntry.putShort(versionNeeded);
         fakeEntry.putShort(modTime);
         fakeEntry.putShort(modDate);
         fakeEntry.putInt((int)crc);
         fakeEntry.putInt(longToUint(compressedSize));
         fakeEntry.putInt(longToUint(decompressedSize));
         fakeEntry.putShort(intToUShort(pathLength));
         fakeEntry.putShort(intToUShort(extraLength));
         fakeEntry.put(pathBytes);
         long approx_end = start + 30L + (long)pathLength - 1L;
         approx_end += compression == 0 ? decompressedSize : compressedSize;
         ZipEntry entry = new ZipEntry(crc, name, start, approx_end, localFileHeader);
         entries.add(entry);
      }

      return entries;
   }

   public static String digest(ByteBuffer buffer) {
      MessageDigest messageDigest;
      try {
         messageDigest = MessageDigest.getInstance("SHA-1");
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException("MessageDigest:SHA-1 unavailable.", e);
      }

      messageDigest.update(buffer);
      byte[] digestBytes = messageDigest.digest();
      return BaseEncoding.base16().lowerCase().encode(digestBytes);
   }

   public static short intToUShort(int integer) {
      if ((integer & -65536) != 0) {
         throw new IllegalStateException("Cannot cast int to uint16 (does not fit)");
      } else {
         return (short)integer;
      }
   }

   public static long uintToLong(int integer) {
      return (long)integer & 4294967295L;
   }

   public static int longToUint(long integer) {
      if ((integer & -4294967296L) != 0L) {
         throw new IllegalStateException("Cannot cast long to uint32 (does not fit)");
      } else {
         return (int)integer;
      }
   }

   public static int ushortToInt(short integer) {
      return integer & '\uffff';
   }

   public static class ZipEntry implements Serializable {
      public final long crc;
      public final String name;
      public final long start;
      public final long approx_end;
      public final byte[] localFileHeader;

      ZipEntry(long crc, String name, long start, long approx_end, byte[] localFileHeader) {
         this.crc = crc;
         this.name = name;
         this.start = start;
         this.approx_end = approx_end;
         this.localFileHeader = localFileHeader;
      }
   }
}
