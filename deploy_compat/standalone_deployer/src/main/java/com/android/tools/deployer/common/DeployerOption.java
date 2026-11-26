package com.android.tools.deployer.common;

import java.util.EnumSet;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class DeployerOption {
   public final boolean useOptimisticSwap;
   public final boolean useOptimisticResourceSwap;
   public final EnumSet<ChangeType> optimisticInstallSupport;
   public final boolean allowAssumeVerified;
   public final boolean useStructuralRedefinition;
   public final boolean useVariableReinitialization;
   public final boolean fastRestartOnSwapFail;
   public final boolean enableCoroutineDebugger;
   public final boolean skipPostInstallTasks;
   public final boolean useRootPushInstall;
   public final int maxDeltaInstallPatchSize;

   private DeployerOption(boolean useOptimisticSwap, boolean useOptimisticResourceSwap, EnumSet<ChangeType> optimisticInstallSupport, boolean allowAssumeVerified, boolean useStructuralRedefinition, boolean useVariableReinitialization, boolean fastRestartOnSwapFail, boolean enableCoroutineDebugger, boolean skipPostInstallTasks, boolean useRootPushInstall, int maxDeltaInstallPatchSize) {
      this.useOptimisticSwap = useOptimisticSwap;
      this.useOptimisticResourceSwap = useOptimisticResourceSwap;
      this.optimisticInstallSupport = optimisticInstallSupport;
      this.allowAssumeVerified = allowAssumeVerified;
      this.useStructuralRedefinition = useStructuralRedefinition;
      this.useVariableReinitialization = useVariableReinitialization;
      this.fastRestartOnSwapFail = fastRestartOnSwapFail;
      this.enableCoroutineDebugger = enableCoroutineDebugger;
      this.skipPostInstallTasks = skipPostInstallTasks;
      this.useRootPushInstall = useRootPushInstall;
      this.maxDeltaInstallPatchSize = maxDeltaInstallPatchSize;
   }

   public static class Builder {
      private boolean useOptimisticSwap;
      private boolean useOptimisticResourceSwap;
      private EnumSet<ChangeType> optimisticInstallSupport = EnumSet.noneOf(ChangeType.class);
      private boolean allowAssumeVerified;
      private boolean useStructuralRedefinition;
      private boolean useVariableReinitialization;
      private boolean fastRestartOnSwapFail;
      private boolean enableCoroutineDebugger;
      private boolean skipPostInstallTasks;
      private boolean useRootPushInstall;
      private int maxDeltaInstallPatchSize = -1;

      public Builder setUseOptimisticSwap(boolean useOptimisticSwap) {
         this.useOptimisticSwap = useOptimisticSwap;
         return this;
      }

      public Builder setUseOptimisticResourceSwap(boolean useOptimisticResourceSwap) {
         this.useOptimisticResourceSwap = useOptimisticResourceSwap;
         return this;
      }

      public Builder setOptimisticInstallSupport(EnumSet<ChangeType> supportedChanges) {
         this.optimisticInstallSupport = supportedChanges;
         return this;
      }

      public Builder setAllowAssumeVerified(boolean allowAssumeVerified) {
         this.allowAssumeVerified = allowAssumeVerified;
         return this;
      }

      public Builder setUseStructuralRedefinition(boolean useStructuralRedefinition) {
         this.useStructuralRedefinition = useStructuralRedefinition;
         return this;
      }

      public Builder setUseVariableReinitialization(boolean useVariableReinitialization) {
         this.useVariableReinitialization = useVariableReinitialization;
         return this;
      }

      public Builder setFastRestartOnSwapFail(boolean fastRestartOnSwapFail) {
         this.fastRestartOnSwapFail = fastRestartOnSwapFail;
         return this;
      }

      public Builder enableCoroutineDebugger(boolean enableCoroutineDebugger) {
         this.enableCoroutineDebugger = enableCoroutineDebugger;
         return this;
      }

      public Builder skipPostInstallTasks(boolean skipPostInstallTasks) {
         this.skipPostInstallTasks = skipPostInstallTasks;
         return this;
      }

      public Builder useRootPushInstall(boolean useRootPushInstall) {
         this.useRootPushInstall = useRootPushInstall;
         return this;
      }

      public Builder setMaxDeltaInstallPatchSize(int maxDeltaInstallPatchSize) {
         this.maxDeltaInstallPatchSize = maxDeltaInstallPatchSize;
         return this;
      }

      public DeployerOption build() {
         return new DeployerOption(this.useOptimisticSwap, this.useOptimisticResourceSwap, this.optimisticInstallSupport, this.allowAssumeVerified, this.useStructuralRedefinition, this.useVariableReinitialization, this.fastRestartOnSwapFail, this.enableCoroutineDebugger, this.skipPostInstallTasks, this.useRootPushInstall, this.maxDeltaInstallPatchSize);
      }
   }
}
