package com.minimaltv.home;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * One place for “what picture does this package show?”. Home tiles and the All-apps grid used to
 * each decide glyph-vs-package-icon themselves.
 */
final class AppIcons {

    private AppIcons() {
    }

    /**
     * Arcticons / Simple Icons glyph when we have one and simple icons are on; otherwise the
     * package icon (optionally greyscaled).
     */
    static Drawable picture(Context c, String pkg, String label, Drawable fallback, boolean simple) {
        if (simple) return Glyphs.forApp(c, pkg, label);
        return Apps.mono(c, fallback);
    }

    /** All apps: mapped apps get a white glyph; others stay the greyscale package icon. */
    static void bindAllApps(ImageView icon, Context c, Apps.Entry entry) {
        if (Glyphs.has(entry.pkg)) {
            Drawable d = Glyphs.forApp(c, entry.pkg, entry.label);
            d.setTint(0xFFFFFFFF);
            icon.setImageDrawable(d);
        } else {
            icon.setImageDrawable(Apps.mono(c, entry.icon));
        }
    }
}
