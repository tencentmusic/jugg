package com.android.tools.deployer.common;

import com.android.ddmlib.IDevice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public final class InstallOptions {
   public static final InstallOptions STUDIO_DEFAULTS = builder().setAllowDebuggable().build();
   public static final InstallOptions MOBILE_INSTALL_DEFAULTS = builder().setAllowDebuggable().setAllowDowngrade().setGrantAllPermissions().build();
   public static final String CURRENT_USER = "current";
   private final List<String> allFlags;
   private final List<String> userFlags;
   private final boolean shouldUseAssumeVerified;
   private final Canceller canceller;

   private InstallOptions(List<String> allFlags, List<String> userFlags, boolean shouldUseAssumeVerified, Canceller canceller) {
      this.allFlags = allFlags;
      this.userFlags = userFlags;
      this.shouldUseAssumeVerified = shouldUseAssumeVerified;
      this.canceller = canceller;
   }

   public List<String> getFlags() {
      return this.allFlags;
   }

   public List<String> getUserFlags() {
      return this.userFlags;
   }

   public Canceller getCancelChecker() {
      return this.canceller;
   }

   public boolean getShouldUseAssumeVerified() {
      return this.shouldUseAssumeVerified;
   }

   public Builder toBuilder() {
      Builder builder = new Builder();
      builder.flags.addAll(this.allFlags);
      builder.userFlags.addAll(this.userFlags);
      return builder;
   }

   public static Builder builder() {
      return new Builder();
   }

   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else if (o != null && this.getClass() == o.getClass()) {
         InstallOptions that = (InstallOptions)o;
         return this.allFlags.equals(that.allFlags);
      } else {
         return false;
      }
   }

   public int hashCode() {
      return this.allFlags.hashCode();
   }

   public static final class Builder {
      private final List<String> flags;
      private final List<String> userFlags;
      private boolean shouldUseAssumeVerified = false;
      private Canceller canceller;

      private Builder() {
         this.canceller = Canceller.NO_OP;
         this.flags = new ArrayList();
         this.userFlags = new ArrayList();
      }

      public Builder setAllowDebuggable() {
         this.flags.add("-t");
         return this;
      }

      public Builder setAllowDowngrade() {
         this.flags.add("-d");
         return this;
      }

      public Builder setGrantAllPermissions() {
         this.flags.add("-g");
         return this;
      }

      public Builder setForceQueryable() {
         this.flags.add("--force-queryable");
         return this;
      }

      public Builder setInstallFullApk() {
         this.flags.add("--full");
         return this;
      }

      public Builder setInstallOnUser(String targetUserId) {
         this.flags.add("--user");
         this.flags.add(targetUserId);
         return this;
      }

      public Builder setDontKill() {
         this.flags.add("--dont-kill");
         return this;
      }

      public Builder setSkipVerification(IDevice device, String packageName) {
         String skipVerificationString = ApkVerifierTracker.getSkipVerificationInstallationFlag(device, packageName);
         if (skipVerificationString != null) {
            this.flags.add(skipVerificationString);
         }

         return this;
      }

      public Builder setUserInstallOptions(String[] userSpecifiedFlags) {
         if (userSpecifiedFlags == null) {
            return this;
         } else {
            this.flags.addAll(Arrays.asList(userSpecifiedFlags));
            this.userFlags.addAll(Arrays.asList(userSpecifiedFlags));
            return this;
         }
      }

      public Builder setUserInstallOptions(String userSpecifiedFlag) {
         if (userSpecifiedFlag == null) {
            return this;
         } else {
            this.flags.add(userSpecifiedFlag);
            this.userFlags.add(userSpecifiedFlag);
            return this;
         }
      }

      public Builder setCancelChecker(Canceller canceller) {
         this.canceller = canceller;
         return this;
      }

      public Builder setShouldUseAssumeVerified(boolean shouldUseAssumeVerified) {
         this.shouldUseAssumeVerified = shouldUseAssumeVerified;
         return this;
      }

      public InstallOptions build() {
         return new InstallOptions(this.flags, this.userFlags, this.shouldUseAssumeVerified, this.canceller);
      }
   }
}
