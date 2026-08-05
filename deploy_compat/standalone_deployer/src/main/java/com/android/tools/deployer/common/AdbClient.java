package com.android.tools.deployer.common;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.Client;
import com.android.ddmlib.ClientData;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.InstallMetrics;
import com.android.ddmlib.InstallReceiver;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.SimpleConnectedSocket;
import com.android.ddmlib.SyncException;
import com.android.ddmlib.TimeoutException;
import com.android.ddmlib.IDevice.Feature;
import com.android.sdklib.AndroidVersion;
import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.proto.Deploy.Arch;
import com.android.tools.deployer.model.Apk;
import com.android.tools.deployer.model.DeploymentPlan;
import com.android.tools.tracer.Trace;
import com.android.utils.ILogger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Provides the Quail deployer ADB operations through standalone ddmlib. */
public class AdbClient {
   private static final Map<String, Deploy.Arch> ABI_MAP;
   private final IDevice device;
   private final ILogger logger;

   public AdbClient(IDevice device, ILogger logger) {
      this.device = device;
      this.logger = logger;
   }

   public SimpleConnectedSocket rawExec(String executable, String[] parameters) throws AdbCommandRejectedException, IOException, TimeoutException {
      return this.device.rawExec2(executable, parameters);
   }

   public byte[] shell(String[] parameters, long timeOutmS) throws IOException {
      return this.shell(parameters, (InputStream)null, timeOutmS);
   }

   public byte[] shell(String[] parameters, InputStream input, long timeOutmS) throws IOException {
      return this.shell(parameters, input, timeOutmS, TimeUnit.MILLISECONDS);
   }

   public byte[] shell(String[] parameters, InputStream input, long maxTimeOutMs, TimeUnit timeUnit) throws IOException {
      try (Trace ignored = Trace.begin("adb shell" + Arrays.toString(parameters))) {
         ByteArrayOutputReceiver receiver = new ByteArrayOutputReceiver();
         this.device.executeShellCommand(String.join(" ", parameters), receiver, maxTimeOutMs, timeUnit, input);
         return receiver.toByteArray();
      } catch (ShellCommandUnresponsiveException | TimeoutException | AdbCommandRejectedException e) {
         throw new IOException(e);
      }
   }

   public byte[] binder(String[] parameters, InputStream input) throws IOException {
      this.logger.info("BINDER: " + String.join(" ", parameters), new Object[0]);

      try (Trace ignored = Trace.begin("binder" + Arrays.toString(parameters))) {
         ByteArrayOutputReceiver receiver = new ByteArrayOutputReceiver();
         this.device.executeBinderCommand(parameters, receiver, 5L, TimeUnit.MINUTES, input);
         return receiver.toByteArray();
      } catch (ShellCommandUnresponsiveException | TimeoutException | AdbCommandRejectedException e) {
         throw new IOException(e);
      }
   }

   public InstallResult install(DeploymentPlan plan, List<String> options, boolean reinstall) {
      List<Path> paths = new ArrayList();
      paths.addAll((Collection)plan.getApksForPackageManager().stream().map((apk) -> Paths.get(apk.path)).collect(Collectors.toList()));
      List<Path> bps = plan.getApp().getBaselineProfile(this.device.getVersion().getApiLevel());
      paths.addAll(bps);
      this.logger.info("Installing:", new Object[0]);

      for(Path p : paths) {
         this.logger.info("    " + String.valueOf(p.getFileName()), new Object[0]);
      }

      this.logger.info("Installing with ddmlib", new Object[0]);
      InstallResult ir = this.installWithDdmLib(paths, options, reinstall);

      if (!bps.isEmpty() && this.baselineInstallationStatusSupported()) {
         String[] cmd = new String[]{"pm", "art", "dump", plan.getApp().getAppId()};
         byte[] rawResult = new byte[0];

         try {
            rawResult = this.shell(cmd, Timeouts.SHELL_BASELINE_PROFILE_STATUS);
         } catch (IOException e) {
            this.logger.warning("Unable to retrieve baseline profile status: %s", new Object[]{e.getMessage()});
         }

         String result = new String(rawResult);
         if (!result.contains("status=speed-profile")) {
            return new InstallResult(InstallStatus.INSTALL_BASELINE_PROFILE_FAILED, "Baseline profile did not install: " + result);
         } else {
            this.logger.info(result, new Object[0]);
            return ir;
         }
      } else {
         return ir;
      }
   }

