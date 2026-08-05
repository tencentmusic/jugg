package com.android.tools.deployer;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deployer.common.DeployerException;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public interface ClassRedefiner {
   Deploy.SwapResponse redefine(Deploy.SwapRequest request) throws DeployerException;

   Deploy.SwapResponse redefine(Deploy.OverlaySwapRequest request) throws DeployerException;

   RedefineClassSupportState canRedefineClass() throws DeployerException;

   public static class RedefineClassSupportState {
      public final RedefineClassSupport support;
      public final String targetThread;

      public RedefineClassSupportState(RedefineClassSupport support, String targetThread) {
         this.support = support;
         this.targetThread = targetThread;
      }
   }

   public static enum RedefineClassSupport {
      FULL,
      MAIN_THREAD_RUNNING,
      NEEDS_AGENT_SERVER,
      NONE;
   }
}
