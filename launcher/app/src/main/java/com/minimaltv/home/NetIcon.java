package com.minimaltv.home;

/** v27: which Wi-Fi icon (a level of drawable/ic_wifi_level) the network state calls for. */
final class NetIcon {
    static final int NO_INTERNET = 5, DISCONNECTED = 6, ETHERNET = 7;

    private NetIcon() {
    }

    /** 0-4 bars from an RSSI in dBm, 0 when unknown. */
    static int bars(int rssi) {
        if (rssi == Integer.MIN_VALUE || rssi <= -127 || rssi >= 0) return 0;
        // WifiManager.calculateSignalLevel(rssi, 5), written out (it is deprecated, and stubbed in tests):
        // -100 dBm or below is 0 bars, -55 or above is 4, linear in between.
        if (rssi <= -100) return 0;
        if (rssi >= -55) return 4;
        return Math.max(0, Math.min(4, (rssi + 100) * 4 / 45));
    }

    static int level(boolean ethernet, boolean wifi, boolean validated, int rssi) {
        if (ethernet) return ETHERNET;
        if (!wifi) return DISCONNECTED;
        if (!validated) return NO_INTERNET;
        return bars(rssi);
    }
}