   private boolean baselineInstallationStatusSupported() {
      return this.device.getVersion().getApiLevel() > 33;
   }

   private InstallResult makeInstallResult(String code, String message, Throwable t) {
      if (code != null) {
         try {
            return toInstallerResult(code, message);
         } catch (NullPointerException | IllegalArgumentException var5) {
            this.logger.warning("Unrecognized Installation Failure: %s\n%s\n", new Object[]{code, message});
            return new InstallResult(InstallStatus.UNKNOWN_ERROR, "Unknown Error");
         }
      } else if (t instanceof ShellCommandUnresponsiveException) {
         return new InstallResult(InstallStatus.SHELL_UNRESPONSIVE, message);
      } else {
         this.logger.warning("Installation Failure: %s\n", new Object[]{message});
         return new InstallResult(InstallStatus.UNKNOWN_ERROR, message, (InstallMetrics)null);
      }
   }

   public static InstallResult toInstallerResult(InstallReceiver r) {
      return toInstallerResult(r.getErrorCode(), r.getErrorMessage());
   }

   public static InstallResult toInstallerResult(String errorCode, String reason) {
      try {
         return new InstallResult(InstallStatus.valueOf(errorCode), reason);
      } catch (IllegalArgumentException var5) {
         try {
            int numericValue = Integer.parseInt(errorCode);
            return new InstallResult(InstallStatus.numericErrorCodeToStatus(numericValue), reason);
         } catch (NumberFormatException var4) {
            return new InstallResult(InstallStatus.UNKNOWN_ERROR, reason);
         }
      }
   }

   private InstallResult installWithDdmLib(List<Path> paths, List<String> options, boolean reinstall) {
      List<File> files = (List)paths.stream().map(Path::toFile).collect(Collectors.toList());

      try {
         if (this.device.getVersion().isAtLeast(21)) {
            this.device.installPackages(files, reinstall, options, 5L, TimeUnit.MINUTES);
            return new InstallResult(InstallStatus.OK, (String)null, this.device.getLastInstallMetrics());
         } else if (files.size() != 1) {
            return new InstallResult(InstallStatus.MULTI_APKS_NO_SUPPORTED_BELOW21, "Splits are not supported below API 21");
         } else {
            this.device.installPackage(((File)files.get(0)).getAbsolutePath(), reinstall, (String[])options.toArray(new String[0]));
            return new InstallResult(InstallStatus.OK, (String)null, this.device.getLastInstallMetrics());
         }
      } catch (com.android.ddmlib.InstallException e) {
         String code = e.getErrorCode();
         String message = e.getMessage();
         return this.makeInstallResult(code, message, e);
      }
   }

   public boolean uninstall(String packageName) {
      try {
         this.device.uninstallPackage(packageName);
         return true;
      } catch (com.android.ddmlib.InstallException var3) {
         return false;
      }
   }

   public List<String> getAbis() {
      return this.device.getAbis();
   }

   public List<Integer> getPids(String packageName) {
      if (!this.device.supportsFeature(Feature.REAL_PKG_NAME)) {
         throw new IllegalStateException(String.format("Device %s, do not support REAL_PKG_NAME", this.device.getSerialNumber()));
      } else {
         List<Integer> results = new ArrayList();

         for(Client client : this.device.getClients()) {
            if (packageName.equals(client.getClientData().getPackageName())) {
               results.add(client.getClientData().getPid());
            }
         }

         return results;
      }
   }

   public Deploy.Arch getArch(List<Integer> pids) {
      Deploy.Arch result = Arch.ARCH_UNKNOWN;

      for(int pid : pids) {
         Deploy.Arch curProc = this.getArch(pid);
         if (result == Arch.ARCH_UNKNOWN) {
            result = curProc;
         } else if (curProc != Arch.ARCH_UNKNOWN && result != curProc) {
            this.logger.warning("Mixed ABIs detected: %s and %s", new Object[]{result, curProc});
         }
      }

      return result;
   }

