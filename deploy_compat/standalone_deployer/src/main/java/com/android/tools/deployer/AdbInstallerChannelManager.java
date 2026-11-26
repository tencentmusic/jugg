package com.android.tools.deployer;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.SimpleConnectedSocket;
import com.android.ddmlib.TimeoutException;
import com.android.tools.deployer.common.AdbClient;
import com.android.utils.ILogger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class AdbInstallerChannelManager {
   private static final HashMap<String, AdbInstallerChannel> channels = new HashMap();

   private AdbInstallerChannelManager() {
   }

   public static AdbInstallerChannel getChannel(AdbClient client, String version, ILogger logger, AdbInstaller.Mode mode) throws IOException {
      String deviceId = client.getSerial();
      if (channels.containsKey(deviceId)) {
         AdbInstallerChannel channel = (AdbInstallerChannel)channels.get(deviceId);
         if (channel.isClosed()) {
            channels.remove(deviceId);
         }
      }

      if (!channels.containsKey(deviceId)) {
         logger.info("Created SocketChannel to '" + deviceId + "'", new Object[0]);
         AdbInstallerChannel channel = createChannel(client, version, logger, mode);
         channels.put(deviceId, channel);
      }

      return (AdbInstallerChannel)channels.get(deviceId);
   }

   private static AdbInstallerChannel createChannel(AdbClient client, String version, ILogger logger, AdbInstaller.Mode mode) throws IOException {
      SimpleConnectedSocket channel = null;
      List<String> parameters = new ArrayList();
      parameters.add("-version=" + version);
      if (mode == AdbInstaller.Mode.DAEMON) {
         parameters.add("-daemon");
      }

      try {
         channel = client.rawExec(AdbInstaller.INSTALLER_PATH, (String[])parameters.toArray(new String[0]));
      } catch (TimeoutException | AdbCommandRejectedException e) {
         if (channel != null) {
            channel.close();
         }

         throw new IOException(e);
      }

      return new AdbInstallerChannel(channel, logger);
   }

   public static void reset(AdbClient client, ILogger logger) throws IOException {
      String serial = client.getSerial();
      logger.info("Reset SocketChannel to '" + serial + "'", new Object[0]);

      try (AdbInstallerChannel c = (AdbInstallerChannel)channels.get(serial)) {
         channels.remove(serial);
      }

   }
}
