package com.android.tools.deployer.common;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.proto.Deploy.Arch;
import com.android.tools.deploy.proto.Deploy.DumpResponse.Status;
import com.android.tools.deployer.model.Apk;
import com.android.tools.deployer.model.ZipUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class ApplicationDumper {
   private final Installer installer;

   public ApplicationDumper(Installer installer) {
      this.installer = installer;
   }

   public static String getPackageName(List<Apk> apks) throws DeployerException {
      String packageName = null;

      for(Apk apk : apks) {
         if (packageName == null) {
            packageName = apk.packageName;
         }

         if (!apk.packageName.equals(packageName)) {
            throw DeployerException.swapMultiplePackages();
         }
      }

      return packageName;
   }

   public Dump dump(List<Apk> apks) throws DeployerException {
      String packageName = null;
      HashSet<String> targetPackages = new HashSet();

      for(Apk apk : apks) {
         if (packageName == null) {
            packageName = apk.packageName;
         }

         if (!apk.packageName.equals(packageName)) {
            throw DeployerException.swapMultiplePackages();
         }

         targetPackages.addAll(apk.targetPackages);
      }

      ArrayList<String> packagesToDump = new ArrayList();
      packagesToDump.add(packageName);
      packagesToDump.addAll(targetPackages);

      Deploy.DumpResponse response;
      try {
         response = this.installer.dump(packagesToDump);
      } catch (IOException e) {
         throw DeployerException.dumpFailed(e.getMessage());
      }

      if (response.getStatus() != Status.OK) {
         throwDumpError(packagesToDump, response);
      }

      return new Dump(GetApks(response.getPackages(0)), GetPids(response), GetArch(response));
   }

   public static void throwDumpError(List<String> packages, Deploy.DumpResponse response) throws DeployerException {
      if (response.getStatus() == Status.ERROR_PACKAGE_NOT_FOUND) {
         throw DeployerException.unknownPackage(response.getFailedPackage());
      }
      throw DeployerException.dumpBadResponse(packages, response.getStatus().getNumber());
   }

   private static List<Apk> GetApks(Deploy.PackageDump packageDump) {
      List<Apk> dumps = new ArrayList();

      for(Deploy.ApkDump dump : packageDump.getApksList()) {
         ByteBuffer cd = dump.getCd().asReadOnlyByteBuffer();
         ByteBuffer signature = dump.getSignature().asReadOnlyByteBuffer();
         List<ZipUtils.ZipEntry> zipEntries = ZipUtils.readZipEntries(cd);
         cd.rewind();
         String digest = ZipUtils.digest(signature.remaining() != 0 ? signature : cd);
         Apk.Builder builder = Apk.builder().setName(dump.getName()).setChecksum(digest).setPath(dump.getAbsolutePath());

         for(ZipUtils.ZipEntry entry : zipEntries) {
            builder.addApkEntry(entry);
         }

         dumps.add(builder.build());
      }

      return dumps;
   }

   private static Map<String, List<Integer>> GetPids(Deploy.DumpResponse response) {
      Map<String, List<Integer>> pids = new HashMap();

      for(Deploy.PackageDump packageDump : response.getPackagesList()) {
         if (!packageDump.getProcessesList().isEmpty()) {
            pids.put(packageDump.getName(), packageDump.getProcessesList());
         }
      }

      return pids;
   }

   private static Deploy.Arch GetArch(Deploy.DumpResponse response) throws DeployerException {
      Deploy.Arch result = Arch.ARCH_UNKNOWN;
      String lastPackageWithKnowArch = null;

      for(Deploy.PackageDump pkg : response.getPackagesList()) {
         Deploy.Arch arch = pkg.getArch();
         if (!arch.equals(Arch.ARCH_UNKNOWN)) {
            if (!result.equals(Arch.ARCH_UNKNOWN) && !result.equals(arch)) {
               throw DeployerException.dumpMixedArch(lastPackageWithKnowArch + " is " + String.valueOf(result) + " while " + pkg.getName() + " is " + String.valueOf(arch) + ".");
            }

            result = arch;
            lastPackageWithKnowArch = pkg.getName();
         }
      }

      return result;
   }

   public static class Dump {
      public final List<Apk> apks;
      public final Map<String, List<Integer>> packagePids;
      public final Deploy.Arch arch;

      public Dump(List<Apk> apks, Map<String, List<Integer>> packagePids, Deploy.Arch arch) {
         this.apks = apks;
         this.packagePids = packagePids;
         this.arch = arch;
      }
   }
}
