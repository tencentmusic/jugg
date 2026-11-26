package com.android.tools.deployer;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deployer.common.DeployerException;
import com.android.tools.deployer.common.Installer;
import java.io.IOException;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class InstallerBasedClassRedefiner implements ClassRedefiner {
   private final Installer installer;

   public InstallerBasedClassRedefiner(Installer installer) {
      this.installer = installer;
   }

   public Deploy.SwapResponse redefine(Deploy.SwapRequest request) throws DeployerException {
      try {
         return this.installer.swap(request);
      } catch (IOException e) {
         throw DeployerException.installerIoException(e);
      }
   }

   public Deploy.SwapResponse redefine(Deploy.OverlaySwapRequest request) throws DeployerException {
      try {
         return this.installer.overlaySwap(request);
      } catch (IOException e) {
         throw DeployerException.installerIoException(e);
      }
   }

   public ClassRedefiner.RedefineClassSupportState canRedefineClass() {
      return new ClassRedefiner.RedefineClassSupportState(ClassRedefiner.RedefineClassSupport.FULL, (String)null);
   }
}
