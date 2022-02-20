//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

// copied from com.android.ddmlib.internal.DeviceClientMonitorTask

package com.sickworm.intellij.jugg.mock;

import com.android.ddmlib.*;
import com.android.ddmlib.AdbHelper.AdbResponse;
import com.android.ddmlib.ClientData.DebuggerStatus;
import com.android.ddmlib.internal.ClientImpl;
import com.android.ddmlib.internal.DeviceImpl;
import com.android.ddmlib.internal.MonitorThread;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class DeviceClientMonitorTask {
    private static final String ADB_TRACK_JDWP_COMMAND = "track-jdwp";
    private volatile boolean mQuit;
    private final Selector mSelector = Selector.open();
    private final ConcurrentHashMap<SocketChannel, DeviceImpl> mChannelsToRegister = new ConcurrentHashMap();
    private final Set<ClientImpl> mClientsToReopen = new HashSet();

    public DeviceClientMonitorTask() throws IOException {
    }

    public SocketChannel register(DeviceImpl device) {
        SocketChannel socketChannel;
        try {
            socketChannel = AndroidDebugBridge.openConnection();
        } catch (IOException var11) {
            Log.e("DeviceClientMonitorTask", "Unable to open connection to ADB server: " + var11);
            return null;
        }

        if (socketChannel != null) {
            try {
                boolean result = sendDeviceMonitoringRequest(socketChannel, device);
                if (result) {
//                    device.setClientMonitoringSocket(socketChannel);
                    socketChannel.configureBlocking(false);
                    this.mChannelsToRegister.put(socketChannel, device);
                    this.mSelector.wakeup();
                    return socketChannel;
                }
            } catch (TimeoutException var8) {
                try {
                    socketChannel.close();
                } catch (IOException var7) {
                }

                Log.d("DeviceClientMonitorTask", "Connection Failure when starting to monitor device '" + device + "' : timeout");
            } catch (AdbCommandRejectedException var9) {
                try {
                    socketChannel.close();
                } catch (IOException var6) {
                }

                Log.d("DeviceClientMonitorTask", "Adb refused to start monitoring device '" + device + "' : " + var9.getMessage());
            } catch (IOException var10) {
                try {
                    socketChannel.close();
                } catch (IOException var5) {
                }

                Log.d("DeviceClientMonitorTask", "Connection Failure when starting to monitor device '" + device + "' : " + var10.getMessage());
            }
        }

        return null;
    }

    void registerClientToDropAndReopen(ClientImpl client) {
        synchronized(this.mClientsToReopen) {
            Log.d("DeviceClientMonitorTask", "Adding " + client + " to list of client to reopen (" + client.getDebuggerListenPort() + ").");
            this.mClientsToReopen.add(client);
        }

        this.mSelector.wakeup();
    }

    void free(ClientImpl client) {
    }

    private void processDropAndReopenClients() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        synchronized(this.mClientsToReopen) {
            MonitorThread monitorThread = MonitorThread.getInstance();
            Iterator var3 = this.mClientsToReopen.iterator();

            while(var3.hasNext()) {
                ClientImpl client = (ClientImpl)var3.next();
                DeviceImpl device = (DeviceImpl)client.getDevice();
                int pid = client.getClientData().getPid();
                monitorThread.dropClient(client, false);
                Uninterruptibles.sleepUninterruptibly(1L, TimeUnit.SECONDS);
                Log.d("DeviceClientMonitorTask", "Reopening " + client);
                openClient(device, pid, monitorThread);
//                device.update(2);
            }

            this.mClientsToReopen.clear();
        }
    }

    void processChannelsToRegister() {
        this.mChannelsToRegister.entrySet().removeIf((entry) -> {
            try {
                ((SocketChannel)entry.getKey()).register(this.mSelector, 1, entry.getValue());
            } catch (ClosedChannelException var3) {
                Log.e("DeviceClientMonitorTask", "Connection error while monitoring clients.");
            }

            return true;
        });
    }

    public boolean run(SocketChannel socket, DeviceImpl device)
            throws InvocationTargetException, NoSuchMethodException, IllegalAccessException, IOException {
        byte[] lengthBuffer = new byte[4];

//        do {
//            try {
//                int count = this.mSelector.select();
//                if (this.mQuit) {
//                    return;
//                }

//                this.processChannelsToRegister();
//                this.processDropAndReopenClients();
//                if (count != 0) {
//                    Set<SelectionKey> keys = this.mSelector.selectedKeys();
//                    Iterator iter = keys.iterator();
//
//                    while(iter.hasNext()) {
//                        SelectionKey key = (SelectionKey)iter.next();
//                        iter.remove();
//                        if (key.isValid() && key.isReadable()) {
//                            Object attachment = key.attachment();
//                            if (attachment instanceof DeviceImpl) {
//                                DeviceImpl device = (DeviceImpl)attachment;
//                                if (socket != null) {
//                                    try {
                                        int length = AdbSocketUtils.readLength(socket, lengthBuffer);
                                        return this.processIncomingJdwpData(device, socket, length);
//                                    } catch (IOException var10) {
//                                        Log.d("DeviceClientMonitorTask", "Error reading jdwp list: " + var10.getMessage());
//                                        socket.close();
//                                        this.mChannelsToRegister.remove(socket);
//                                        device.getClientTracker().trackDeviceToDropAndReopen(device);
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//            } catch (IOException var11) {
//                Log.e("DeviceClientMonitorTask", "Connection error while monitoring clients.");
//            }
//        } while(!this.mQuit);

    }

    public void stop() {
        this.mQuit = true;
        this.mSelector.wakeup();
    }

    private static boolean sendDeviceMonitoringRequest(SocketChannel socket, DeviceImpl device) throws TimeoutException, AdbCommandRejectedException, IOException {
        try {
            AdbHelper.setDevice(socket, device);
            AdbHelper.write(socket, AdbHelper.formAdbRequest("track-jdwp"));
            AdbResponse resp = AdbHelper.readAdbResponse(socket, false);
            if (!resp.okay) {
                Log.e("DeviceClientMonitorTask", "adb refused request: " + resp.message);
            }

            return resp.okay;
        } catch (TimeoutException var3) {
            Log.e("DeviceClientMonitorTask", "Sending jdwp tracking request timed out!");
            throw var3;
        } catch (IOException var4) {
            Log.e("DeviceClientMonitorTask", "Sending jdwp tracking request failed!");
            throw var4;
        }
    }

    private boolean processIncomingJdwpData(DeviceImpl device, SocketChannel monitorSocket, int length)
            throws IOException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        boolean isSuccess = false;
        if (length >= 0) {
            Set<Integer> newPids = new HashSet();
            if (length > 0) {
                byte[] buffer = new byte[length];
                String result = AdbSocketUtils.read(monitorSocket, buffer);
                String[] pids = result.split("\n");
                String[] var8 = pids;
                int var9 = pids.length;

                for(int var10 = 0; var10 < var9; ++var10) {
                    String pid = var8[var10];

                    try {
                        newPids.add(Integer.valueOf(pid));
                    } catch (NumberFormatException var14) {
                    }
                }
            }

            MonitorThread monitorThread = MonitorThread.getInstance();
            List<ClientImpl> clients = Arrays.asList(device.getClients());
//            List<ClientImpl> clients = device.getClientList();
            Map<Integer, ClientImpl> existingClients = new HashMap();
            Iterator var20;
            synchronized(clients) {
                var20 = clients.iterator();

                while(true) {
                    if (!var20.hasNext()) {
                        break;
                    }

                    ClientImpl c = (ClientImpl)var20.next();
                    existingClients.put(c.getClientData().getPid(), c);
                }
            }

            Set<ClientImpl> clientsToRemove = new HashSet();
            var20 = existingClients.keySet().iterator();

            while(var20.hasNext()) {
                Integer pid = (Integer)var20.next();
                if (!newPids.contains(pid)) {
                    clientsToRemove.add((ClientImpl)existingClients.get(pid));
                }
            }
//
            Set<Integer> pidsToAdd = new HashSet<>(newPids);
            pidsToAdd.removeAll(existingClients.keySet());
            monitorThread.dropClients(clientsToRemove, false);
            Iterator var24 = pidsToAdd.iterator();

            while(var24.hasNext()) {
                int newPid = (Integer)var24.next();
                isSuccess |= openClient(device, newPid, monitorThread);
            }

            if (!pidsToAdd.isEmpty() || !clientsToRemove.isEmpty()) {
                AndroidDebugBridge.deviceChanged(device, 2);
            }
        }

        return isSuccess;
    }

    private static boolean openClient(DeviceImpl device, int pid, MonitorThread monitorThread)
            throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        SocketChannel clientSocket;
        try {
            clientSocket = AdbHelper.createPassThroughConnection(AndroidDebugBridge.getSocketAddress(), device.getSerialNumber(), pid);
            clientSocket.configureBlocking(false);
        } catch (UnknownHostException var5) {
            Log.d("DeviceClientMonitorTask", "Unknown Jdwp pid: " + pid);
            return false;
        } catch (TimeoutException var6) {
            Log.w("DeviceClientMonitorTask", "Failed to connect to client '" + pid + "': timeout");
            return false;
        } catch (AdbCommandRejectedException var7) {
            Log.d("DeviceClientMonitorTask", "Adb rejected connection to client '" + pid + "': " + var7.getMessage());
            return false;
        } catch (IOException var8) {
            Log.w("DeviceClientMonitorTask", "Failed to connect to client '" + pid + "': " + var8.getMessage());
            return false;
        }

        return createClient(device, pid, clientSocket, monitorThread);
    }

    private static boolean createClient(DeviceImpl device, int pid, SocketChannel socket, MonitorThread monitorThread)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        ClientImpl client = new ClientImpl(device, socket, pid);
        if (client.sendHandshake()) {
//            try {
                if (AndroidDebugBridge.getClientSupport()) {
//                    client.listenForDebugger();
                    String msg = String.format(Locale.US, "Opening a debugger listener at port %1$d for client with pid %2$d", client.getDebuggerListenPort(), pid);
                    Log.d("ddms", msg);
                }
//            } catch (IOException var6) {
//                client.getClientData().setDebuggerConnectionStatus(DebuggerStatus.ERROR);
//                Log.e("ddms", "Can't bind to local " + client.getDebuggerListenPort() + " for debugger");
//            }

            client.requestAllocationStatus();
        } else {
            Log.e("ddms", "Handshake with " + client + " failed!");
        }

        if (client.isValid()) {
            Method method = DeviceImpl.class.getDeclaredMethod("addClient", ClientImpl.class);
            method.setAccessible(true);
            method.invoke(device, client);
            monitorThread.addClient(client);

            client.getClientData().setNames(new ClientData.Names(
                    CommonsKt.getAndroidApkPackage(),
                    0,
                    CommonsKt.getAndroidApkPackage()));
            client.getClientData().setAbi("64-bit");
            return true;
        }

        return false;
    }
}
