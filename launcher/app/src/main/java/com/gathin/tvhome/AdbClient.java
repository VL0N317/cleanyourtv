package com.gathin.tvhome;

import android.util.Log;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class AdbClient {
    private static final String TAG = "TvHome.AdbClient";
    private static final int PORT = 5555;
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;

    private static final Object LOCK = new Object();
    private static Socket socket;
    private static OutputStream outStream;
    private static InputStream inStream;
    private static int remoteId;
    private static final int LOCAL_ID = 1;
    private static volatile boolean isConnected = false;

    private static void connect() {
        close();
        try {
            android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder().permitAll().build());
        } catch (Exception ignored) {}
        try {
            socket = new Socket("127.0.0.1", PORT);
            socket.setTcpNoDelay(true);
            outStream = socket.getOutputStream();
            inStream = socket.getInputStream();

            // 1. Send CNXN
            byte[] body = "host::\0".getBytes(StandardCharsets.US_ASCII);
            sendPacket(outStream, A_CNXN, A_VERSION, MAX_DATA, body);

            // Read CNXN response
            byte[] hdr = readBytes(inStream, 24);
            int dataLen = getInt(hdr, 12);
            if (dataLen > 0) readBytes(inStream, dataLen);

            // 2. Send OPEN shell:
            byte[] cmdBytes = "shell:\0".getBytes(StandardCharsets.US_ASCII);
            sendPacket(outStream, A_OPEN, LOCAL_ID, 0, cmdBytes);

            // Read OKAY response
            byte[] openHdr = readBytes(inStream, 24);
            int openCmd = getInt(openHdr, 0);
            if (openCmd != A_OKAY) {
                Log.w(TAG, "ADB OPEN failed: " + Integer.toHexString(openCmd));
                close();
                return;
            }
            remoteId = getInt(openHdr, 4); // remote_id is arg0
            isConnected = true;
            Log.i(TAG, "Persistent ADB shell connected! remoteId=" + remoteId);

            // Start reader thread to keep streaming and ACK stdout from adbd
            Thread readerThread = new Thread(AdbClient::readerLoop, "AdbShellReader");
            readerThread.setDaemon(true);
            readerThread.start();
        } catch (Exception e) {
            Log.e(TAG, "ADB connection failed", e);
            close();
        }
    }

    private static void readerLoop() {
        try {
            while (isConnected && socket != null && !socket.isClosed()) {
                byte[] hdr = readBytes(inStream, 24);
                if (hdr.length < 24) break;
                int cmd = getInt(hdr, 0);
                int dlen = getInt(hdr, 12);
                if (dlen > 0) readBytes(inStream, dlen);

                if (cmd == A_WRTE) {
                    // ACK output so adbd doesn't stall
                    synchronized (LOCK) {
                        if (isConnected && outStream != null) {
                            sendPacket(outStream, A_OKAY, LOCAL_ID, remoteId, null);
                        }
                    }
                } else if (cmd == A_CLSE) {
                    Log.w(TAG, "ADB shell stream closed by server");
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            close();
        }
    }

    private static void close() {
        isConnected = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) { }
        socket = null;
        outStream = null;
        inStream = null;
    }

    public static boolean execute(String command) {
        synchronized (LOCK) {
            if (!isConnected) {
                connect();
            }
            if (!isConnected || outStream == null) {
                return false;
            }
            try {
                byte[] data = (command + "\n").getBytes(StandardCharsets.UTF_8);
                sendPacket(outStream, A_WRTE, LOCAL_ID, remoteId, data);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to write command: " + command, e);
                close();
                connect();
                if (isConnected && outStream != null) {
                    try {
                        byte[] data = (command + "\n").getBytes(StandardCharsets.UTF_8);
                        sendPacket(outStream, A_WRTE, LOCAL_ID, remoteId, data);
                        return true;
                    } catch (Exception retryEx) {
                        Log.e(TAG, "Retry write command failed", retryEx);
                    }
                }
                return false;
            }
        }
    }

    private static void sendPacket(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws Exception {
        int len = (data != null ? data.length : 0);
        ByteBuffer buf = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(cmd);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(len);
        int crc = 0;
        if (data != null) {
            for (byte b : data) crc += (b & 0xff);
        }
        buf.putInt(crc);
        buf.putInt(cmd ^ 0xffffffff);
        if (data != null) buf.put(data);
        out.write(buf.array());
        out.flush();
    }

    private static byte[] readBytes(InputStream in, int count) throws Exception {
        byte[] buf = new byte[count];
        int read = 0;
        while (read < count) {
            int n = in.read(buf, read, count - read);
            if (n < 0) break;
            read += n;
        }
        return buf;
    }

    private static int getInt(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
