package com.gathin.tvhome;

import android.util.Log;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

public final class LinuxInputClient {
    private static final String TAG = "TvHome.LinuxInput";
    private static final String KEY_DEVICE = "/dev/input/event0";
    private static final String MOUSE_DEVICE = "/dev/input/event1";

    private static FileOutputStream keyStream;
    private static FileOutputStream mouseStream;
    private static final Object LOCK = new Object();

    // Map Android KeyEvent keycodes to Linux input_event codes
    private static final Map<Integer, Integer> KEY_MAP = new HashMap<>();

    static {
        KEY_MAP.put(19, 103); // DPAD_UP -> KEY_UP
        KEY_MAP.put(20, 108); // DPAD_DOWN -> KEY_DOWN
        KEY_MAP.put(21, 105); // DPAD_LEFT -> KEY_LEFT
        KEY_MAP.put(22, 106); // DPAD_RIGHT -> KEY_RIGHT
        KEY_MAP.put(23, 28);  // DPAD_CENTER -> KEY_ENTER
        KEY_MAP.put(66, 28);  // ENTER -> KEY_ENTER
        KEY_MAP.put(4, 158);  // BACK -> KEY_BACK
        KEY_MAP.put(82, 139); // MENU -> KEY_MENU
        KEY_MAP.put(3, 172);  // HOME -> KEY_HOMEPAGE
        KEY_MAP.put(24, 115); // VOLUME_UP -> KEY_VOLUMEUP
        KEY_MAP.put(25, 114); // VOLUME_DOWN -> KEY_VOLUMEDOWN
        KEY_MAP.put(164, 113);// VOLUME_MUTE -> KEY_MUTE
        KEY_MAP.put(26, 116); // POWER -> KEY_POWER
        KEY_MAP.put(85, 164); // MEDIA_PLAY_PAUSE -> KEY_PLAYPAUSE
        KEY_MAP.put(89, 168); // MEDIA_REWIND -> KEY_REWIND
        KEY_MAP.put(90, 208); // MEDIA_FAST_FORWARD -> KEY_FASTFORWARD
        KEY_MAP.put(67, 14);  // DEL (Backspace) -> KEY_BACKSPACE
        KEY_MAP.put(7, 11);   // 0 -> KEY_0
        KEY_MAP.put(8, 2);    // 1 -> KEY_1
        KEY_MAP.put(9, 3);    // 2 -> KEY_2
        KEY_MAP.put(10, 4);   // 3 -> KEY_3
        KEY_MAP.put(11, 5);   // 4 -> KEY_4
        KEY_MAP.put(12, 6);   // 5 -> KEY_5
        KEY_MAP.put(13, 7);   // 6 -> KEY_6
        KEY_MAP.put(14, 8);   // 7 -> KEY_7
        KEY_MAP.put(15, 9);   // 8 -> KEY_8
        KEY_MAP.put(16, 10);  // 9 -> KEY_9
    }

    private static FileOutputStream ensureKeyStream() {
        if (keyStream != null) return keyStream;
        try {
            keyStream = new FileOutputStream(KEY_DEVICE);
            Log.i(TAG, "Direct Linux keyboard opened on " + KEY_DEVICE);
            return keyStream;
        } catch (Exception e) {
            Log.w(TAG, "Initial open of " + KEY_DEVICE + " failed, attempting chmod 666: " + e.getMessage());
            AdbClient.execute("chmod 666 /dev/input/event*");
            try {
                Thread.sleep(50);
                keyStream = new FileOutputStream(KEY_DEVICE);
                Log.i(TAG, "Direct Linux keyboard opened on " + KEY_DEVICE + " after chmod");
                return keyStream;
            } catch (Exception retryEx) {
                Log.w(TAG, "Retry open of " + KEY_DEVICE + " failed: " + retryEx.getMessage());
                return null;
            }
        }
    }