   public String getAbiForApks(List<Apk> apks) throws DeployerException {
      HashSet<String> appSupported = new HashSet();

      for(Apk apk : apks) {
         appSupported.addAll(apk.libraryAbis);
      }

      List<String> deviceSupported = this.getAbis();
      if (deviceSupported.isEmpty()) {
         throw DeployerException.unsupportedArch();
      } else if (appSupported.isEmpty()) {
         String abi = (String)deviceSupported.get(0);
         return abi;
      } else {
         for(String abi : deviceSupported) {
            if (appSupported.contains(abi)) {
               return abi;
            }
         }

         throw DeployerException.unsupportedArch();
      }
   }

   public static Deploy.Arch getArchForAbi(String abi) {
      return (Deploy.Arch)ABI_MAP.get(abi);
   }

   private Deploy.Arch getArch(int pid) {
      for(Client client : this.device.getClients()) {
         if (client.getClientData().getPid() == pid) {
            return getArchFromDdmClient(client.getClientData());
         }
      }

      return Arch.ARCH_UNKNOWN;
   }

   @VisibleForTesting
   static Deploy.Arch getArchFromDdmClient(ClientData clientData) {
      String abi = clientData.getAbi();
      if (abi == null) {
         return Arch.ARCH_UNKNOWN;
      } else if (abi.startsWith("32-bit")) {
         return Arch.ARCH_32_BIT;
      } else if (abi.startsWith("64-bit")) {
         return Arch.ARCH_64_BIT;
      } else {
         Deploy.Arch fromMapping = getArchForAbi(abi);
         return fromMapping == null ? Arch.ARCH_UNKNOWN : fromMapping;
      }
   }

   public void push(String from, String to) throws IOException {
      try {
         try (Trace ignored = Trace.begin("adb push")) {
            this.device.pushFile(from, to);
         }

      } catch (TimeoutException | AdbCommandRejectedException | SyncException e) {
         throw new IOException(e);
      }
   }

   public AndroidVersion getVersion() {
      return this.device.getVersion();
   }

   public String getName() {
      return this.device.getName();
   }

   public String getSerial() {
      return this.device.getSerialNumber();
   }

   public String abortSession(String sessionId) {
      String prefix = this.device.getVersion().isAtLeast(24) ? "cmd package" : "pm";
      String[] command = new String[]{prefix, "install-abandon", sessionId};

      String response;
      try {
         byte[] bytes = this.shell(command, Timeouts.SHELL_ABORT_INSTALL_MS);
         response = new String(bytes, StandardCharsets.UTF_8);
      } catch (Exception e) {
         response = e.getMessage();
      }

      return response;
   }

   public String getSkipVerificationOption(String packageName) {
      return ApkVerifierTracker.getSkipVerificationInstallationFlag(this.device, packageName);
   }

   public IDevice getDevice() {
      return this.device;
   }

   static {
      ABI_MAP = ImmutableMap.of("arm64-v8a", Arch.ARCH_64_BIT, "armeabi-v7a", Arch.ARCH_32_BIT, "x86_64", Arch.ARCH_64_BIT, "x86", Arch.ARCH_32_BIT);
   }

   public static class InstallResult {
      public final InstallStatus status;
      public final String reason;
      public final InstallMetrics metrics;

      public InstallResult(InstallStatus status, String reason) {
         this.status = status;
         this.reason = reason;
         this.metrics = null;
      }

      public InstallResult(InstallStatus status, String reason, InstallMetrics metrics) {
         this.status = status;
         this.reason = reason;
         this.metrics = metrics;
      }
   }

   private class ByteArrayOutputReceiver implements IShellOutputReceiver {
      ByteArrayOutputStream stream = new ByteArrayOutputStream();

      public void addOutput(byte[] data, int offset, int length) {
         this.stream.write(data, offset, length);
      }

      public void flush() {
      }

      public boolean isCancelled() {
         return false;
      }

      byte[] toByteArray() {
         return this.stream.toByteArray();
      }
   }
}
