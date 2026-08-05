package com.android.tools.deployer.install;

import com.android.ddmlib.InstallReceiver;
import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.proto.Deploy.InstallInfo;
import com.android.tools.deployer.common.AdbClient;
import com.android.tools.deployer.common.ApkDiffer;
import com.android.tools.deployer.common.ApplicationDumper;
import com.android.tools.deployer.common.DeployMetric;
import com.android.tools.deployer.common.DeployerException;
import com.android.tools.deployer.common.DeployerOption;
import com.android.tools.deployer.common.InstallOptions;
import com.android.tools.deployer.common.InstallStatus;
import com.android.tools.deployer.common.Installer;
import com.android.tools.deployer.common.PatchSet;
import com.android.tools.deployer.common.PatchSetGenerator;
import com.android.tools.deployer.common.Timeouts;
import com.android.tools.deployer.common.UIService;
import com.android.tools.deployer.model.Apk;
import com.android.tools.deployer.model.DeploymentPlan;
import com.android.tools.deployer.model.FileDiff;
import com.android.utils.ILogger;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class ApkInstaller {
   private final AdbClient adb;
   private final UIService service;
   private final Installer installer;
   private final ILogger logger;

   public ApkInstaller(AdbClient adb, UIService service, Installer installer, ILogger logger) {
      this.adb = adb;
      this.service = service;
      this.installer = installer;
      this.logger = logger;
   }

   public boolean install(DeploymentPlan plan, DeployerOption deployOptions, InstallOptions installOptions, InstallMode installMode, Collection<DeployMetric> metrics) throws DeployerException {
      DeltaInstallResult deltaInstallResult = new DeltaInstallResult(ApkInstaller.DeltaInstallStatus.UNKNOWN, "");
      boolean allowReinstall = true;
      long deltaInstallStart = System.nanoTime();

      try {
         deltaInstallResult = this.deltaInstall(plan, deployOptions, installOptions, allowReinstall, installMode);
      } catch (DeployerException e) {
         this.logger.info("Unable to delta install: '%s'", new Object[]{e.getDetails()});
      }

      AdbClient.InstallResult result;
      result = new AdbClient.InstallResult(InstallStatus.OK, "");
      label47:
      switch (deltaInstallResult.status.ordinal()) {
         case 0:
            DeployMetric metric = new DeployMetric("DELTAINSTALL", deltaInstallStart);
            InstallReceiver installReceiver = new InstallReceiver();
            String[] lines = deltaInstallResult.packageManagerOutput.split("\\n");
            installReceiver.processNewLines(lines);
            installReceiver.done();
            if (installReceiver.isSuccessfullyCompleted()) {
               metric.finish(ApkInstaller.DeltaInstallStatus.SUCCESS.name(), metrics);
            } else {
               result = AdbClient.toInstallerResult(installReceiver);
               metric.finish(ApkInstaller.DeltaInstallStatus.ERROR.name() + "." + result.status.name(), metrics);
            }

            switch (result.status) {
               case NO_CERTIFICATE:
               case INSTALL_PARSE_FAILED_NO_CERTIFICATES:
                  result = this.invokeAdbInstall(this.adb, plan, installOptions.getFlags(), allowReinstall, installOptions.getShouldUseAssumeVerified());
                  long installStartTime = System.nanoTime();
                  DeployMetric installResult = new DeployMetric("INSTALL", installStartTime);
                  installResult.finish(result.status.name(), metrics);
               default:
                  break label47;
            }
         case 1:
         case 2:
         case 3:
         case 4:
         case 5:
         case 6:
         case 7:
         case 8:
         case 10:
         case 11:
         case 12:
         case 13:
         case 14:
            this.logger.info("Deltapush failed: " + deltaInstallResult.status.name(), new Object[0]);
            this.logger.info("Falling back to standard full install", new Object[0]);
            DeployMetric deltaNotPatchableMetric = new DeployMetric("DELTAINSTALL", deltaInstallStart);
            deltaNotPatchableMetric.finish(deltaInstallResult.status.name(), metrics);
            long installStartedNs = System.nanoTime();
            result = this.invokeAdbInstall(this.adb, plan, installOptions.getFlags(), allowReinstall, installOptions.getShouldUseAssumeVerified());
            DeployMetric installResult = new DeployMetric("INSTALL", installStartedNs);
            installResult.finish(result.status.name(), metrics);
            break;
         case 9:
            result = new AdbClient.InstallResult(InstallStatus.SKIPPED_INSTALL, "APKs have not been modified");
            DeployMetric installMetric = new DeployMetric("INSTALL");
            installMetric.finish(result.status.name(), metrics);

            try {
               this.adb.shell(new String[]{"am", "force-stop", plan.getApp().getAppId()}, Timeouts.SHELL_AM_STOP);
            } catch (IOException var17) {
               throw DeployerException.installFailed(InstallStatus.SKIPPED_INSTALL, "Failure to kill " + plan.getApp().getAppId());
            }
      }

      if (result.metrics != null) {
         metrics.add(new DeployMetric("DDMLIB_UPLOAD", result.metrics.getUploadStartNs(), result.metrics.getUploadFinishNs()));
         metrics.add(new DeployMetric("DDMLIB_INSTALL", result.metrics.getInstallStartNs(), result.metrics.getInstallFinishNs()));
      }

      String message = message(result);
      switch (result.status) {
         case INSTALL_FAILED_UPDATE_INCOMPATIBLE:
         case INCONSISTENT_CERTIFICATES:
         case INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE:
         case INSTALL_FAILED_VERSION_DOWNGRADE:
         case INSTALL_FAILED_DEXOPT:
            StringBuilder sb = new StringBuilder();
            sb.append(message).append("\n");
            sb.append("In order to proceed, you will have to uninstall the existing application");
            sb.append("\n\nWARNING: Uninstalling will remove the application data!\n\n");
            sb.append("Do you want to uninstall the existing application?");
            if (this.service.prompt(sb.toString())) {
               this.adb.uninstall(plan.getApp().getAppId());
               result = this.invokeAdbInstall(this.adb, plan, installOptions.getFlags(), allowReinstall, installOptions.getShouldUseAssumeVerified());
               message = message(result);
            }
      }

      boolean installed = true;
      if (result.status == InstallStatus.SKIPPED_INSTALL) {
         installed = false;
      } else if (result.status != InstallStatus.OK) {
         StringBuilder messageBuilder = new StringBuilder(message);
         messageBuilder.append("\nList of apks:\n");

         for(int i = 0; i < plan.getApp().getApks().size(); ++i) {
            String apkPath = ((Apk)plan.getApp().getApks().get(i)).path;
            String line = String.format(Locale.ROOT, "[%d] '%s'\n", i, apkPath);
            messageBuilder.append(line);
         }

         throw DeployerException.installFailed(result.status, messageBuilder.toString());
      }

      return installed;
   }

   DeltaInstallResult deltaInstall(DeploymentPlan plan, DeployerOption deployerOption, InstallOptions installOptions, boolean allowReinstall, InstallMode installMode) throws DeployerException {
      if (installMode == InstallMode.FULL) {
         return new DeltaInstallResult(ApkInstaller.DeltaInstallStatus.DISABLED);
      } else if (!plan.getApp().getBaselineProfile(this.adb.getDevice().getVersion().getApiLevel()).isEmpty()) {
         return new DeltaInstallResult(ApkInstaller.DeltaInstallStatus.BASELINE_PROFILE_NOT_SUPPORTED);
      } else if (!this.adb.getVersion().isAtLeast(24)) {
         return new DeltaInstallResult(ApkInstaller.DeltaInstallStatus.API_NOT_SUPPORTED);
      } else {
         List<Apk> localApks = plan.getApksForPackageManager();

         ApplicationDumper.Dump dump;
         try {
            dump = (new ApplicationDumper(this.installer)).dump(localApks);
         } catch (DeployerException e) {
            if (e.getError() == DeployerException.Error.DUMP_UNKNOWN_PACKAGE) {
               return new DeltaInstallResult(ApkInstaller.DeltaInstallStatus.DUMP_UNKNOWN_PACKAGE);
            }

            return new DeltaInstallResult(ApkInstaller.DeltaInstallStatus.DUMP_FAILED);
         }

         Deploy.InstallInfo.Builder builder = InstallInfo.newBuilder();
         builder.addAllOptions(installOptions.getFlags());
         if (allowReinstall) {
            builder.addOptions("-r");
         }

         PatchSet patchSet = (new PatchSetGenerator(installMode == InstallMode.DELTA_NO_SKIP ? PatchSetGenerator.WhenNoChanges.GENERATE_PATCH_ANYWAY : PatchSetGenerator.WhenNoChanges.GENERATE_EMPTY_PATCH, this.logger)).generateFromApks(localApks, dump.apks);
         switch (patchSet.getStatus()) {
            case NoChanges:
               return new DeltaInstallResult(ApkInstaller.DeltaInstallStatus.NO_CHANGES);
            case Invalid:
            case SizeThresholdExceeded:
               return new DeltaInstallResult(ApkInstaller.DeltaInstallStatus.CANNOT_GENERATE_DELTA);
            case Ok:
               List<Deploy.PatchInstruction> patches = patchSet.getPatches();

               for(Deploy.PatchInstruction patch : patches) {
                  this.logger.info("Patch size %d", new Object[]{patch.getSerializedSize()});
               }

               boolean inherit = canInherit(localApks.size(), (new ApkDiffer()).diff(dump.apks, localApks), installMode);
               builder.setInherit(inherit);
               builder.addAllPatchInstructions(patches);
               builder.setPackageName(plan.getApp().getAppId());
               builder.setAssumeVerified(plan.getApp().isDebuggable() && installOptions.getShouldUseAssumeVerified());
               Deploy.InstallInfo info = builder.build();
               int serializedSize = info.getSerializedSize();
               int customDeltaInstallThreshold = deployerOption.maxDeltaInstallPatchSize;
               if (customDeltaInstallThreshold > 0) {
                  if (serializedSize > customDeltaInstallThreshold) {
                     this.logger.info("Total Patch Size %d exceeds custom limit of %d", new Object[]{serializedSize, customDeltaInstallThreshold});
                     return new DeltaInstallResult(ApkInstaller.DeltaInstallStatus.CUSTOM_PATCH_SIZE_EXCEEDED);
                  }
               } else if (serializedSize > 41943040) {
                  this.logger.info("Total Patch Size %d exceeds default limit of %d", new Object[]{serializedSize, ApkInstaller.DeltaInstallStatus.PATCH_SIZE_EXCEEDED});
                  return new DeltaInstallResult(ApkInstaller.DeltaInstallStatus.PATCH_SIZE_EXCEEDED);
               }

               Deploy.DeltaInstallResponse res;
               try {
                  res = this.installer.deltaInstall(info);
               } catch (IOException var17) {
                  return new DeltaInstallResult(ApkInstaller.DeltaInstallStatus.UNKNOWN);
               }

               DeltaInstallStatus status = convertStatus(res.getStatus());
               return new DeltaInstallResult(status, res.getInstallOutput());
            default:
               throw new IllegalStateException("Unhandled PatchSet status");
         }
      }
   }

   private static DeltaInstallStatus convertStatus(Deploy.DeltaStatus status) {
      switch (status) {
         case STREAM_APK_FAILED:
            return ApkInstaller.DeltaInstallStatus.STREAM_APK_FAILED;
         case OK:
            return ApkInstaller.DeltaInstallStatus.SUCCESS;
         case UNKNOWN:
            return ApkInstaller.DeltaInstallStatus.UNKNOWN;
         case UNRECOGNIZED:
         case ERROR:
            return ApkInstaller.DeltaInstallStatus.ERROR;
         case SESSION_CREATE_FAILED:
            return ApkInstaller.DeltaInstallStatus.SESSION_CREATE_FAILED;
         case STREAM_APK_NOT_SUPPORTED:
            return ApkInstaller.DeltaInstallStatus.STREAM_APK_NOT_SUPPORTED;
         default:
            return ApkInstaller.DeltaInstallStatus.SUCCESS;
      }
   }

   public static boolean canInherit(int apkCount, List<FileDiff> diff, InstallMode mode) {
      boolean inherit = apkCount > 1;
      if (inherit) {
         for(FileDiff fileDiff : diff) {
            if (fileDiff.oldFile != null && fileDiff.oldFile.getName().equals("AndroidManifest.xml")) {
               inherit = false;
            }
         }
      }

      if (mode == InstallMode.DELTA_NO_SKIP) {
         inherit = inherit && !diff.isEmpty();
      }

      return inherit;
   }

   public static String message(AdbClient.InstallResult result) {
      switch (result.status) {
         case NO_CERTIFICATE:
            return "The APK was either not signed, or signed incorrectly.";
         case INSTALL_PARSE_FAILED_NO_CERTIFICATES:
            return "APK signature verification failed.";
         case INSTALL_FAILED_UPDATE_INCOMPATIBLE:
         case INCONSISTENT_CERTIFICATES:
            return "The device already has an application with the same package but a different signature.";
         case INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE:
         default:
            String var10000 = result.reason == null ? result.status.toString() : result.reason;
            return "Installation failed due to: '" + var10000 + "'";
         case INSTALL_FAILED_VERSION_DOWNGRADE:
            return "The device already has a newer version of this application.";
         case INSTALL_FAILED_DEXOPT:
            return "The device might have stale dexed jars that don't match the current version (dexopt error).";
         case DEVICE_NOT_RESPONDING:
            return "Device not responding.";
         case INSTALL_FAILED_OLDER_SDK:
            return "The application's minSdkVersion / targetSdkVersion is incompatible with the device. Check logcat for detailed message.";
         case DEVICE_NOT_FOUND:
            return "The device has been disconnected.";
         case SHELL_UNRESPONSIVE:
            return "The device timed out while trying to install the application.";
         case INSTALL_FAILED_INSUFFICIENT_STORAGE:
            return "The device needs more free storage to install the application (extra space is needed in addition to APK size).";
         case MULTI_APKS_NO_SUPPORTED_BELOW21:
            return "Multi-APK app installation is not supported on devices with API level < 21.";
         case INSTALL_FAILED_USER_RESTRICTED:
            return "Installation via USB is disabled.";
         case INSTALL_FAILED_INVALID_APK:
            return "The APKs are invalid.";
      }
   }

   private AdbClient.InstallResult invokeAdbInstall(AdbClient adb, DeploymentPlan plan, List<String> options, boolean allowReinstall, boolean shouldUseAssumeVerified) {
      return adb.install(plan, this.maybeInjectAssumeVerified(plan.getApp().isDebuggable(), shouldUseAssumeVerified, options), allowReinstall);
   }

   private List<String> maybeInjectAssumeVerified(boolean isDebuggable, boolean allowAssumeVerified, List<String> options) {
      if (!isDebuggable) {
         return options;
      } else if (!allowAssumeVerified) {
         return options;
      } else if (options.contains("assume-verfied")) {
         return options;
      } else {
         List<String> newOptions = Lists.newArrayList(options);
         newOptions.add("--dexopt-compiler-filter");
         newOptions.add("assume-verified");
         return newOptions;
      }
   }

   private static enum DeltaInstallStatus {
      SUCCESS,
      UNKNOWN,
      ERROR,
      DISABLED,
      CANNOT_GENERATE_DELTA,
      API_NOT_SUPPORTED,
      DUMP_FAILED,
      PATCH_SIZE_EXCEEDED,
      CUSTOM_PATCH_SIZE_EXCEEDED,
      NO_CHANGES,
      DUMP_UNKNOWN_PACKAGE,
      STREAM_APK_FAILED,
      STREAM_APK_NOT_SUPPORTED,
      BASELINE_PROFILE_NOT_SUPPORTED,
      SESSION_CREATE_FAILED;
   }

   private static class DeltaInstallResult {
      final DeltaInstallStatus status;
      final String packageManagerOutput;

      private DeltaInstallResult(DeltaInstallStatus status, String output) {
         this.status = status;
         this.packageManagerOutput = output;
      }

      private DeltaInstallResult(DeltaInstallStatus status) {
         this(status, "");
      }
   }
}
