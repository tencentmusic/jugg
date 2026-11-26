package com.android.tools.deployer;

/** Defines the device paths shared by the Quail deployer transport. */
public final class Deployer {
   public static final String BASE_DIRECTORY = Sites.deviceStudioFolder();
   public static final String INSTALLER_DIRECTORY = Sites.installerExecutableFolder();
   public static final String INSTALLER_TMP_DIRECTORY = Sites.installerTmpFolder();

   private Deployer() {
   }
}
