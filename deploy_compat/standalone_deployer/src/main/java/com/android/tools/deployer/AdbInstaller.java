package com.android.tools.deployer;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.proto.Deploy.Event.Type;
import com.android.tools.deploy.proto.Deploy.InstallerResponse.Status;
import com.android.tools.deployer.common.AdbClient;
import com.android.tools.deployer.common.DeployMetric;
import com.android.tools.deployer.common.Installer;
import com.android.tools.deployer.common.Timeouts;
import com.android.tools.tracer.Trace;
import com.android.utils.ILogger;
import com.google.common.base.Charsets;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.TimeoutException;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class AdbInstaller extends Installer {
   public static final String INSTALLER_BINARY_NAME = Sites.installerBinary();
   public static final String INSTALLER_PATH = Sites.installerPath();
   public static final String ANDROID_EXECUTABLE_PATH = "/tools/base/deploy/installer/android-installer";
   private final AdbClient adb;
   private final String installersFolder;
   private final Collection<DeployMetric> metrics;
   private final Mode mode;

   public AdbInstaller(String installersFolder, AdbClient adb, Collection<DeployMetric> metrics, ILogger logger) {
      this(installersFolder, adb, metrics, logger, AdbInstaller.Mode.ONE_SHOT);
   }

   public AdbInstaller(String installersFolder, AdbClient adb, Collection<DeployMetric> metrics, ILogger logger, Mode mode) {
      super(logger);
      this.adb = adb;
      this.installersFolder = installersFolder;
      this.metrics = metrics;
      this.mode = mode;
   }

   private void logEvents(List<Deploy.Event> events) {
      for(Deploy.Event event : events) {
         if (event.getType() != Type.TRC_END) {
            ILogger var10000 = this.logger;
            long var10001 = event.getTimestampNs() / 1000000L;
            var10000.info(var10001 + "ms " + String.valueOf(event.getType()) + " [" + event.getPid() + "][" + event.getTid() + "] : " + event.getText(), new Object[0]);
         }
      }

   }

   private void traceEvents(Deploy.InstallerResponse response, long start, long end) {
      long maxNs = Long.MIN_VALUE;
      long minNs = Long.MAX_VALUE;

      for(Deploy.Event event : response.getEventsList()) {
         maxNs = Math.max(maxNs, event.getTimestampNs());
         minNs = Math.min(minNs, event.getTimestampNs());
      }

      long delta = (maxNs + minNs - (end + start)) / 2L;
      Stack<Deploy.Event> eventStack = new Stack();

      for(Deploy.Event event : response.getEventsList()) {
         switch (event.getType()) {
            case TRC_BEG:
            case TRC_METRIC:
               Trace.begin(event.getPid(), event.getTid(), event.getTimestampNs() - delta, event.getText());
               eventStack.push(event);
               break;
            case TRC_END:
               Trace.end(event.getPid(), event.getTid(), event.getTimestampNs() - delta);
               if (!eventStack.empty()) {
                  Deploy.Event begin = (Deploy.Event)eventStack.pop();
                  if (begin.getType() == Type.TRC_METRIC) {
                     long startMs = begin.getTimestampNs() - delta;
                     long endMs = event.getTimestampNs() - delta;
                     this.metrics.add(new DeployMetric(begin.getText(), startMs, endMs));
                  }
               }
         }
      }

   }

   protected Deploy.InstallerResponse sendInstallerRequest(Deploy.InstallerRequest request, long timeOutMs) throws IOException {
      try (Trace ignore = Trace.begin("./installer " + request.getCommandName())) {
         long start = System.nanoTime();
         Deploy.InstallerResponse response = this.sendInstallerRequest(request, AdbInstaller.OnFail.RETRY, timeOutMs);
         long end = System.nanoTime();
         this.logEvents(response.getEventsList());
         this.traceEvents(response, start, end);
         return response;
      }
   }

   private Deploy.InstallerResponse sendInstallerRequest(Deploy.InstallerRequest request, OnFail onFail, long timeOutMs) throws IOException {
      Deploy.InstallerResponse response = null;
      synchronized(AdbInstallerChannelManager.class) {
         AdbInstallerChannel channel = AdbInstallerChannelManager.getChannel(this.adb, this.getVersion(), this.logger, this.mode);

         try {
            if (channel.writeRequest(request, timeOutMs)) {
               response = channel.readResponse(timeOutMs);
            }
         } catch (TimeoutException var13) {
            String msg = String.format("Device '%s' timed out", this.adb.getName());
            throw new IOException(msg);
         }

         if (response == null) {
            if (onFail == AdbInstaller.OnFail.DO_NO_RETRY) {
               throw new IOException("Invalid installer response");
            } else {
               AdbInstallerChannelManager.reset(this.adb, this.logger);
               this.prepare();
               return this.sendInstallerRequest(request, AdbInstaller.OnFail.DO_NO_RETRY, timeOutMs);
            }
         } else if (response.getStatus() == Status.ERROR_WRONG_VERSION) {
            if (onFail == AdbInstaller.OnFail.DO_NO_RETRY) {
               throw new IOException("Unrecoverable installer WRONG_VERSION error. Aborting");
            } else {
               AdbInstallerChannelManager.reset(this.adb, this.logger);
               this.prepare();
               return this.sendInstallerRequest(request, AdbInstaller.OnFail.DO_NO_RETRY, timeOutMs);
            }
         } else {
            Deploy.InstallerResponse.Status status = response.getStatus();
            if (status != Status.OK) {
               int statusNumber = status.getNumber();
               String errorMsg = response.getErrorMessage();
               String msg = String.format(Locale.US, "Bad InstallerResponse msg='%s', status=%d", errorMsg, statusNumber);
               throw new IOException(msg);
            } else {
               if (this.mode == AdbInstaller.Mode.ONE_SHOT) {
                  AdbInstallerChannelManager.reset(this.adb, this.logger);
               }

               return response;
            }
         }
      }
   }

   private void prepare() throws IOException {
      File installerFile = null;
      List<String> abis = this.adb.getAbis();

      for(String abi : abis) {
         String installerJarPath = abi + "/" + INSTALLER_BINARY_NAME;
         InputStream inputStream = this.getResource(installerJarPath);

         label49: {
            try {
               if (inputStream != null) {
                  this.logger.info("Pushed installer '" + installerJarPath + "'", new Object[0]);
                  installerFile = File.createTempFile(".studio_installer", abi);
                  Files.copy(inputStream, Paths.get(installerFile.getAbsolutePath()), new CopyOption[]{StandardCopyOption.REPLACE_EXISTING});
                  break label49;
               }
            } catch (Throwable var11) {
               if (inputStream != null) {
                  try {
                     inputStream.close();
                  } catch (Throwable var9) {
                     var11.addSuppressed(var9);
                  }
               }

               throw var11;
            }

            if (inputStream != null) {
               inputStream.close();
            }
            continue;
         }

         if (inputStream != null) {
            inputStream.close();
         }
         break;
      }

      if (installerFile == null) {
         throw new IOException("Unsupported abis: " + Arrays.toString(abis.toArray()));
      } else {
         try {
            this.cleanAndPushInstaller(installerFile);
         } catch (IOException var10) {
            this.runShell(new String[]{"su", "root", "chown", "-R", "shell:shell", Deployer.BASE_DIRECTORY}, Timeouts.SHELL_CHOWN);
            this.cleanAndPushInstaller(installerFile);
         }

         installerFile.delete();
      }
   }

   private void cleanAndPushInstaller(File installerFile) throws IOException {
      this.runShell(new String[]{"rm", "-fr", Deployer.INSTALLER_DIRECTORY, Deployer.INSTALLER_TMP_DIRECTORY}, Timeouts.SHELL_RMFR);
      this.runShell(new String[]{"mkdir", "-p", Deployer.INSTALLER_DIRECTORY, Deployer.INSTALLER_TMP_DIRECTORY}, Timeouts.SHELL_MKDIR);
      this.adb.push(installerFile.getAbsolutePath(), INSTALLER_PATH);
      this.runShell(new String[]{"chmod", "+x", INSTALLER_PATH}, Timeouts.SHELL_CHMOD);
      this.runShell(new String[]{"chmod", "-R", "775", Deployer.BASE_DIRECTORY}, Timeouts.SHELL_CHMOD);
      this.runShell(new String[]{"chown", "-R", "shell:shell", Deployer.BASE_DIRECTORY}, Timeouts.SHELL_CHOWN);
   }

   private void runShell(String[] cmd, long timeOutMs) throws IOException {
      byte[] response = this.adb.shell(cmd, timeOutMs);
      if (response.length > 0) {
         String extraMsg = (new String(response, Charsets.UTF_8)).trim();
         String error = String.format("Cannot '%s' : '%s'", String.join(" ", cmd), extraMsg);
         this.logger.warning(error, new Object[0]);
         throw new IOException(error);
      }
   }

   private InputStream getResource(String path) throws FileNotFoundException {
      InputStream stream;
      if (this.installersFolder == null) {
         stream = Installer.class.getResourceAsStream("/tools/base/deploy/installer/android-installer/" + path);
      } else {
         stream = new FileInputStream(this.installersFolder + "/" + path);
      }

      return stream;
   }

   protected void onAsymetry(Deploy.InstallerRequest req, Deploy.InstallerResponse resp) {
      try {
         synchronized(AdbInstallerChannelManager.class) {
            AdbInstallerChannelManager.reset(this.adb, this.logger);
         }
      } catch (IOException var6) {
      }

   }

   private static enum OnFail {
      RETRY,
      DO_NO_RETRY;
   }

   public static enum Mode {
      DAEMON,
      ONE_SHOT;
   }
}
