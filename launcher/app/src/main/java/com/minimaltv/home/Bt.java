package com.minimaltv.home;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v25: bonded Bluetooth remotes and speakers/headphones, for the icons by the clock. Connection comes from the
 * hidden BluetoothDevice.isConnected() (reflection) and the ACL broadcasts; battery from the hidden
 * getBatteryLevel() and ACTION_BATTERY_LEVEL_CHANGED. Every hidden call is optional: a failure just means
 * "unknown". Needs BLUETOOTH_CONNECT; without it there is simply nothing to show.
 */
final class Bt {

    static final int KIND_REMOTE = 0, KIND_AUDIO = 1;
    static final int LOW_BATTERY = 20;
    static final String ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    static final String EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL";

    /** What the icon row draws for one device. */
    static final class State {
        final String address, name;
        final int kind;
        final boolean headphones;
        final boolean connected;
        final int battery;   // 0-100, or -1 unknown

        State(String address, String name, int kind, boolean headphones, boolean connected, int battery) {
            this.address = address;
            this.name = name;
            this.kind = kind;
            this.headphones = headphones;
            this.connected = connected;
            this.battery = battery;
        }

        boolean low() {
            return battery >= 0 && battery < LOW_BATTERY;
        }
    }

    // Process-wide, so "seen connected this session" survives the activity being recreated.
    private static final Set<String> seenConnected = new HashSet<>();
    private static final Set<String> aclConnected = new HashSet<>();
    private static final Set<String> aclDisconnected = new HashSet<>();
    private static final Map<String, Integer> battery = new HashMap<>();

    private final Context context;
    private final Runnable onChange;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String a = d != null ? d.getAddress() : null;
            String action = intent.getAction();
            if (a != null && action != null) onEvent(action, a, intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1));
            onChange.run();
        }
    };

    Bt(Context context, Runnable onChange) {
        this.context = context;
        this.onChange = onChange;
    }

    /** Applies one broadcast; static and package-private so tests can drive it without a radio. */
    static void onEvent(String action, String address, int level) {
        synchronized (seenConnected) {
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                aclConnected.add(address);
                aclDisconnected.remove(address);
                seenConnected.add(address);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                aclDisconnected.add(address);
                aclConnected.remove(address);
            } else if (ACTION_BATTERY_LEVEL_CHANGED.equals(action)) {
                if (level >= 0 && level <= 100) battery.put(address, level);
                else battery.remove(address);
            }
        }
    }

    static void resetForTest() {
        synchronized (seenConnected) {
            seenConnected.clear();
            aclConnected.clear();
            aclDisconnected.clear();
            battery.clear();
        }
    }

    boolean permitted() {
        return Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        if (registered || !permitted()) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(ACTION_BATTERY_LEVEL_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            context.registerReceiver(receiver, f);
            registered = true;
        } catch (RuntimeException ignored) {
            // No live updates; the row still refreshes on resume.
        }
    }

    void stop() {
        if (!registered) return;
        registered = false;
        try {
            context.unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }

    /** Remote or audio, or -1 for anything else (phones, keyboards we do not draw, ...). */
    static int kindOf(int major, int deviceClass, String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return KIND_AUDIO;
        if (major == BluetoothClass.Device.Major.PERIPHERAL || n.contains("remote") || n.endsWith(" rc") || n.contains("rc ")) return KIND_REMOTE;
        if (n.contains("speaker") || n.contains("buds") || n.contains("headphone") || n.contains("soundbar")) return KIND_AUDIO;
        return -1;
    }

    static boolean headphones(int deviceClass, String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES
                || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET
                || n.contains("buds") || n.contains("headphone") || n.contains("airpods");
    }

    /** One device per kind, connected first: what the row shows. Never-connected-this-session devices are left out. */
    List<State> states() {
        List<State> out = new ArrayList<>();
        if (!permitted()) return out;
        Set<BluetoothDevice> bonded;
        try {
            BluetoothManager bm = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
            if (ad == null || !ad.isEnabled()) return out;
            bonded = ad.getBondedDevices();
        } catch (RuntimeException e) {
            return out;
        }
        if (bonded == null) return out;
        List<State> all = new ArrayList<>();
        for (BluetoothDevice d : bonded) {
            try {
                String name = d.getName();
                BluetoothClass bc = d.getBluetoothClass();
                int major = bc != null ? bc.getMajorDeviceClass() : -1, cls = bc != null ? bc.getDeviceClass() : -1;
                int kind = kindOf(major, cls, name);
                if (kind < 0 && hasHidUuid(d)) kind = KIND_REMOTE;   // BLE remotes often report no class at all
                if (kind < 0) continue;
                String a = d.getAddress();
                Boolean hidden = callBool(d, "isConnected");
                boolean conn;
                int level;
                synchronized (seenConnected) {
                    conn = hidden != null ? hidden : aclConnected.contains(a);
                    // A disconnect broadcast newer than the query is trusted when the hidden call is unavailable.
                    if (hidden == null && aclDisconnected.contains(a)) conn = false;
                    if (conn) seenConnected.add(a);
                    if (!seenConnected.contains(a)) continue;
                    Integer b = battery.get(a);
                    level = b != null ? b : -1;
                }
                if (conn) {
                    Integer hl = callInt(d, "getBatteryLevel");
                    if (hl != null && hl >= 0 && hl <= 100) level = hl;
                } else {
                    level = -1;   // a stale level on a dimmed icon would only mislead
                }
                all.add(new State(a, name, kind, kind == KIND_AUDIO && headphones(cls, name), conn, level));
            } catch (RuntimeException ignored) {
                // A device that throws (permission revoked mid-loop, ...) is just skipped.
            }
        }
        // Per kind: every connected device (a TV can have two remotes paired and both awake), else the one seen last.
        for (int kind : new int[]{KIND_REMOTE, KIND_AUDIO}) {
            State fallback = null;
            int n = 0;
            for (State s : all) {
                if (s.kind != kind) continue;
                if (s.connected && n < 2) {
                    out.add(s);
                    n++;
                } else if (fallback == null) {
                    fallback = s;
                }
            }
            if (n == 0 && fallback != null) out.add(fallback);
        }
        {   // one line per shown device: `adb logcat -s MinimalTvBt`
            for (State s : out) android.util.Log.i("MinimalTvBt", s.address + " " + s.name + " kind=" + s.kind + " conn=" + s.connected + " batt=" + s.battery);
        }
        return out;
    }

    /** HID (classic 0x1124) or HID over GATT (0x1812) among the device's cached service UUIDs. */
    private static boolean hasHidUuid(BluetoothDevice d) {
        try {
            android.os.ParcelUuid[] uuids = d.getUuids();
            if (uuids == null) return false;
            for (android.os.ParcelUuid u : uuids) {
                String x = u.getUuid().toString().toLowerCase(java.util.Locale.ROOT);
                if (x.startsWith("00001812-") || x.startsWith("00001124-")) return true;
            }
        } catch (RuntimeException ignored) {
            // Unknown: not a remote.
        }
        return false;
    }

    private static Boolean callBool(Object o, String m) {
        try {
            Method method = o.getClass().getMethod(m);
            Object r = method.invoke(o);
            return r instanceof Boolean ? (Boolean) r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static Integer callInt(Object o, String m) {
        try {
            Method method = o.getClass().getMethod(m);
            Object r = method.invoke(o);
            return r instanceof Integer ? (Integer) r : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
