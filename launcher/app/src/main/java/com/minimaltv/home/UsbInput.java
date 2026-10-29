package com.minimaltv.home;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v44: external non-Bluetooth input devices (USB 2.4GHz air-mouse dongles, USB keyboards/mice) for the remotes row.
 * One entry per vendor+product (a dongle shows up as a Mouse node and a Keyboard node), with a clean name. Built-in
 * MTK inputs, virtual devices and anything Bluetooth (already drawn by {@link Bt}) are left out. Live via
 * InputManager.InputDeviceListener. USB has no battery, so the entries carry battery -1.
 */
final class UsbInput {

    /** The bits of an InputDevice the filter needs; plain so tests can build them without a device. */
    static final class Dev {
        final int id, vendor, product, sources;
        final String name, btAddress;
        final boolean external, virtual;

        Dev(int id, String name, int vendor, int product, int sources, boolean external, boolean virtual, String btAddress) {
            this.id = id;
            this.name = name;
            this.vendor = vendor;
            this.product = product;
            this.sources = sources;
            this.external = external;
            this.virtual = virtual;
            this.btAddress = btAddress;
        }
    }

    private static final String[] SUFFIXES = {"consumer control", "system control", "keyboard", "mouse", "touchpad"};

    /** "AI Smart control AI Smart control02 Mouse" -> "AI Smart control02": drops the node suffix and a repeated prefix. */
    static String cleanName(String raw) {
        if (raw == null) return "";
        String n = raw.trim().replaceAll("\\s+", " ");
        boolean cut = true;
        while (cut) {
            cut = false;
            String l = n.toLowerCase(Locale.ROOT);
            for (String s : SUFFIXES) {
                if (l.length() > s.length() && l.endsWith(" " + s)) {
                    n = n.substring(0, n.length() - s.length() - 1).trim();
                    cut = true;
                    break;
                }
            }
        }
        String[] w = n.split(" ");
        // Largest k where the first k words repeat right after (the last repeated word may carry a suffix, "control02").
        for (int k = w.length / 2; k >= 1; k--) {
            boolean dup = true;
            for (int i = 0; i < k && dup; i++) {
                String a = w[i].toLowerCase(Locale.ROOT), b = w[k + i].toLowerCase(Locale.ROOT);
                dup = i < k - 1 ? a.equals(b) : b.startsWith(a);
            }
            if (dup) {
                StringBuilder sb = new StringBuilder();
                for (int i = k; i < w.length; i++) sb.append(i > k ? " " : "").append(w[i]);
                return sb.toString();
            }
        }
        return n;
    }

    static boolean wanted(Dev d) {
        if (d == null || d.name == null || !d.external || d.virtual || d.id < 0) return false;
        if (d.btAddress != null && !d.btAddress.isEmpty()) return false;
        String l = d.name.toLowerCase(Locale.ROOT);
        if (l.startsWith("virtual") || l.contains("mtk") || l.contains("mediatek")) return false;
        int s = d.sources;
        return (s & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                || (s & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                || (s & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
    }

    /** Filtered, one per vendor+product, as connected remotes without a battery. Names in {@code skipNames} (BT) are dropped. */
    static List<Bt.State> states(List<Dev> devs, java.util.Collection<String> skipNames) {
        Map<String, Bt.State> out = new LinkedHashMap<>();
        for (Dev d : devs) {
            if (!wanted(d)) continue;
            String name = cleanName(d.name);
            if (name.isEmpty() || skip(name, d.name, skipNames)) continue;
            String key = String.format(Locale.ROOT, "usb:%04x:%04x", d.vendor, d.product);
            if (d.vendor == 0 && d.product == 0) key = "usb:" + name.toLowerCase(Locale.ROOT);
            if (out.containsKey(key)) continue;
            out.put(key, new Bt.State(key, name, Bt.KIND_REMOTE, false, true, -1, true));
        }
        return new ArrayList<>(out.values());
    }

    private static boolean skip(String clean, String raw, java.util.Collection<String> skipNames) {
        if (skipNames == null) return false;
        for (String s : skipNames) {
            if (s == null || s.trim().isEmpty()) continue;
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (clean.toLowerCase(Locale.ROOT).equals(t) || raw.toLowerCase(Locale.ROOT).startsWith(t)) return true;
        }
        return false;
    }

    private final InputManager im;
    private final Runnable onChange;
    private boolean registered;

    private final InputManager.InputDeviceListener listener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { onChange.run(); }
        @Override public void onInputDeviceRemoved(int id) { onChange.run(); }
        @Override public void onInputDeviceChanged(int id) { onChange.run(); }
    };

    UsbInput(Context context, Runnable onChange) {
        InputManager m = null;
        try {
            m = context.getSystemService(InputManager.class);
        } catch (RuntimeException ignored) {
            // No input service: nothing to list.
        }
        this.im = m;
        this.onChange = onChange;
    }

    void start() {
        if (registered || im == null) return;
        try {
            im.registerInputDeviceListener(listener, new Handler(Looper.getMainLooper()));
            registered = true;
        } catch (RuntimeException ignored) {
            // No live updates; resume still refreshes.
        }
    }

    void stop() {
        if (!registered) return;
        registered = false;
        try {
            im.unregisterInputDeviceListener(listener);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }

    List<Dev> devices() {
        List<Dev> out = new ArrayList<>();
        if (im == null) return out;
        int[] ids;
        try {
            ids = im.getInputDeviceIds();
        } catch (RuntimeException e) {
            return out;
        }
        for (int id : ids) {
            try {
                InputDevice d = im.getInputDevice(id);
                if (d == null) continue;
                boolean ext = Build.VERSION.SDK_INT >= 29 ? d.isExternal() : !d.isVirtual();
                String bt = null;
                try {
                    Object r = InputDevice.class.getMethod("getBluetoothAddress").invoke(d);
                    if (r instanceof String) bt = (String) r;
                } catch (Throwable ignored) {
                    // Pre-34 or hidden: assume not Bluetooth.
                }
                out.add(new Dev(id, d.getName(), d.getVendorId(), d.getProductId(), d.getSources(), ext, d.isVirtual(), bt));
            } catch (RuntimeException ignored) {
                // Skip a device that vanished mid-loop.
            }
        }
        return out;
    }
}
