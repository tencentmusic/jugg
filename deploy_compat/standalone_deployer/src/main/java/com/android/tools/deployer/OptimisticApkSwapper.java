package com.android.tools.deployer;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.proto.Deploy.ClassDef;
import com.android.tools.deploy.proto.Deploy.OverlayFile;
import com.android.tools.deploy.proto.Deploy.OverlaySwapRequest;
import com.android.tools.deploy.proto.Deploy.RestartActivityRequest;
import com.android.tools.deploy.proto.Deploy.OverlayIdPushResponse.Status;
import com.android.tools.deployer.common.DeployerException;
import com.android.tools.deployer.common.DeployerOption;
import com.android.tools.deployer.common.DeploymentCacheDatabase;
import com.android.tools.deployer.common.Installer;
import com.android.tools.deployer.common.OverlayId;
import com.android.tools.deployer.model.ApkEntry;
import com.android.tools.deployer.model.DexClass;
import com.android.tools.idea.protobuf.ByteString;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class OptimisticApkSwapper {
   private final Installer installer;
   private final boolean restart;
   private final Map<Integer, ClassRedefiner> redefiners;
   private final MetricsRecorder metrics;
   private final DeployerOption options;

   public OptimisticApkSwapper(Installer installer, Map<Integer, ClassRedefiner> redefiners, boolean restart, DeployerOption options, MetricsRecorder metrics) {
      this.installer = installer;
      this.redefiners = redefiners;
      this.restart = restart;
      this.options = options;
      this.metrics = metrics;
   }

   public SwapResult optimisticSwap(String packageId, List<Integer> pids, Deploy.Arch arch, OverlayUpdate overlayUpdate) throws DeployerException {
      DeploymentCacheDatabase.Entry cachedDump = overlayUpdate.cachedDump;
      DexComparator.ChangedClasses dexOverlays = overlayUpdate.dexOverlays;
      Map<ApkEntry, ByteString> fileOverlays = overlayUpdate.fileOverlays;
      OverlayId.Builder overlayIdBuilder = OverlayId.builder(cachedDump.getOverlayId());
      OverlayId expectedOverlayId = cachedDump.getOverlayId();
      Deploy.OverlaySwapRequest.Builder request = OverlaySwapRequest.newBuilder().setPackageName(packageId).setRestartActivity(this.restart).setArch(arch).setExpectedOverlayId(expectedOverlayId.isBaseInstall() ? "" : expectedOverlayId.getSha()).setAlwaysUpdateOverlay(this.options.fastRestartOnSwapFail);
      boolean hasDebuggerAttached = false;

      for(Integer pid : pids) {
         if (this.redefiners.containsKey(pid)) {
            ClassRedefiner redefiner = (ClassRedefiner)this.redefiners.get(pid);
            if (redefiner.canRedefineClass().support != ClassRedefiner.RedefineClassSupport.FULL) {
               throw new IllegalArgumentException("R+ Device should have FULL debugger swap support");
            }

            hasDebuggerAttached = true;
         } else {
            request.addProcessIds(pid);
         }
      }

      for(DexClass clazz : dexOverlays.newClasses) {
         request.addNewClasses(ClassDef.newBuilder().setName(clazz.name).setDex(ByteString.copyFrom(clazz.code)));
         String file = String.format(Locale.US, "%s.dex", clazz.name);
         overlayIdBuilder.addOverlayFile(file, clazz.checksum);
      }

      for(DexClass clazz : dexOverlays.modifiedClasses) {
         request.addModifiedClasses(ClassDef.newBuilder().setName(clazz.name).setDex(ByteString.copyFrom(clazz.code)).addAllFields(clazz.variableStates));
         String file = String.format(Locale.US, "%s.dex", clazz.name);
         overlayIdBuilder.addOverlayFile(file, clazz.checksum);
      }

      for(Map.Entry<ApkEntry, ByteString> entry : fileOverlays.entrySet()) {
         request.addResourceOverlays(OverlayFile.newBuilder().setPath(((ApkEntry)entry.getKey()).getQualifiedPath()).setContent((ByteString)entry.getValue()));
         overlayIdBuilder.addOverlayFile(((ApkEntry)entry.getKey()).getQualifiedPath(), ((ApkEntry)entry.getKey()).getChecksum());
      }

      request.setStructuralRedefinition(this.options.useStructuralRedefinition);
      request.setVariableReinitialization(this.options.useVariableReinitialization);
      OverlayId overlayId = overlayIdBuilder.build();
      request.setOverlayId(overlayId.getSha());
      Deploy.OverlaySwapRequest swapRequest = request.build();
      InstallerResponseHandler.SuccessStatus successStatus = InstallerResponseHandler.SuccessStatus.OK;
      if (hasDebuggerAttached) {
         try {
            Deploy.OverlayIdPushResponse response = this.installer.verifyOverlayId(request.getPackageName(), request.getExpectedOverlayId());
            if (response.getStatus() != Status.OK) {
               throw DeployerException.overlayIdMismatch();
            }
         } catch (IOException e) {
            throw DeployerException.installerIoException(e);
         }

         for(Map.Entry<Integer, ClassRedefiner> entry : this.redefiners.entrySet()) {
            switch (this.sendSwapRequest(swapRequest, (ClassRedefiner)entry.getValue())) {
               case SWAP_FAILED_BUT_APP_UPDATED:
                  successStatus = InstallerResponseHandler.SuccessStatus.SWAP_FAILED_BUT_APP_UPDATED;
               case OK:
                  break;
               default:
                  throw new IllegalStateException("Unknown swap status");
            }
         }
      }

      switch (this.sendSwapRequest(swapRequest, new InstallerBasedClassRedefiner(this.installer))) {
         case SWAP_FAILED_BUT_APP_UPDATED:
            successStatus = InstallerResponseHandler.SuccessStatus.SWAP_FAILED_BUT_APP_UPDATED;
         case OK:
            if (this.restart) {
               try {
                  this.installer.restartActivity(RestartActivityRequest.newBuilder().setApplicationId(packageId).setArch(arch).addAllProcessIds(pids).build());
               } catch (IOException io) {
                  throw DeployerException.installerIoException(io);
               }
            }

            return new SwapResult(overlayId, successStatus == InstallerResponseHandler.SuccessStatus.OK);
         default:
            throw new IllegalStateException("Unknown swap status");
      }
   }

   private InstallerResponseHandler.SuccessStatus sendSwapRequest(Deploy.OverlaySwapRequest request, ClassRedefiner redefiner) throws DeployerException {
      Deploy.SwapResponse swapResponse = redefiner.redefine(request);
      this.metrics.add(swapResponse.getAgentLogsList());
      return (new InstallerResponseHandler(this.options.useStructuralRedefinition ? InstallerResponseHandler.RedefinitionCapability.ALLOW_ADD_FIELD : InstallerResponseHandler.RedefinitionCapability.MOFIFY_CODE_ONLY)).handle(swapResponse);
   }

   public static final class OverlayUpdate {
      private final DeploymentCacheDatabase.Entry cachedDump;
      private final DexComparator.ChangedClasses dexOverlays;
      private final Map<ApkEntry, ByteString> fileOverlays;

      public OverlayUpdate(DeploymentCacheDatabase.Entry cachedDump, DexComparator.ChangedClasses dexOverlays, Map<ApkEntry, ByteString> fileOverlays) {
         this.cachedDump = cachedDump;
         this.dexOverlays = dexOverlays;
         this.fileOverlays = fileOverlays;
      }
   }

   public static class SwapResult {
      public final OverlayId overlayId;
      public final boolean hotswapSucceeded;

      private SwapResult(OverlayId overlayId, boolean hotswapSucceeded) {
         this.overlayId = overlayId;
         this.hotswapSucceeded = hotswapSucceeded;
      }
   }
}