    private static FileOutputStream ensureMouseStream() {
        if (mouseStream != null) return mouseStream;
        try {
            mouseStream = new FileOutputStream(MOUSE_DEVICE);
            Log.i(TAG, "Direct Linux mouse opened on " + MOUSE_DEVICE);
            return mouseStream;
        } catch (Exception e) {
            Log.w(TAG, "Initial open of " + MOUSE_DEVICE + " failed, attempting chmod 666: " + e.getMessage());
            AdbClient.execute("chmod 666 /dev/input/event*");
            try {
                Thread.sleep(50);
                mouseStream = new FileOutputStream(MOUSE_DEVICE);
                Log.i(TAG, "Direct Linux mouse opened on " + MOUSE_DEVICE + " after chmod");
                return mouseStream;
            } catch (Exception retryEx) {
                Log.w(TAG, "Retry open of " + MOUSE_DEVICE + " failed: " + retryEx.getMessage());
                return null;
            }
        }
    }

    public static void init() {
        synchronized (LOCK) {
            try {
                ensureKeyStream();
                ensureMouseStream();
            } catch (Exception e) {
                Log.w(TAG, "LinuxInputClient init error: " + e.getMessage());
            }
        }
    }

    public static boolean sendKey(int androidKeyCode) {
        Integer linuxCode = KEY_MAP.get(androidKeyCode);
        if (linuxCode == null) return false;

        synchronized (LOCK) {
            FileOutputStream stream = ensureKeyStream();
            if (stream == null) return false;

            try {
                // 32-bit Linux struct input_event: 4B tv_sec, 4B tv_usec, 2B type, 2B code, 4B value = 16 bytes
                ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
                // 1. KEY DOWN
                buf.putInt(0).putInt(0).putShort((short) 1).putShort(linuxCode.shortValue()).putInt(1);
                // 2. SYN_REPORT
                buf.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0).putInt(0);
                // 3. KEY UP
                buf.putInt(0).putInt(0).putShort((short) 1).putShort(linuxCode.shortValue()).putInt(0);
                // 4. SYN_REPORT
                buf.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0).putInt(0);

                stream.write(buf.array());
                stream.flush();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Write to " + KEY_DEVICE + " failed: " + e.getMessage());
                try { stream.close(); } catch (Exception ignored) {}
                keyStream = null;
                return false;
            }
        }
    }

    public static boolean sendMouseMove(int dx, int dy) {
        synchronized (LOCK) {
            FileOutputStream stream = ensureMouseStream();
            if (stream == null) return false;

            try {
                // Event 1: REL_X (type 2, code 0, value dx)
                // Event 2: REL_Y (type 2, code 1, value dy)
                // Event 3: SYN_REPORT (type 0, code 0, value 0)
                ByteBuffer buf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
                buf.putInt(0).putInt(0).putShort((short) 2).putShort((short) 0).putInt(dx);
                buf.putInt(0).putInt(0).putShort((short) 2).putShort((short) 1).putInt(dy);
                buf.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0).putInt(0);

                stream.write(buf.array());
                stream.flush();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Write mouse move failed: " + e.getMessage());
                try { stream.close(); } catch (Exception ignored) {}
                mouseStream = null;
                return false;
            }
        }
    }

    public static boolean sendMouseClick() {
        synchronized (LOCK) {
            FileOutputStream stream = ensureMouseStream();
            if (stream == null) return false;

            try {
                // BTN_LEFT is code 272 (0x110)
                ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
                // DOWN
                buf.putInt(0).putInt(0).putShort((short) 1).putShort((short) 272).putInt(1);
                buf.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0).putInt(0);
                // UP
                buf.putInt(0).putInt(0).putShort((short) 1).putShort((short) 272).putInt(0);
                buf.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0).putInt(0);

                stream.write(buf.array());
                stream.flush();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Write mouse click failed: " + e.getMessage());
                try { stream.close(); } catch (Exception ignored) {}
                mouseStream = null;
                return false;
            }
        }
    }

    public static boolean sendMouseScroll(int scroll) {
        synchronized (LOCK) {
            FileOutputStream stream = ensureMouseStream();
            if (stream == null) return false;

            try {
                // REL_WHEEL is code 8
                ByteBuffer buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
                buf.putInt(0).putInt(0).putShort((short) 2).putShort((short) 8).putInt(scroll);
                buf.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0).putInt(0);

                stream.write(buf.array());
                stream.flush();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Write mouse scroll failed: " + e.getMessage());
                try { stream.close(); } catch (Exception ignored) {}
                mouseStream = null;
                return false;
            }
        }
    }
}
