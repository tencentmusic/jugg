package com.android.tools.deployer.model;

/** Stores the target ABI used to select package-manager APKs. */
public final class AppState {
   private final String abi;

   public AppState(String abi) {
      this.abi = abi;
   }

   public String getAbi() {
      return this.abi;
   }
}
