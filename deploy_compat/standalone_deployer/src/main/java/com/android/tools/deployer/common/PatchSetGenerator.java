package com.android.tools.deployer.common;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.proto.Deploy.PatchInstruction;
import com.android.tools.deployer.model.Apk;
import com.android.tools.idea.protobuf.ByteString;
import com.android.utils.ILogger;
import com.android.utils.Pair;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class PatchSetGenerator {
   public static final int MAX_PATCHSET_SIZE = 41943040;
   private ILogger logger;
   private final WhenNoChanges whenNoChanges;

   public PatchSetGenerator(WhenNoChanges whenNoChanges, ILogger logger) {
      this.logger = logger;
      this.whenNoChanges = whenNoChanges;
   }

   public PatchSet generateFromApks(List<Apk> localApks, List<Apk> remoteApks) {
      HashMap<String, Apk> localApkMap = new HashMap();

      for(Apk apk : localApks) {
         localApkMap.put(apk.name, apk);
      }

      HashMap<String, Apk> remoteApkMap = new HashMap();

      for(Apk apk : remoteApks) {
         remoteApkMap.put(apk.name, apk);
      }

      return this.generateFromApkSets(remoteApkMap, localApkMap);
   }

   public PatchSet generateFromApkSets(HashMap<String, Apk> remoteApks, HashMap<String, Apk> localApks) {
      try {
         if (remoteApks.size() != localApks.size()) {
            return PatchSet.INVALID;
         } else {
            List<Pair<Apk, Apk>> pairs = new ArrayList();

            for(Map.Entry<String, Apk> localApk : localApks.entrySet()) {
               if (!remoteApks.keySet().contains(((Apk)localApk.getValue()).name)) {
                  return PatchSet.INVALID;
               }

               pairs.add(Pair.of((Apk)localApk.getValue(), (Apk)remoteApks.get(((Apk)localApk.getValue()).name)));
            }

            return this.generateFromPairs(pairs);
         }
      } catch (IOException var6) {
         return PatchSet.INVALID;
      }
   }

   public PatchSet generateFromPairs(List<Pair<Apk, Apk>> pairs) throws IOException {
      ArrayList<Deploy.PatchInstruction> patches = new ArrayList();
      boolean noChanges = true;

      for(Pair<Apk, Apk> pair : pairs) {
         Apk localApk = (Apk)pair.getFirst();
         Apk remoteApk = (Apk)pair.getSecond();
         if (!remoteApk.checksum.equals(localApk.checksum)) {
            noChanges = false;
            break;
         }
      }

      if (noChanges && this.whenNoChanges == PatchSetGenerator.WhenNoChanges.GENERATE_EMPTY_PATCH) {
         return PatchSet.NO_CHANGES;
      } else {
         long patchSizes = 0L;

         for(Pair<Apk, Apk> pair : pairs) {
            Apk localApk = (Apk)pair.getFirst();
            Apk remoteApk = (Apk)pair.getSecond();
            Deploy.PatchInstruction instruction = null;
            if (localApk.checksum.equals(remoteApk.checksum)) {
               instruction = this.generateCleanPatch(remoteApk, localApk);
            } else {
               PatchGenerator.Patch patch = (new PatchGenerator(this.logger)).generate(remoteApk, localApk);
               switch (patch.status) {
                  case SizeThresholdExceeded:
                     return PatchSet.SIZE_THRESHOLD_EXCEEDED;
                  case Ok:
                     instruction = this.buildPatchInstruction(patch.destinationSize, patch.sourcePath, patch.instructions, patch.data);
                     break;
                  default:
                     throw new IllegalStateException("Unhandled PatchSet status");
               }
            }

            patchSizes += (long)(instruction.getInstructions().size() + instruction.getPatches().size());
            if (patchSizes > 41943040L) {
               return PatchSet.SIZE_THRESHOLD_EXCEEDED;
            }

            patches.add(instruction);
         }

         assert pairs.size() == patches.size();

         return new PatchSet(patches);
      }
   }

   private Deploy.PatchInstruction buildPatchInstruction(long size, String remotePath, ByteBuffer instruction, ByteBuffer data) {
      Deploy.PatchInstruction.Builder patchInstructionBuilder = PatchInstruction.newBuilder();
      patchInstructionBuilder.setSrcAbsolutePath(remotePath);
      patchInstructionBuilder.setPatches(ByteString.copyFrom(data));
      patchInstructionBuilder.setInstructions(ByteString.copyFrom(instruction));
      patchInstructionBuilder.setDstFilesize(size);
      return patchInstructionBuilder.build();
   }

   private Deploy.PatchInstruction generateCleanPatch(Apk remoteApk, Apk localApk) throws IOException {
      Deploy.PatchInstruction.Builder patchInstructionBuilder = PatchInstruction.newBuilder();
      PatchGenerator.Patch patch = (new PatchGenerator(this.logger)).generateCleanPatch(remoteApk, localApk);
      patchInstructionBuilder.setSrcAbsolutePath(patch.sourcePath);
      patchInstructionBuilder.setDstFilesize(patch.destinationSize);
      return patchInstructionBuilder.build();
   }

   public static enum WhenNoChanges {
      GENERATE_PATCH_ANYWAY,
      GENERATE_EMPTY_PATCH;
   }
}
