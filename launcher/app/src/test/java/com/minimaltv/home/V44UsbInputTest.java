package com.minimaltv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** v44: USB 2.4GHz remotes from InputManager: filter, one per vendor+product, clean name, no battery. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V44UsbInputTest {
    private static final int KB = InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD, MOUSE = InputDevice.SOURCE_MOUSE;

    private static UsbInput.Dev dev(int id, String name, int v, int p, int src, boolean ext, String bt) {
        return new UsbInput.Dev(id, name, v, p, src, ext, false, bt);
    }

    @Test
    public void cleanName_stripsDuplicateAndSuffix() {
        assertEquals("AI Smart control02", UsbInput.cleanName("AI Smart control AI Smart control02 Mouse"));
        assertEquals("AI Smart control02", UsbInput.cleanName("AI Smart control AI Smart control02 Keyboard"));
        assertEquals("AI Smart control02", UsbInput.cleanName("AI Smart control AI Smart control02 Consumer Control"));
        assertEquals("Logitech K400", UsbInput.cleanName("Logitech K400"));
        assertEquals("G20", UsbInput.cleanName("G20 G20 Mouse"));
    }

    @Test
    public void airMouse_dedupedToOneConnectedRemoteWithoutBattery() {
        List<UsbInput.Dev> devs = Arrays.asList(
                dev(5, "AI Smart control AI Smart control02 Mouse", 0x1915, 0x1025, MOUSE, true, null),
                dev(6, "AI Smart control AI Smart control02 Keyboard", 0x1915, 0x1025, KB, true, null),
                dev(2, "mtk-kpd", 0, 0, KB, false, null),
                dev(3, "MTK_IR remote", 0x0e8f, 1, KB, true, null),
                dev(-1, "Virtual", 0, 0, KB, false, null),
                dev(7, "virtual-keyboard", 0, 0, KB, true, null),
                dev(8, "Xiaomi RC", 0x2717, 0x32b9, KB, true, "AA:BB:CC:DD:EE:FF"));
        List<Bt.State> s = UsbInput.states(devs, Collections.emptyList());
        assertEquals(1, s.size());
        Bt.State a = s.get(0);
        assertEquals("AI Smart control02", a.name);
        assertTrue(a.usb);
        assertTrue(a.connected);
        assertEquals(-1, a.battery);
        assertEquals(Bt.KIND_REMOTE, a.kind);
        assertEquals("AI Smart control02", Bt.label(a));
        assertEquals(Bt.color(new Bt.State("x", "x", Bt.KIND_REMOTE, false, true, -1), 0xFFFFFFFF), Bt.color(a, 0xFFFFFFFF));
    }

    @Test
    public void bluetoothNamesAndNonInputSourcesSkipped() {
        List<UsbInput.Dev> devs = Arrays.asList(
                dev(8, "Xiaomi RC", 0x2717, 0x32b9, KB, true, null),
                dev(9, "USB Audio", 0x1, 0x2, InputDevice.SOURCE_UNKNOWN, true, null));
        assertTrue(UsbInput.states(devs, Collections.singletonList("Xiaomi RC")).isEmpty());
        assertFalse(UsbInput.wanted(devs.get(1)));
    }
}
