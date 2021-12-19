package com.sickworm.intellij.jugg.mock;

import com.android.ddmlib.AdbHelper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

// copied from com.android.ddmlib.internal.AdbSocketUtils

public class AdbSocketUtils {
    public AdbSocketUtils() {
    }

    public static String read(SocketChannel socket, byte[] buffer) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(buffer, 0, buffer.length);

        int count;
        do {
            if (buf.position() == buf.limit()) {
                return new String(buffer, 0, buf.position(), AdbHelper.DEFAULT_CHARSET);
            }

            count = socket.read(buf);
        } while(count >= 0);

        throw new IOException("EOF");
    }

    public static int readLength(SocketChannel socket, byte[] buffer) throws IOException {
        String msg = read(socket, buffer);
        if (msg != null) {
            try {
                return Integer.parseInt(msg, 16);
            } catch (NumberFormatException var4) {
            }
        }

        throw new IOException("Unable to read length");
    }
}
