package com.minimaltv.home;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * v36: upcoming shopping orders on the same top-left status line as the holiday text.
 * v37: each order shows its short item name and date instead of the store
 * ({@code Diwali · in 6 days  |  [box] Air Mouse · Thu 29 Oct  |  Phone Case · shipped  [refresh]}).
 */
final class Orders {

    static final class Order {
        final String store;
        final String item;
        final String status;
        final LocalDate eta;
        /** v40: optional product image URL (null when missing). */
        final String image;

        Order(String store, String item, String status, LocalDate eta) {
            this(store, item, status, eta, null);
        }

        Order(String store, String item, String status, LocalDate eta, String image) {
            this.store = store;
            this.item = item;
            this.status = status;
            this.eta = eta;
            this.image = image;
        }

        boolean outForDelivery() {
            return "out_for_delivery".equals(status);
        }

        boolean hidden() {
            return "delivered".equals(status) || "cancelled".equals(status);
        }
    }

    static final class Snapshot {
        final Instant updated;
        final List<Order> orders;

        Snapshot(Instant updated, List<Order> orders) {
            this.updated = updated;
            this.orders = orders;
        }
    }

    static final String KEY_URL = "orders_url";
    /** v39: no built-in feed; empty = orders feature off until a URL is set (e.g. via the ordersUrl extra). */
    static final String DEFAULT_URL = "";
    static final String KEY_CACHE = "orders_json";
    /** v37: optional endpoint POSTed before a manual refresh (e.g. a workflow_dispatch proxy). Empty = off. */
    static final String KEY_REFRESH_URL = "orders_refresh_url";
    static final long TRIGGER_WAIT_MS = 60_000L;
    /** v37: placeholder the activity replaces with the box icon at the start of the orders section. */
    static final String ICON_SLOT = "\uFFFC";
    static final int MAX_LABEL = 18;
    static final String EXTRA_URL = "ordersUrl";
    /** v39: sets {@link #KEY_REFRESH_URL}; an empty value clears it. */
    static final String EXTRA_REFRESH_URL = "ordersRefreshUrl";
    static final String EXTRA_JSON = "ordersJson";
    static final long POLL_MS = 10 * 60_000L;
    static final Duration STALE_AFTER = Duration.ofHours(24);
    private static final String TAG = "Orders";
    private static final DateTimeFormatter ETA = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final int MAX_SHOWN = 3;

    /** Tests pin the clock so formatting does not depend on the machine date. */
    static volatile LocalDate todayForTest;
    static volatile Instant nowForTest;

    private Orders() {
    }

    static LocalDate today() {
        return todayForTest != null ? todayForTest : LocalDate.now();
    }

    static Instant now() {
        return nowForTest != null ? nowForTest : Instant.now();
    }

    static String url(String raw) {
        return raw == null ? "" : raw.trim();
    }

