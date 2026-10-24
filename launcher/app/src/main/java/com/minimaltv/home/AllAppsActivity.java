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
        loadWallpaper();
        // v33: option C focus: a soft brand-colour glow behind the selected tile's icon.
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
                grid.requestFocus();
            });
        }).start();
    }

    /** v33: the home screen's current photo (Bing or bundled), light blur, with home's 0x59000000 dim. */
    private void loadWallpaper() {
        final ImageView wp = findViewById(R.id.wallpaper);
        final String file = getSharedPreferences("dock", MODE_PRIVATE).getString(MainActivity.KEY_WALLPAPER_FILE, null);
        if (wp == null || file == null) return;   // never shown home yet: stay black
        wp.setColorFilter(WALLPAPER_DIM, android.graphics.PorterDuff.Mode.SRC_ATOP);
        new Thread(() -> {
            final android.graphics.Bitmap b = Wallpapers.background(this, file, true);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && b != null) {
                    wp.setImageBitmap(b);
                    wallpaperShown = file;
                }
            });
        }).start();
    }

    static final int WALLPAPER_DIM = 0x59000000;
    String wallpaperShown;   // tests
    int glowPosition = -1;   // tests

    /** Glow drawable: radial brand colour fading to clear. */
    static android.graphics.drawable.GradientDrawable glow(int color, float radiusPx) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(color & 0x00FFFFFF) | 0x99000000, (color & 0x00FFFFFF) | 0x44000000, color & 0x00FFFFFF});
        g.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(radiusPx);
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        return g;
    }

    private void showGlow(int position) {
        glowPosition = grid.hasFocus() || position < 0 ? position : -1;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View cell = grid.getChildAt(i);
            View g = cell.findViewById(R.id.glow);
            if (g == null) continue;
            int pos = grid.getFirstVisiblePosition() + i;
            if (pos == glowPosition && glowPosition >= 0) {
                Apps.Entry e = (Apps.Entry) grid.getItemAtPosition(pos);
                g.setBackground(glow(Glyphs.glowColor(e.pkg), g.getLayoutParams().width / 2f));
                g.setVisibility(View.VISIBLE);
            } else {
                g.setVisibility(View.INVISIBLE);
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
            ImageView icon = cell.findViewById(R.id.icon);
            if (Glyphs.has(entry.pkg)) {   // v33: Arcticons / Simple Icons glyph in white, like the home row
                android.graphics.drawable.Drawable d = Glyphs.forApp(AllAppsActivity.this, entry.pkg, entry.label);
                d.setTint(0xFFFFFFFF);
                icon.setImageDrawable(d);
            } else {
                icon.setImageDrawable(Apps.mono(AllAppsActivity.this, entry.icon));
            }
            View g = cell.findViewById(R.id.glow);
            if (g != null) {
                boolean on = position == glowPosition;
                if (on) g.setBackground(glow(Glyphs.glowColor(entry.pkg), g.getLayoutParams().width / 2f));
                g.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            }
            ((TextView) cell.findViewById(R.id.label)).setText(entry.label);
            return cell;
        }
    }
}
