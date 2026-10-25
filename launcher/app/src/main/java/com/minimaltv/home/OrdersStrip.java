package com.minimaltv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * v40: the orders cards in All apps (v42: a vertical, scrollable column on the right). One non-focusable card per order: product thumbnail
 * (async, rounded, box icon fallback), bold item name, store + date, a 4-step progress bar and a store badge.
 */
final class OrdersStrip {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(24);

    private OrdersStrip() {
    }

    static void bind(LinearLayout strip, List<Orders.Order> orders) {
        strip.removeAllViews();
        strip.setFocusable(false);
        strip.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (orders == null || orders.isEmpty()) {
            strip.setVisibility(View.GONE);
            return;
        }
        strip.setVisibility(View.VISIBLE);
        Context c = strip.getContext();
        for (int i = 0; i < orders.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) lp.topMargin = dp(c, 8);
            strip.addView(card(c, orders.get(i)), lp);
        }
    }

    static View card(Context c, Orders.Order o) {
        FrameLayout frame = new FrameLayout(c);
        frame.setFocusable(false);
        frame.setTag("order_card");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x66000000);
        bg.setCornerRadius(dp(c, 10));
        bg.setStroke(Math.max(1, dp(c, 1)), 0x40FFFFFF);
        frame.setBackground(bg);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, 7), dp(c, 7), dp(c, 8), dp(c, 3));
        frame.addView(col, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));

        ImageView thumb = new ImageView(c);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setClipToOutline(true);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0x33FFFFFF);
        tb.setCornerRadius(dp(c, 7));
        thumb.setBackground(tb);
        fallback(thumb);
        head.addView(thumb, new LinearLayout.LayoutParams(dp(c, 34), dp(c, 34)));
        if (o.image != null) load(thumb, o.image);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(dp(c, 8));
        tp.setMarginEnd(dp(c, 36));
        head.addView(text, tp);
        TextView name = new TextView(c);
        name.setText(Orders.label(o));
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(12);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);
        String date = Orders.cardDate(o);
        TextView sub = new TextView(c);
        sub.setText(date == null ? o.store : o.store + " · " + date);
        sub.setTextColor(0xB3FFFFFF);
        sub.setTextSize(9);
        sub.setSingleLine(true);
        text.addView(sub);

        col.addView(progress(c, Orders.step(o.status)), new LinearLayout.LayoutParams(-1, dp(c, 28)));

        // store badge, top-right
        int icon = Orders.storeIcon(o.store);
        View badge;
        if (icon != 0) {
            ImageView iv = new ImageView(c);
            iv.setImageResource(icon);
            iv.setColorFilter(0xE6FFFFFF);
            iv.setContentDescription(o.store);
            badge = iv;
            frame.addView(iv, badgeLp(c, dp(c, 20), dp(c, 20)));
        } else {
            TextView tv = new TextView(c);
            tv.setText(o.store);
            tv.setTextSize(8);
            tv.setTextColor(0xCCFFFFFF);
            badge = tv;
            frame.addView(tv, badgeLp(c, -2, -2));
        }
        badge.setTag("store_badge");
        return frame;
    }

    private static FrameLayout.LayoutParams badgeLp(Context c, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.END);
        lp.topMargin = dp(c, 6);
        lp.rightMargin = dp(c, 8);
        return lp;
    }

    /** Dots on a line, done segments in the step colour, the current dot larger with a bold label. */
    static View progress(Context c, int step) {
        final int color = Orders.stepColor(step);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout line = new FrameLayout(c);
            View left = new View(c), right = new View(c);
            left.setBackgroundColor(i == 0 ? 0 : (i <= step ? color : 0x40FFFFFF));
            right.setBackgroundColor(i == 3 ? 0 : (i < step ? color : 0x40FFFFFF));
            FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(-1, Math.max(1, dp(c, 2)), Gravity.CENTER_VERTICAL);
            LinearLayout halves = new LinearLayout(c);
            halves.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));
            halves.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));
            line.addView(halves, l);
            View dot = new View(c);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            boolean cur = i == step;
            d.setColor(i <= step ? color : 0x80FFFFFF);
            if (cur) d.setStroke(Math.max(1, dp(c, 2)), 0xFFFFFFFF);
            dot.setBackground(d);
            int s = dp(c, cur ? 11 : 6);
            line.addView(dot, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            cell.addView(line, new LinearLayout.LayoutParams(-1, dp(c, 14)));
            TextView t = new TextView(c);
            t.setText(Orders.STEPS[i]);
            t.setTextSize(7);
            t.setTextColor(cur ? 0xFFFFFFFF : 0x99FFFFFF);
            if (cur) t.setTypeface(Typeface.DEFAULT_BOLD);
            cell.addView(t);
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return row;
    }

    private static void fallback(ImageView v) {
        Drawable box = v.getContext().getDrawable(R.drawable.ic_package);
        if (box != null) {
            box = box.mutate();
            box.setTint(0xE6FFFFFF);
        }
        int p = dp(v.getContext(), 8);
        v.setPadding(p, p, p, p);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setImageDrawable(box);
    }

    private static void load(final ImageView v, final String url) {
        v.setTag(url);
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            show(v, hit);
            return;
        }
        new Thread(() -> {
            Bitmap b = null;
            byte[] bytes = HttpIo.getBytes(url, 6000, 8000, 2 << 20, null, "image/*");
            if (bytes != null) {
                BitmapFactory.Options op = new BitmapFactory.Options();
                op.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, op);
                int sample = 1;
                while (op.outWidth / (sample * 2) >= 128 && op.outHeight / (sample * 2) >= 128) sample *= 2;
                op = new BitmapFactory.Options();
                op.inSampleSize = sample;
                b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, op);
            }
            final Bitmap fb = b;
            if (fb != null) CACHE.put(url, fb);
            v.post(() -> {
                if (fb != null && url.equals(v.getTag())) show(v, fb);
            });
        }, "order-img").start();
    }

    private static void show(ImageView v, Bitmap b) {
        v.setPadding(0, 0, 0, 0);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImageBitmap(b);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
