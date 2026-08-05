package com.android.tools.deployer;

import com.android.ddmlib.SimpleConnectedSocket;
import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.proto.Deploy.InstallerResponse;
import com.android.tools.idea.protobuf.CodedInputStream;
import com.android.tools.idea.protobuf.CodedOutputStream;
import com.android.utils.ILogger;
import com.google.common.base.Charsets;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class AdbInstallerChannel implements AutoCloseable {
   private static final byte[] MAGIC_NUMBER = new byte[]{-84, -91, -84, -91, -84, -91, -84, -91};
   private final SimpleConnectedSocket channel;
   private final ILogger logger;
   private static final long PER_WRITE_TIME_OUT;

   AdbInstallerChannel(SimpleConnectedSocket c, ILogger logger) {
      this.channel = c;
      this.logger = logger;
   }

   private void read(ByteBuffer buffer, long timeOutMs) throws IOException {
      long deadline = System.currentTimeMillis() + timeOutMs;

      while(true) {
         if (buffer.remaining() != 0) {
            long timeout = Math.max(0L, deadline - System.currentTimeMillis());
            int read = this.channel.read(buffer, timeout);
            if (read == 0 || System.currentTimeMillis() >= deadline) {
               this.close();
               String template = "InstallerChannel.select: Timeout on read after %dms";
               String msg = String.format(Locale.US, template, timeOutMs);
               throw new IOException(msg);
            }

            if (read != -1) {
               continue;
            }
         }

         buffer.rewind();
         return;
      }
   }

   private void write(ByteBuffer buffer, long timeOutMs) throws IOException, TimeoutException {
      long deadline = System.currentTimeMillis() + timeOutMs;

      while(buffer.remaining() != 0) {
         if (System.currentTimeMillis() >= deadline) {
            throw new TimeoutException("InstallerChannel write timeout");
         }

         long timeout = Math.min(PER_WRITE_TIME_OUT, deadline - System.currentTimeMillis());
         timeout = Math.max(0L, timeout);
         int written = this.channel.write(buffer, timeout);
         if (written == 0) {
            throw new TimeoutException("InstallerChannel write timeout");
         }
      }

   }

   public void close() {
      try {
         this.channel.close();
      } catch (IOException var2) {
      }

   }

   boolean writeRequest(Deploy.InstallerRequest request, long timeOutMs) throws TimeoutException {
      ByteBuffer bytes = this.wrap(request);

      try {
         this.write(bytes, timeOutMs);
      } catch (IOException var6) {
         this.logger.warning("Error while writing InstallerChannel", new Object[0]);
         this.close();
         return false;
      }

      return bytes.remaining() == 0;
   }

   Deploy.InstallerResponse readResponse(long timeOutMs) {
      try {
         ByteBuffer bufferMarker = ByteBuffer.allocate(MAGIC_NUMBER.length);
         this.read(bufferMarker, timeOutMs);
         if (!Arrays.equals(MAGIC_NUMBER, bufferMarker.array())) {
            String garbage = new String(bufferMarker.array(), Charsets.UTF_8);
            String installerLoc = Sites.installerPath();
            String linkerWarning = "WARNING: linker: " + installerLoc + ": unsupported flags DT_FLAGS_1=0x8000001\n";
            if (!linkerWarning.startsWith(garbage)) {
               this.logger.info("Expecting MAGIC_NUMBER but read '" + garbage + "' from socket", new Object[0]);
               return null;
            }

            bufferMarker = ByteBuffer.allocate(linkerWarning.length() - MAGIC_NUMBER.length);
            this.read(bufferMarker, timeOutMs);
            String result = new String(bufferMarker.array(), Charsets.UTF_8);
            this.logger.info("Read warning'" + result + "' from socket", new Object[0]);
            bufferMarker = ByteBuffer.allocate(MAGIC_NUMBER.length);
            this.read(bufferMarker, timeOutMs);
            if (!Arrays.equals(MAGIC_NUMBER, bufferMarker.array())) {
               this.logger.info("Expecting MAGIC_NUMBER but read '" + garbage + "' from socket", new Object[0]);
               return null;
            }
         }

         ByteBuffer bufferSize = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
         this.read(bufferSize, timeOutMs);
         int responseSize = bufferSize.getInt();
         if (responseSize < 0) {
            return null;
         } else {
            ByteBuffer bufferPayload = ByteBuffer.allocate(responseSize);
            this.read(bufferPayload, timeOutMs);
            return this.unwrap(bufferPayload);
         }
      } catch (IOException var8) {
         this.logger.warning("Error while reading InstallerChannel", new Object[0]);
         this.close();
         return null;
      }
   }

   private Deploy.InstallerResponse unwrap(ByteBuffer buffer) {
      buffer.rewind();

      try {
         CodedInputStream cis = CodedInputStream.newInstance(buffer);
         return (Deploy.InstallerResponse)InstallerResponse.parser().parseFrom(cis);
      } catch (IOException e) {
         throw new IllegalStateException(e);
      }
   }

   private ByteBuffer wrap(Deploy.InstallerRequest message) {
      int messageSize = message.getSerializedSize();
      int headerSize = MAGIC_NUMBER.length + 4;
      byte[] buffer = new byte[headerSize + messageSize];
      ByteBuffer headerWriter = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
      headerWriter.put(MAGIC_NUMBER);
      headerWriter.putInt(messageSize);

      try {
         CodedOutputStream cos = CodedOutputStream.newInstance(buffer, headerSize, messageSize);
         message.writeTo(cos);
      } catch (IOException e) {
         throw new IllegalStateException(e);
      }

      return ByteBuffer.wrap(buffer);
   }

   boolean isClosed() {
      return !this.channel.isOpen();
   }

   static {
      PER_WRITE_TIME_OUT = TimeUnit.SECONDS.toMillis(5L);
   }
}
