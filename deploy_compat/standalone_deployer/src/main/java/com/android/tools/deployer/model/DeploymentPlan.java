package com.android.tools.deployer.model;

import java.util.List;

/** Combines one application model with its target device ABI. */
public final class DeploymentPlan {
   private final App app;
   private final AppState appState;

   public DeploymentPlan(App app, AppState appState) {
      this.app = app;
      this.appState = appState;
   }

   public App getApp() {
      return this.app;
   }

   public AppState getAppState() {
      return this.appState;
   }

   public List<Apk> getApksForPackageManager() {
      return this.app.getApksForPackageManager(this.appState.getAbi());
   }
}
