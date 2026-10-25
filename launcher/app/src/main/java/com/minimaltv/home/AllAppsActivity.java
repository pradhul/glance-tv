package com.minimaltv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every installed app in one grid, A to Z. Back returns to the home screen.
 * In pick mode (EXTRA_PICK) choosing an app returns its package name instead of starting it, and apps
 * listed in EXTRA_EXCLUDE (already on the home screen) are left out. v29: apps the user hid (Apps.hidden) are left
 * out of both; long-press (or Menu) on a tile offers "Hide from All apps".
 */
public class AllAppsActivity extends Activity {

    static final String EXTRA_PICK = "pick";
    static final String EXTRA_EXCLUDE = "exclude";
    static final String EXTRA_PACKAGE = "package";

    private GridView grid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_apps);
        final boolean pick = getIntent().getBooleanExtra(EXTRA_PICK, false);
        final String[] excluded = getIntent().getStringArrayExtra(EXTRA_EXCLUDE);
        final Set<String> exclude = new HashSet<>();
        if (excluded != null) exclude.addAll(Arrays.asList(excluded));
        if (pick) ((TextView) findViewById(R.id.title)).setText(R.string.add_to_home);

        grid = findViewById(R.id.grid);
        // GridView must own D-pad: items are not focusable, children never steal keys.
        grid.setFocusable(true);
        grid.setFocusableInTouchMode(true);
        grid.setClipChildren(true);
        loadWallpaper();
        bindOrders();
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                showGlow(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                showGlow(-1);
            }
        });
        grid.setOnFocusChangeListener((v, has) -> showGlow(has ? grid.getSelectedItemPosition() : -1));
        grid.setOnItemClickListener((parent, view, position, id) -> {
            Apps.Entry entry = (Apps.Entry) parent.getItemAtPosition(position);
            if (pick) {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_PACKAGE, entry.pkg));
                finish();
            } else {
                Apps.start(this, entry.launch);
            }
        });

        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmHide((Apps.Entry) parent.getItemAtPosition(position));
            return true;
        });

        // Loading labels and icons is the slow part; keep it off the main thread so the screen opens at once.
        final PackageManager pm = getPackageManager();
        final String self = getPackageName();
        new Thread(() -> {
            final List<Apps.Entry> entries = Apps.list(pm, self);
            final Set<String> hidden = Apps.hidden(this);
            entries.removeIf(e -> exclude.contains(e.pkg) || hidden.contains(e.pkg));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                grid.setAdapter(new EntryAdapter(entries));
                if (entries.size() > 0) grid.setSelection(0);
                grid.requestFocus();
            });
        }, "all-apps").start();
    }

    /** v38: the home screen's current photo (Bing or bundled), heavily blurred under a darker scrim; fades in. */
    private void loadWallpaper() {
        final ImageView wp = findViewById(R.id.wallpaper);
        final String file = getSharedPreferences(Looks.PREFS, MODE_PRIVATE)
                .getString(MainActivity.KEY_WALLPAPER_FILE, null);
        if (wp == null || file == null) return;   // never shown home yet: stay black
        wp.setColorFilter(WALLPAPER_SCRIM, android.graphics.PorterDuff.Mode.SRC_ATOP);
        wp.setAlpha(0f);
        new Thread(() -> {
            final android.graphics.Bitmap b = Wallpapers.heavyBlur(this, file);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && b != null) {
                    wp.setImageBitmap(b);
                    wp.animate().alpha(1f).setDuration(Looks.WALLPAPER_FADE_MS).start();
                    wallpaperShown = file;
                }
            });
        }, "all-apps-wp").start();
    }

    /** v40: orders strip from the same cached orders JSON the home screen uses (only when a URL is set). */
    private void bindOrders() {
        android.widget.LinearLayout strip = findViewById(R.id.orders_strip);
        View column = findViewById(R.id.orders_column);
        if (strip == null || column == null) return;
        android.content.SharedPreferences p = getSharedPreferences(Looks.PREFS, MODE_PRIVATE);
        String json = Orders.url(p.getString(Orders.KEY_URL, Orders.DEFAULT_URL)).isEmpty() ? null : Orders.cached(p);
        List<Orders.Order> cards = Orders.cards(Orders.parse(json), Orders.today(), Orders.now());
        OrdersStrip.bind(strip, cards);
        boolean show = !cards.isEmpty();
        column.setVisibility(show ? View.VISIBLE : View.GONE);
        grid.setNumColumns(show ? 5 : 6);
        final View scroll = findViewById(R.id.orders_scroll);
        final TextView header = findViewById(R.id.orders_header);
        scroll.setOnFocusChangeListener((v, has) -> header.setTextColor(has ? 0xFF8AB4FF : getColor(R.color.text)));
    }

    /**
     * v42: RIGHT on the grid's last column (or last app) moves focus to the orders column, where UP/DOWN scroll it;
     * LEFT (or Back) returns to the grid.
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        View column = findViewById(R.id.orders_column);
        View scroll = findViewById(R.id.orders_scroll);
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && column != null && column.getVisibility() == View.VISIBLE) {
            int k = e.getKeyCode();
            if (k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && grid.hasFocus() && grid.getAdapter() != null) {
                int pos = grid.getSelectedItemPosition();
                int cols = grid.getNumColumns();
                if (pos >= 0 && (pos % cols == cols - 1 || pos == grid.getAdapter().getCount() - 1)) {
                    scroll.requestFocus();
                    return true;
                }
            }
            if ((k == android.view.KeyEvent.KEYCODE_DPAD_LEFT || k == android.view.KeyEvent.KEYCODE_BACK) && scroll.hasFocus()) {
                grid.requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    static final int WALLPAPER_SCRIM = Looks.ALL_APPS_SCRIM;
    String wallpaperShown;   // tests
    int glowPosition = -1;   // tests

    /** Glow drawable: radial brand colour fading to clear. */
    static android.graphics.drawable.GradientDrawable glow(int color, float radiusPx) {
        return FocusGlow.drawable(color, radiusPx);
    }

    static final long GLOW_IN_MS = Looks.GLOW_IN_MS, GLOW_OUT_MS = Looks.GLOW_OUT_MS;

    private void fadeIn(View g, int pos, String pkg) {
        g.setTag(R.id.tag_glow_pos, pos);
        FocusGlow.fadeIn(g, Glyphs.glowColor(pkg), g.getLayoutParams().width / 2f);
    }

    private void showGlow(int position) {
        glowPosition = grid.hasFocus() || position < 0 ? position : -1;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View cell = grid.getChildAt(i);
            View g = cell.findViewById(R.id.glow);
            if (g == null) continue;
            int pos = grid.getFirstVisiblePosition() + i;
            if (pos == glowPosition && glowPosition >= 0) {
                if (Integer.valueOf(pos).equals(g.getTag(R.id.tag_glow_pos))) continue;
                fadeIn(g, pos, ((Apps.Entry) grid.getItemAtPosition(pos)).pkg);
            } else if (g.getTag(R.id.tag_glow_pos) != null) {
                g.setTag(R.id.tag_glow_pos, null);
                FocusGlow.fadeOut(g);
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU && grid.getSelectedItem() instanceof Apps.Entry) {
            confirmHide((Apps.Entry) grid.getSelectedItem());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    AlertDialog hideDialog;

    /** v29: the tile options: hide this app from the grid (undo from ⋯ → Hidden apps on the home screen). */
    private void confirmHide(Apps.Entry entry) {
        hideDialog = new AlertDialog.Builder(this, R.style.MenuDialog)
                .setTitle(entry.label)
                .setItems(new CharSequence[]{getString(R.string.hide_app)}, (d, which) -> {
                    Apps.setHidden(this, entry.pkg, true);
                    EntryAdapter a = (EntryAdapter) grid.getAdapter();
                    if (a != null) {
                        a.entries.remove(entry);
                        a.notifyDataSetChanged();
                    }
                    android.widget.Toast.makeText(this, getString(R.string.app_hidden, entry.label),
                            android.widget.Toast.LENGTH_SHORT).show();
                    grid.requestFocus();
                })
                .create();
        hideDialog.setOnShowListener(d -> {
            if (hideDialog.getListView() != null) hideDialog.getListView().setSelector(R.drawable.menu_row_bg);
        });
        hideDialog.show();
    }

    private final class EntryAdapter extends BaseAdapter {
        private final List<Apps.Entry> entries;

        EntryAdapter(List<Apps.Entry> entries) {
            this.entries = entries;
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View cell = convertView != null
                    ? convertView
                    : getLayoutInflater().inflate(R.layout.item_app, parent, false);
            Apps.Entry entry = entries.get(position);
            AppIcons.bindAllApps(cell.findViewById(R.id.icon), AllAppsActivity.this, entry);
            View g = cell.findViewById(R.id.glow);
            if (g != null) {
                boolean on = position == glowPosition;
                boolean was = Integer.valueOf(position).equals(g.getTag(R.id.tag_glow_pos));
                boolean sameApp = Integer.valueOf(position).equals(cell.getTag(R.id.tag_cell_pos));
                cell.setTag(R.id.tag_cell_pos, position);
                if (on && !was) {
                    fadeIn(g, position, entry.pkg);
                } else if (!on && sameApp) {
                    // leave it: a fade-out may be running
                } else if (!on && g.getTag(R.id.tag_glow_pos) != null) {
                    FocusGlow.hideNow(g);
                } else if (!on) {
                    g.setVisibility(View.INVISIBLE);
                }
            }
            ((TextView) cell.findViewById(R.id.label)).setText(entry.label);
            return cell;
        }
    }
}
