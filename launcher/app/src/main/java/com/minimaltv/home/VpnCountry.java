package com.minimaltv.home;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Asks a public IP lookup service which country this connection exits from. The only network use in the
 * launcher: it runs while a VPN is up, once per VPN connection, so the service sees the VPN's address and
 * never the real one.
 */
final class VpnCountry {

    interface Callback {
        /** The English country name, or null when no service answered. Called on a background thread. */
        void onResult(String countryName);
    }

    // Free services that need no key, tried in order. Each answers with a two-letter country code.
    private static final String[][] SERVICES = {
            {"https://api.country.is/", "country"},
            {"https://ipwho.is/", "country_code"},
    };

    private VpnCountry() {
    }

    static void lookup(Callback callback) {
        new Thread(() -> {
            String name = null;
            for (String[] service : SERVICES) {
                String code = fetchCode(service[0], service[1]);
                if (code != null) {
                    name = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
                    if (name.isEmpty()) name = code;
                    break;
                }
            }
            callback.onResult(name);
        }).start();
    }

    private static String fetchCode(String url, String field) {
        byte[] body = HttpIo.getBytes(url, 5000, 5000, 8192, null, "application/json");
        if (body == null) return null;
        try {
            String code = new JSONObject(new String(body, "UTF-8")).optString(field, "");
            return code.length() == 2 ? code.toUpperCase(Locale.ROOT) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
