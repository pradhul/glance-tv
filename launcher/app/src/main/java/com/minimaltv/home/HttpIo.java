package com.minimaltv.home;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Small GET (and v37: fire-and-forget POST) helper for Bing, Open-Meteo, Nager.Date, orders JSON and the VPN country lookup. Call off the main
 * thread. Null on any failure (timeout, non-200, too large, parse later).
 */
final class HttpIo {

    private HttpIo() {
    }

    static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new IOException("too big");
        }
        return out.toByteArray();
    }

    static byte[] getBytes(String url, int connectMs, int readMs, int max, String userAgent, String accept) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
            if (accept != null) c.setRequestProperty("Accept", accept);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return read(in, max);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String getString(String url, int connectMs, int readMs, int max) {
        byte[] b = getBytes(url, connectMs, readMs, max, null, null);
        if (b == null) return null;
        try {
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** v37: empty-body POST; true on any 2xx. */
    static boolean post(String url, int connectMs, int readMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