    static Snapshot parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            Instant updated = OffsetDateTime.parse(root.getString("updated")).toInstant();
            JSONArray arr = root.optJSONArray("orders");
            List<Order> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String store = o.optString("store", "").trim();
                    String status = o.optString("status", "").trim();
                    if (store.isEmpty() || status.isEmpty()) continue;
                    LocalDate eta = null;
                    if (o.has("eta") && !o.isNull("eta")) {
                        String raw = o.optString("eta", "").trim();
                        if (!raw.isEmpty()) {
                            try {
                                eta = LocalDate.parse(raw);
                            } catch (RuntimeException ignored) {
                                eta = null;
                            }
                        }
                    }
                    String image = null;
                    if (o.has("image") && !o.isNull("image")) {
                        String raw = o.optString("image", "").trim();
                        if (raw.startsWith("http://") || raw.startsWith("https://")) image = raw;
                    }
                    list.add(new Order(store, o.isNull("item") ? "" : o.optString("item", ""), status, eta, image));
                }
            }
            return new Snapshot(updated, list);
        } catch (Exception e) {
            Log.w(TAG, "bad orders json", e);
            return null;
        }
    }

    static boolean stale(Snapshot s, Instant now) {
        if (s == null || s.updated == null || now == null) return true;
        return Duration.between(s.updated, now).compareTo(STALE_AFTER) > 0;
    }

    static List<Order> visible(Snapshot s, LocalDate today) {
        List<Order> out = new ArrayList<>();
        if (s == null) return out;
        for (Order o : s.orders) if (!o.hidden()) out.add(o);
        out.sort(Comparator
                .comparing((Order o) -> !o.outForDelivery())
                .thenComparing(o -> o.eta == null)
                .thenComparing(o -> o.eta, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /** v37: date first ({@code Today}, {@code Tomorrow}, {@code Thu 29 Oct}), then out-for-delivery, then the status word. */
    static String phrase(Order o, LocalDate today) {
        if (o.eta != null) {
            long days = ChronoUnit.DAYS.between(today, o.eta);
            if (days <= 0) return "Today";
            if (days == 1) return "Tomorrow";
            return ETA.format(o.eta);
        }
        if (o.outForDelivery()) return "arriving today";
        return o.status.replace('_', ' ');
    }

    /** v37: the short item name (store if no item), cut at a word boundary with an ellipsis past {@link #MAX_LABEL}. */
    static String label(Order o) {
        String item = o.item == null ? "" : o.item.trim().replaceAll("\\s+", " ");
        return item.isEmpty() ? o.store : shorten(item, MAX_LABEL);
    }

    static String shorten(String s, int max) {
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max);
        String head = (cut > 0 ? s.substring(0, cut) : s.substring(0, max)).replaceAll("[\\s,.;:\\-–(]+$", "");
        return head + "…";
    }

    /** v37: cache-busting copy of the orders URL for a manual refresh (raw gist URLs sit behind a CDN). */
    static String bust(String url, long nowMs) {
        return url + (url.contains("?") ? "&" : "?") + "t=" + nowMs;
    }

    static boolean post(String url) {
        return HttpIo.post(url, 5000, 5000);
    }

    /** Null when there is nothing to show (stale, empty, or unavailable). */
    static String line(Snapshot s, LocalDate today, Instant now) {
        if (stale(s, now)) return null;
        List<Order> vis = visible(s, today);
        if (vis.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        int n = Math.min(MAX_SHOWN, vis.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append("  |  ");
            Order o = vis.get(i);
            b.append(label(o)).append(" · ").append(phrase(o, today));
        }
        int extra = vis.size() - n;
        if (extra > 0) b.append("  |  +").append(extra);
        return b.toString();
    }

    static String line(Snapshot s) {
        return line(s, today(), now());
    }

    /** Holiday first, then orders, same separators as the order list. Null if both empty. */
    static String join(String holiday, String orders) {
        boolean h = holiday != null && !holiday.isEmpty();
        boolean o = orders != null && !orders.isEmpty();
        if (h && o) return holiday + "  |  " + orders;
        if (h) return holiday;
        if (o) return orders;
        return null;
    }

    // ---- v40: All apps orders strip -------------------------------------------------------------

    static final String[] STEPS = {"Ordered", "Shipped", "Out", "Delivered"};
    static final int[] STEP_COLORS = {0xFF9E9E9E, 0xFF4A8CFF, 0xFFFFB300, 0xFF43C463};

    /** v40: 0 ordered, 1 shipped, 2 out for delivery, 3 delivered. Unknown statuses count as ordered. */
    static int step(String status) {
        if (status == null) return 0;
        switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "shipped":
            case "in_transit":
                return 1;
            case "out_for_delivery":
                return 2;
            case "delivered":
                return 3;
            default:
                return 0;
        }
    }

    static int stepColor(int step) {
        return STEP_COLORS[Math.max(0, Math.min(3, step))];
    }

    /** v41: no store logos in the icon pack; always 0 so every badge shows the store name in small text. */
    static int storeIcon(String store) {
        return 0;
    }

    /** v40: {@code due Mon 26 Oct} (out for delivery) or {@code by Thu 29 Oct}; null when there is no eta. */
    static String cardDate(Order o) {
        if (o.eta == null) return null;
        return (o.outForDelivery() || step(o.status) == 0 ? "due " : "by ") + ETA.format(o.eta);
    }

    /** v40: the cards for the All apps strip; empty (strip hidden) when stale or nothing to show. */
    static List<Order> cards(Snapshot s, LocalDate today, Instant now) {
        if (stale(s, now)) return new ArrayList<>();
        List<Order> vis = visible(s, today);
        return vis.size() > MAX_SHOWN ? new ArrayList<>(vis.subList(0, MAX_SHOWN)) : vis;
    }

    static String cached(SharedPreferences prefs) {
        return prefs.getString(KEY_CACHE, null);
    }

    static void saveCache(SharedPreferences prefs, String json) {
        prefs.edit().putString(KEY_CACHE, json).apply();
    }

    static String fetch(String url) {
        return HttpIo.getString(url, 8000, 8000, 1 << 20);
    }
}
