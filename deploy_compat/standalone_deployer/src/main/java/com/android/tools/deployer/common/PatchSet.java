package com.android.tools.deployer.common;

import com.android.tools.deploy.proto.Deploy;
import java.util.ArrayList;
import java.util.List;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class PatchSet {
   public static final PatchSet NO_CHANGES;
   public static final PatchSet SIZE_THRESHOLD_EXCEEDED;
   public static final PatchSet INVALID;
   private final Status status;
   private final List<Deploy.PatchInstruction> patches;

   private PatchSet(Status status) {
      this.status = status;
      this.patches = new ArrayList();
   }

   public PatchSet(List<Deploy.PatchInstruction> patches) {
      this.status = PatchSet.Status.Ok;
      this.patches = patches;
   }

   public Status getStatus() {
      return this.status;
   }

   public List<Deploy.PatchInstruction> getPatches() {
      return this.patches;
   }

   static {
      NO_CHANGES = new PatchSet(PatchSet.Status.NoChanges);
      SIZE_THRESHOLD_EXCEEDED = new PatchSet(PatchSet.Status.SizeThresholdExceeded);
      INVALID = new PatchSet(PatchSet.Status.Invalid);
   }

   public static enum Status {
      Ok,
      NoChanges,
      Invalid,
      SizeThresholdExceeded;
   }
}
