package com.minimaltv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Home screen: a random wallpaper, greeting, clock and date centred near the top, three frosted pills top right,
 * and a dock with the pinned apps plus one tile per connected HDMI device, the focused app's name above the row,
 * the network status (Wi-Fi name, VPN when one is up) and the All apps and Add app pills below it. Pinned apps start as the list in config.xml; the Add app pill pins more, and holding OK on a tile
 * removes it. The look follows docs/design/mockups/d-hybrid.html: every piece of chrome belongs to the TOP or
 * the DOCK region and takes that region's colour pair, light frost + dark ink on a pale photo or the reverse
 * on a dark one, as sampled at build time into assets/wallpapers/themes.json.
 */
public class MainActivity extends Activity {

    private static final float FOCUS_SCALE = 1.08f;
    private static final float PILL_FOCUS_SCALE = 1.06f;
    private static final int REQUEST_PICK = 1;
    private static final String PREFS = "dock";
    private static final String KEY_APPS = "apps";

    private static final ViewOutlineProvider ROUNDED = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            float radius = 9 * view.getResources().getDisplayMetrics().density;
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    };

    /** One colour pair: what a region's chrome is drawn with. */
    private static final class Pair {
        int ink;
        final int tint, tintFocus, edge, dotOff, dotOn;

        Pair(Resources r, boolean dark) {
            ink = r.getColor(dark ? R.color.dark_ink : R.color.light_ink, null);
            tint = r.getColor(dark ? R.color.dark_tint : R.color.light_tint, null);
            tintFocus = r.getColor(dark ? R.color.dark_tint_focus : R.color.light_tint_focus, null);
            edge = r.getColor(dark ? R.color.dark_edge : R.color.light_edge, null);
            dotOff = r.getColor(dark ? R.color.dark_dot_off : R.color.light_dot_off, null);
            dotOn = r.getColor(dark ? R.color.dark_dot_on : R.color.light_dot_on, null);
        }
    }

    private TextView greeting;
    private TextView network;
    private TextView netStatus;
    private TextView appName;
    private LinearLayout dock;
    private ImageView wallpaperView;
    private Bitmap blurred;
    private Pair top, pills, dockPair;
    private int screenW, screenH;
    private PopupWindow inputsPopup;
    private AlertDialog removeDialog;
    private boolean networkCallbackRegistered;
    private boolean inputCallbackRegistered;
    private boolean online;
    private String netLabel = "";
    private int signalLevel = -1;   // 0-4 bars on Wi-Fi, -1 for none
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable signalTick = new Runnable() {
        @Override
        public void run() {
            updateNetwork();
            ticker.postDelayed(this, 10_000);
        }
    };
    private boolean focusPlaced;

    // ---- v5 ----
    private static final String KEY_AUTO_HDMI = "auto_hdmi";
    private static final String KEY_NIGHT = "night_mode";
    private static final String KEY_SIMPLE_ICONS = "simple_icons";

    private boolean simpleIcons() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SIMPLE_ICONS, true);
    }
    private static final int NIGHT_INK = 0xFFFFE2B8;   // amber-ish off-white
    private static final int[] SLEEP_STEPS = {0, 30, 60, 90};
    private TextView weatherView, lastWatched;
    private boolean resumed;
    private boolean hdmiShown;
    private final java.util.Set<String> connectedInputs = new java.util.HashSet<>();
    private boolean themeTop, themePills, themeDock, nightApplied;
    private Weather weather;
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            Weather.fetch(w -> runOnUiThread(() -> {
                if (w != null) weather = w;
                showWeather();
            }));
            ticker.postDelayed(this, 30 * 60_000);
        }
    };
    private float density;

    // VPN state: the network handle of the current VPN (0 when none) and the country found for it.
    private long vpnHandle;
    private String vpnCountry;
    private boolean vpnLookingUp;
    private long vpnLastLookup;

    /** The clock and date are TextClocks that tick on their own; this refreshes what they do not cover. */
    private final BroadcastReceiver minuteTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    /** A VPN switching on or off changes the default network; this updates the header right away. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            refresh();
        }

        @Override
        public void onLost(Network network) {
            refresh();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            updateNetwork();   // the Wi-Fi name arrives with the capabilities
        }
    };

    /** An HDMI device plugged in, switched on, unplugged or renamed over CEC: rebuild the dock's input tiles. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            maybeAutoJump(inputId);
            rebuildIfChanged();
        }

        @Override
        public void onInputAdded(String inputId) {
            maybeAutoJump(inputId);
            rebuildIfChanged();
        }

        @Override
        public void onInputRemoved(String inputId) {
            rebuildIfChanged();
        }

        @Override
        public void onInputUpdated(String inputId) {
            rebuildIfChanged();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        greeting = findViewById(R.id.greeting);
        network = findViewById(R.id.btnNetwork);
        netStatus = findViewById(R.id.vpnStatus);
        appName = findViewById(R.id.appName);
        dock = findViewById(R.id.dock);
        wallpaperView = findViewById(R.id.wallpaper);
        weatherView = findViewById(R.id.weather);
        lastWatched = findViewById(R.id.btnLastWatched);
        findViewById(R.id.btnSettings).setOnLongClickListener(v -> {
            showLauncherSettings();
            return true;
        });
        lastWatched.setOnClickListener(v -> openLastWatched());

        findViewById(R.id.btnInputs).setOnClickListener(this::showInputs);
        network.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        findViewById(R.id.btnAdd).setOnClickListener(v -> startPicker());
        findViewById(R.id.btnAllApps).setOnClickListener(v -> startActivity(new Intent(this, AllAppsActivity.class)));

        // Until the photo is decoded, everything is drawn with the light pair on black: readable, and only
        // for a moment. The wallpaper thread then applies the real theme.
        applyTheme(false, false, false);
        buildDock();

        // The square panel gets rounded corners: pure black outside a 48px-radius rectangle, over everything.
        FrameLayout root = findViewById(R.id.root);
        root.addView(new CornerMask(this, 24 * density),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        takeWallpaperExtra(getIntent());
        loadWallpaper();

        analog = new AnalogClockView(this);
        LinearLayout lock = findViewById(R.id.lock);
        lock.addView(analog, 0, new LinearLayout.LayoutParams(dp(164), dp(164)));
        buildLayoutC();
        buildV21(root);
        takeDebugExtras(getIntent());
        applyAutoStyle(false);
        updateClockStyle();

        // Bottom vignette behind the dock (transparent -> ~35% black) so tiles stand out on bright photos.
        View vignette = new View(this);
        vignette.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x59000000, 0x26000000, 0x00000000}));
        FrameLayout.LayoutParams vl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420), Gravity.BOTTOM);
        root.addView(vignette, root.indexOfChild(findViewById(R.id.scrimDock)) + 1, vl);
    }

    private boolean wallpaperNight;
    private float wallpaperLum = 0.5f;

    /** Day (06:00-22:00) or night set, by the clock alone (independent of the night-mode text setting). */
    private static boolean nightHours() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 22 || hour < 6;
    }

    private void loadWallpaper() {
        final boolean night = nightHours();
        wallpaperNight = night;
        final String forced = prefs().getString(KEY_FORCED_WP, null);
        new Thread(() -> {
            final Wallpapers.Loaded loaded = Wallpapers.pickAndLoad(this, night, forced);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || loaded == null) return;
                blurred = loaded.blurred;
                wallpaperLum = loaded.theme.luminance;
                rowLum = rowLuminance(loaded.blurred != null ? loaded.blurred : loaded.sharp);
                wallpaperView.setImageBitmap(loaded.sharp);
                applyTheme(loaded.theme.topDark, loaded.theme.pillsDark, loaded.theme.dockDark);
                buildDock();
                if (getCurrentFocus() == null) focusFirstTile();
            });
        }).start();
    }

    // ---- look -------------------------------------------------------------------------------------

    /** Colours every piece of chrome from its region's pair and repaints the two scrims. */
    private void applyTheme(boolean topDark, boolean pillsDark, boolean dockDark) {
        themeTop = topDark;
        themePills = pillsDark;
        themeDock = dockDark;
        boolean night = isNight();
        nightApplied = night;
        Resources r = getResources();
        // Night: the photo is dimmed, so every region takes the dark pair, with warm ink instead of white.
        top = new Pair(r, topDark || night);
        pills = new Pair(r, pillsDark || night);
        dockPair = new Pair(r, dockDark || night);
        if (night) {
            top.ink = NIGHT_INK;
            pills.ink = NIGHT_INK;
            dockPair.ink = NIGHT_INK;
            // Already-dark photos (the night set) need less: ~35% instead of ~55%.
            wallpaperView.setColorFilter(wallpaperLum < 0.45f ? 0x59000000 : 0x8C000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
        } else {
            wallpaperView.clearColorFilter();
        }
        dockDark = dockDark || night;

        // On a darker photo the top text is pure white with a soft shadow; night keeps amber (with the shadow).
        if (topDark && !night) top.ink = 0xFFFFFFFF;
        if (dockDark && !night) dockPair.ink = 0xFFFFFFFF;
        float sr = (topDark || night) ? 6 * density : 0;
        for (TextView t : new TextView[]{greeting, findViewById(R.id.clock), findViewById(R.id.date), weatherView}) {
            t.setShadowLayer(sr, 0, sr > 0 ? density : 0, 0x66000000);
        }
        appName.setShadowLayer((dockDark || night) ? 6 * density : 0, 0, density, 0x66000000);
        int ink = top.ink;
        if (analog != null) analog.setColor(ink, topDark || night);
        greeting.setTextColor(ink);
        ((TextView) findViewById(R.id.clock)).setTextColor(ink);
        ((TextView) findViewById(R.id.date)).setTextColor(ink);
        weatherView.setTextColor(ink);
        if (slimLine != null) {
            float ss = (topDark || night) ? 6 * density : 0;
            for (TextView t : new TextView[]{slimClock, slimDate, slimWeather}) {
                t.setTextColor(ink);
                t.setShadowLayer(ss, 0, ss > 0 ? density : 0, 0x66000000);
            }
        }
        if (netLine != null) {
            netLine.setTextColor(ink);
            netLine.setShadowLayer((topDark || night) ? 6 * density : 0, 0, density, 0x66000000);
        }
        weatherView.setCompoundDrawableTintList(ColorStateList.valueOf(ink));

        for (int id : new int[]{R.id.btnInputs, R.id.btnNetwork, R.id.btnSettings}) {
            dressPill(findViewById(id), pills);
        }
        dressPill(findViewById(R.id.btnAdd), dockPair);
        dressPill(findViewById(R.id.btnAllApps), dockPair);
        dressPill(lastWatched, dockPair);
        if (morePill != null) dressPill(morePill, dockPair);
        showLastWatched();
        appName.setTextColor(dockPair.ink);
        drawStatus();

        // Scrims: a radial lift behind the clock block, a linear lift under the dock, each in its region's tint.
        int topTint = top.tint & 0x00FFFFFF;
        GradientDrawable radial = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x57000000 | topTint, 0x00000000 | topTint});
        radial.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        radial.setGradientCenter(0.5f, 0.36f);
        radial.setGradientRadius(screenW * 0.42f);
        findViewById(R.id.scrimTop).setBackground(radial);

        // The lift under the dock eases in over its top 55% so it never shows as a fold, and sits a little
        // stronger on a dark pair, whose light ink needs it.
        findViewById(R.id.scrimDock).setBackground(
                new LiftDrawable(dockPair.tint, dockDark ? 0x5C : 0x30, 0.65f));
    }

    /** Frost under a pill, its ink, and its focus state (fills brighter, scales a touch, one soft shadow). */
    private void dressPill(TextView pill, Pair pair) {
        FrostDrawable frost = new FrostDrawable(blurred, screenW, screenH, 14 * density, pair.tint, pair.edge, density);
        pill.setBackground(frost);
        pill.setTextColor(pair.ink);
        pill.setCompoundDrawableTintList(ColorStateList.valueOf(pair.ink));
        pill.setOutlineProvider(null);
        trackPosition(pill, frost);
        pill.setOnFocusChangeListener((v, focused) -> {
            frost.setFill(focused ? pair.tintFocus : pair.tint);
            v.animate().scaleX(focused ? PILL_FOCUS_SCALE : 1f).scaleY(focused ? PILL_FOCUS_SCALE : 1f)
                    .translationZ(focused ? 14 * density : 0f).setDuration(120).start();
        });
        if (pill.isFocused()) frost.setFill(pair.tintFocus);
    }

    /** Keeps a frost drawable pointed at the patch of blurred photo under its view. */
    private void trackPosition(View view, FrostDrawable frost) {
        ViewTreeObserver.OnPreDrawListener listener = () -> {
            int[] xy = new int[2];
            view.getLocationOnScreen(xy);
            frost.setScreenOffset(xy[0], xy[1]);
            return true;
        };
        view.getViewTreeObserver().addOnPreDrawListener(listener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        snapshotConnected();
        ticker.removeCallbacks(weatherTick);
        ticker.post(weatherTick);
        registerReceiver(minuteTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        try {
            getSystemService(ConnectivityManager.class)
                    .registerDefaultNetworkCallback(networkCallback, new Handler(Looper.getMainLooper()));
            networkCallbackRegistered = true;
        } catch (RuntimeException e) {
            // The header still refreshes on resume and every minute.
        }
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) {
                tim.registerCallback(inputCallback, new Handler(Looper.getMainLooper()));
                inputCallbackRegistered = true;
            }
        } catch (RuntimeException e) {
            // No live updates; the dock is still rebuilt on resume.
        }
        applyAutoStyle(true);
        rebuildKeepingFocus();
        refresh();
        updateRain();
        ticker.removeCallbacks(signalTick);
        ticker.postDelayed(signalTick, 10_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (rain != null) rain.setRunning(false);
        ticker.removeCallbacks(weatherTick);
        ticker.removeCallbacks(autoJumpCheck);
        unregisterReceiver(minuteTick);
        ticker.removeCallbacks(signalTick);
        if (networkCallbackRegistered) {
            networkCallbackRegistered = false;
            try {
                getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
        if (inputCallbackRegistered) {
            inputCallbackRegistered = false;
            try {
                ((TvInputManager) getSystemService(Context.TV_INPUT_SERVICE)).unregisterCallback(inputCallback);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
        if (inputsPopup != null) inputsPopup.dismiss();
        if (morePopup != null) morePopup.dismiss();
        if (removeDialog != null) removeDialog.dismiss();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The first time this window has focus (a cold start, also right after boot) begin on the first app,
        // whatever the system's own initial focus did.
        if (hasFocus && !focusPlaced) {
            focusPlaced = true;
            focusFirstTile();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (takeWallpaperExtra(intent)) loadWallpaper();
        takeDebugExtras(intent);
        // Home pressed while the launcher is already running: start over on the first app.
        focusFirstTile();
    }

    private void focusFirstTile() {
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- dock ------------------------------------------------------------------------------------

    private void buildDock() {
        dock.removeAllViews();
        PackageManager pm = getPackageManager();
        for (String pkg : pinned()) {
            try {
                addApp(pm, pkg);
            } catch (RuntimeException e) {
                // One broken app must not take the whole home screen down: leave its tile out.
            }
        }
        int apps = dock.getChildCount();
        try {
            for (HdmiDevice device : connectedHdmi()) addHdmi(device);
        } catch (RuntimeException e) {
            // No input tiles rather than no home screen.
        }
        hdmiShown = dock.getChildCount() > apps;
        showLastWatched();
        if (dock.getChildCount() == 0) {
            findViewById(R.id.btnAllApps).setFocusedByDefault(true);
            appName.setText("");
            return;
        }
        dock.setTag(apps);
        // The window picks this tile when it first takes focus, so the first dock app is selected on start.
        dock.getChildAt(0).setFocusedByDefault(true);
        View focused = getCurrentFocus();
        appName.setText(cardStyle() == STYLE_GLASS && focused != null && focused.getParent() == dock ? String.valueOf(focused.getTag(R.id.label)) : "");
    }

    /** Rebuilds the dock and puts focus back on the tile at the same place, if the focus was in the dock. */
    private String dockSignature;

    /** What the dock would show: pinned apps + connected HDMI tiles. Rebuild only when this changes. */
    private String currentSignature() {
        StringBuilder b = new StringBuilder(TextUtils.join(",", pinned()));
        try {
            for (HdmiDevice d : connectedHdmi()) b.append('|').append(d.inputId).append('=').append(d.name);
        } catch (RuntimeException ignored) {
        }
        return b.toString();
    }

    private void rebuildIfChanged() {
        String sig = currentSignature();
        if (sig.equals(dockSignature)) return;
        rebuildKeepingFocus();
    }

    private void rebuildKeepingFocus() {
        dockSignature = currentSignature();
        View focused = getCurrentFocus();
        int index = focused != null && focused.getParent() == dock ? dock.indexOfChild(focused) : -1;
        buildDock();
        if (index >= 0 && dock.getChildCount() > 0) dock.getChildAt(Math.min(index, dock.getChildCount() - 1)).requestFocus();
    }

    private void addApp(PackageManager pm, String pkg) {
        Intent launch = Apps.launchIntent(pm, pkg);
        if (launch == null) return;
        ApplicationInfo info;
        try {
            info = pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        String label = String.valueOf(info.loadLabel(pm));
        int style = cardStyle();
        if (style != STYLE_GLASS) {
            String sub = pkg.equals(prefs().getString(MediaListener.KEY_PKG, null))
                    ? prefs().getString(MediaListener.KEY_TITLE, null) : null;
            View t = simpleIcons()
                    ? styledTile(style, label, sub, Glyphs.forApp(this, pkg, label), null, true, Glyphs.brandColor(pkg))
                    : styledTile(style, label, sub, info.loadIcon(pm), info.loadIcon(pm), false, 0);
            t.setOnClickListener(v -> Apps.start(this, launch));
            t.setOnLongClickListener(v -> {
                confirmRemove(pkg, label);
                return true;
            });
            dock.addView(t);
            return;
        }
        View tile = newTile();
        tile.setTag(R.id.label, label);
        // Glass tiles (mock-1): every app gets the frosted card with its icon and name; banners are not used.
        Drawable banner = null;
        if (banner != null) {
            ImageView image = tile.findViewById(R.id.image);
            image.setImageDrawable(Apps.mono(this, banner));
            // The hairline keeps a white banner visible on a pale photo (the mockup's inset 1px at 8%).
            GradientDrawable hair = new GradientDrawable();
            hair.setCornerRadius(9 * density);
            hair.setStroke(Math.max(1, (int) density), 0x14221C17);
            tile.setForeground(hair);
        } else {
            if (simpleIcons()) showPlain(tile, Glyphs.forApp(this, pkg, label), label, true);
            else showPlain(tile, info.loadIcon(pm), label, false);
        }
        tile.setOnClickListener(v -> Apps.start(this, launch));
        tile.setOnLongClickListener(v -> {
            confirmRemove(pkg, label);
            return true;
        });
        dock.addView(tile);
    }

    // ---- pinned apps -----------------------------------------------------------------------------

    /** Package names on the dock, in order: what was saved on this TV, or the config.xml list before any change. */
    private List<String> pinned() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_APPS, null);
        List<String> list = new ArrayList<>();
        if (saved == null) {
            Collections.addAll(list, getResources().getStringArray(R.array.dock_apps));
        } else if (!saved.isEmpty()) {
            Collections.addAll(list, saved.split(","));
        }
        return list;
    }

    private void savePinned(List<String> list) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_APPS, TextUtils.join(",", list)).apply();
    }

    /** Opens the app list in pick mode, without the apps that are already on the dock. */
    private void startPicker() {
        Intent picker = new Intent(this, AllAppsActivity.class)
                .putExtra(AllAppsActivity.EXTRA_PICK, true)
                .putExtra(AllAppsActivity.EXTRA_EXCLUDE, pinned().toArray(new String[0]));
        startActivityForResult(picker, REQUEST_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) return;
        String pkg = data.getStringExtra(AllAppsActivity.EXTRA_PACKAGE);
        if (pkg == null) return;
        List<String> list = pinned();
        if (!list.contains(pkg)) {
            list.add(pkg);
            savePinned(list);
        }
        buildDock();
        // The new app is the last app tile, before any HDMI tiles: select it.
        Object apps = dock.getTag();
        int last = (apps instanceof Integer ? (Integer) apps : dock.getChildCount()) - 1;
        if (last >= 0 && last < dock.getChildCount()) dock.getChildAt(last).requestFocus();
    }

    private void confirmRemove(String pkg, String label) {
        removeDialog = new AlertDialog.Builder(this, menuTheme())
                .setTitle(label)
                .setItems(new CharSequence[]{getString(R.string.remove_from_home)}, (dialog, which) -> {
                    List<String> list = pinned();
                    list.remove(pkg);
                    savePinned(list);
                    buildDock();
                    if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
                    else findViewById(R.id.btnAllApps).requestFocus();
                })
                .create();
        dressDialog(removeDialog);
        removeDialog.show();
    }

    private View newTile() {
        View tile = getLayoutInflater().inflate(R.layout.tile, dock, false);
        if (cardStyle() == STYLE_GLASS) {
            // Layout C: square glass tiles.
            ViewGroup.MarginLayoutParams tl = (ViewGroup.MarginLayoutParams) tile.getLayoutParams();
            tl.width = dp(GLASS_SQ);
            tl.height = dp(GLASS_SQ);
            tl.setMarginEnd(dp(16));
        }
        ImageView image = tile.findViewById(R.id.image);
        image.setOutlineProvider(ROUNDED);
        image.setClipToOutline(true);
        // tvOS lift: the focused tile grows a little and gets one soft shadow, and its name appears above the row.
        tile.setOutlineProvider(ROUNDED);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(GLASS_R * density);
        ring.setStroke((int) (2 * density + 0.5f), 0xFFFFFFFF);
        tile.setOnFocusChangeListener((v, focused) -> {
            // Glass: a thin white outline while focused, over whatever foreground (hairline) the tile has.
            Object frostTag = v.getTag(R.id.wallpaper);
            if (frostTag instanceof FrostDrawable) ((FrostDrawable) frostTag).setFill(focused ? GLASS_FILL_FOCUS : GLASS_FILL);
            Object base = v.getTag(R.id.image);
            if (focused) {
                if (base == null) v.setTag(R.id.image, v.getForeground() == null ? "none" : v.getForeground());
                v.setForeground(ring);
            } else if (base != null) {
                v.setForeground(base instanceof Drawable ? (Drawable) base : null);
                v.setTag(R.id.image, null);
            }
            v.animate()
                    .scaleX(focused ? FOCUS_SCALE : 1f)
                    .scaleY(focused ? FOCUS_SCALE : 1f)
                    .translationZ(focused ? 18 * density : 0f)
                    .setDuration(140)
                    .start();
            if (focused) appName.setText(String.valueOf(v.getTag(R.id.label)));
            else if (appName.getText().toString().equals(String.valueOf(v.getTag(R.id.label)))) appName.setText("");
        });
        return tile;
    }

    /** Frosted tile with a centred icon and a name underneath, for HDMI inputs and apps that have no banner. */
    private void showPlain(View tile, Drawable icon, String label, boolean glyph) {
        ImageView image = tile.findViewById(R.id.image);
        // Glass (mock-1): heavily blurred photo + ~20% white + a 35% white hairline; brighter on focus.
        FrostDrawable frost = new FrostDrawable(blurred, screenW, screenH, GLASS_R * density, GLASS_FILL, 0x59FFFFFF, density);
        image.setBackground(frost);
        tile.setTag(R.id.wallpaper, frost);
        image.setOutlineProvider(GLASS_OUTLINE);
        tile.setOutlineProvider(GLASS_OUTLINE);
        trackPosition(image, frost);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        image.setPadding(dp(70), dp(18), dp(70), dp(40));
        // App icons: greyscale (a flat ink tint would turn full-bleed icons into blank squares). Glyphs: ink.
        image.setImageDrawable(Apps.mono(this, icon));
        image.setImageTintList(glyph ? ColorStateList.valueOf(0xFFFFFFFF) : null);
        if (cardStyle() == STYLE_GLASS) {
            int side = (dp(GLASS_SQ) - dp(76)) / 2;
            image.setPadding(side, dp(40), side, dp(GLASS_SQ - 40 - 48));   // 76x48dp icon box, name below
        } else {
            image.setPadding(dp(61), dp(16), dp(61), dp(48));   // a 70x44dp icon box
        }
        TextView text = tile.findViewById(R.id.label);
        text.setText(label);
        text.setTextColor(0xFFFFFFFF);
        text.setShadowLayer(6 * density, 0, density, 0x8C000000);
        text.setVisibility(View.VISIBLE);
    }

    // ---- HDMI inputs -----------------------------------------------------------------------------

    /** A connected HDMI port: the input to tune (the CEC device's own input when there is one) and its name. */
    private static final class HdmiDevice {
        final String inputId;
        final String name;

        HdmiDevice(String inputId, String name) {
            this.inputId = inputId;
            this.name = name;
        }
    }

    /**
     * The HDMI ports with something switched on behind them, in port order. A port counts when its own state,
     * or that of a CEC device input under it (getParentId() = the port), is INPUT_STATE_CONNECTED. Standby
     * (state 1) is what this TV reports for an empty port, so it does not count.
     */
    private List<HdmiDevice> connectedHdmi() {
        List<HdmiDevice> result = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return result;
        List<TvInputInfo> all;
        try {
            all = tim.getTvInputList();
        } catch (RuntimeException e) {
            return result;
        }
        if (all == null) return result;
        Map<String, TvInputInfo> ports = new LinkedHashMap<>();
        for (TvInputInfo info : all) {
            try {
                if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI && info.getParentId() == null) {
                    ports.put(info.getId(), info);
                }
            } catch (RuntimeException ignored) {
                // Skip an input that cannot be read.
            }
        }
        List<TvInputInfo> sorted = new ArrayList<>(ports.values());
        Collections.sort(sorted, (a, b) ->
                String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this))));
        for (TvInputInfo port : sorted) {
            try {
                boolean connected = state(tim, port.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
                TvInputInfo device = null;
                for (TvInputInfo info : all) {
                    if (!port.getId().equals(info.getParentId())) continue;
                    int s = state(tim, info.getId());
                    if (s == TvInputManager.INPUT_STATE_CONNECTED) {
                        device = info;
                        connected = true;
                        break;
                    }
                    if (device == null && s != TvInputManager.INPUT_STATE_DISCONNECTED) device = info;
                }
                if (!connected) continue;
                String portLabel = portLabel(port);
                String name = device != null ? deviceName(device) : null;
                String key = KEY_HDMI_NAME_PREFIX + port.getId();
                android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (name != null && !name.isEmpty() && !name.equalsIgnoreCase(portLabel)) {
                    // Remember it: the CEC child input vanishes when the device idles, the port stays connected.
                    if (!name.equals(prefs.getString(key, null))) prefs.edit().putString(key, name).apply();
                } else {
                    // No CEC child or name right now: use the last name seen on this port (same device, usually).
                    name = prefs.getString(key, null);
                    if (name == null || name.isEmpty()) name = portLabel;
                }
                result.add(new HdmiDevice(device != null ? device.getId() : port.getId(), name));
            } catch (RuntimeException ignored) {
                // Leave this port out.
            }
        }
        return result;
    }

    private static int state(TvInputManager tim, String id) {
        try {
            return tim.getInputState(id);
        } catch (RuntimeException e) {
            return TvInputManager.INPUT_STATE_DISCONNECTED;
        }
    }

    /** "HDMI 1": the port's label, or one built from its number. */
    private String portLabel(TvInputInfo port) {
        String label = String.valueOf(port.loadLabel(this));
        if (label.toUpperCase(Locale.ROOT).contains("HDMI")) return label;
        return label.isEmpty() ? getString(R.string.hdmi_n, 1) : "HDMI " + label;
    }

    /** Generic names the framework makes up for a CEC device ("playback_1", "HDMI 2"), not worth showing. */
    private static final java.util.regex.Pattern GENERIC_NAME = java.util.regex.Pattern.compile(
            "^(playback|recorder|tuner|audio|hdmi)[_ ]?\\d*$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final String KEY_HDMI_NAME_PREFIX = "hdmi_name_";
    private static final int VENDOR_NINTENDO = 0x0009BF;

    /**
     * The device's name: the user's custom label, then the CEC input's own label, then the CEC OSD name,
     * skipping generic ones; a Nintendo vendor id gives "Nintendo Switch"; otherwise null (the port label).
     */
    private String deviceName(TvInputInfo device) {
        String name = null;
        try {
            name = usable(device.loadCustomLabel(this));
        } catch (RuntimeException ignored) {
        }
        if (name == null) {
            try {
                name = usable(device.loadLabel(this));
            } catch (RuntimeException ignored) {
            }
        }
        Object hdmi = null;
        try {
            // getHdmiDeviceInfo() is a system API; read it reflectively so a missing method is not a crash.
            hdmi = TvInputInfo.class.getMethod("getHdmiDeviceInfo").invoke(device);
        } catch (Exception | LinkageError ignored) {
        }
        if (name == null && hdmi != null) {
            try {
                Object n = hdmi.getClass().getMethod("getDisplayName").invoke(hdmi);
                name = usable(n == null ? null : n.toString());
            } catch (Exception | LinkageError ignored) {
            }
        }
        if (name == null && hdmi != null) {
            try {
                Object v = hdmi.getClass().getMethod("getVendorId").invoke(hdmi);
                if (v instanceof Integer && (Integer) v == VENDOR_NINTENDO) return "Nintendo Switch";
            } catch (Exception | LinkageError ignored) {
            }
        }
        return name;
    }

    /** The trimmed, CamelCase-split name, or null when it is empty or generic. */
    private static String usable(CharSequence raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        if (s.isEmpty() || GENERIC_NAME.matcher(s).matches()) return null;
        return s.replaceAll("(?<=\\p{Ll})(?=\\p{Lu})", " ");
    }

    private void addHdmi(HdmiDevice device) {
        String lower = device.name.toLowerCase(Locale.ROOT);
        boolean console = lower.contains("switch") || lower.contains("nintendo");
        int style = cardStyle();
        if (style != STYLE_GLASS) {
            int res = console ? R.drawable.ic_game_console : R.drawable.ic_hdmi;
            View t = styledTile(style, device.name, null, getDrawable(res), null, true, 0xFFFFFFFF);
            t.setOnClickListener(v -> switchTo(device.inputId));
            dock.addView(t);
            return;
        }
        View tile = newTile();
        tile.setTag(R.id.label, device.name);
        showPlain(tile, getDrawable(console ? R.drawable.ic_game_console : R.drawable.ic_hdmi), device.name, true);
        tile.setOnClickListener(v -> switchTo(device.inputId));
        dock.addView(tile);
    }

    /** Tunes the TV app to a passthrough input; if nothing takes the intent, opens the TV app itself. */
    private void switchTo(String inputId) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
            return;
        } catch (RuntimeException e) {
            // Try the TV app below.
        }
        try {
            Intent tv = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(tv);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- header ----------------------------------------------------------------------------------

    private void refresh() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int greetingRes;
        if (hour < 5) greetingRes = R.string.good_night;
        else if (hour < 12) greetingRes = R.string.good_morning;
        else if (hour < 17) greetingRes = R.string.good_afternoon;
        else if (hour < 22) greetingRes = R.string.good_evening;
        else greetingRes = R.string.good_night;
        greeting.setText(greetingRes);
        if (applyAutoStyle(true)) rebuildKeepingFocus();   // crossed midnight with auto-switch on
        updateNetwork();
        if (nightHours() != wallpaperNight) {
            prefs().edit().remove(KEY_FORCED_WP).apply();   // a forced photo lasts until the next rotation
            loadWallpaper();
        }   // crossed 06:00 or 22:00: switch sets now
        if (isNight() != nightApplied) {
            applyTheme(themeTop, themePills, themeDock);
            buildDock();
        }
        showLastWatched();
    }

    /** Reads the connections once: sets the network pill and the VPN chip. A VPN is a network of its own. */
    private void updateNetwork() {
        boolean wifi = false;
        boolean ethernet = false;
        String ssid = null;
        int rssi = Integer.MIN_VALUE;
        Network vpn = null;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = n;
                } else if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    boolean isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    wifi |= isWifi;
                    if (isWifi && ssid == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Object info = caps.getTransportInfo();
                        if (info instanceof WifiInfo) {
                            ssid = cleanSsid(((WifiInfo) info).getSSID());
                            rssi = ((WifiInfo) info).getRssi();
                        }
                        if (rssi == Integer.MIN_VALUE || rssi <= -127) rssi = caps.getSignalStrength();
                    }
                    ethernet |= caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                }
            }
        } catch (RuntimeException ignored) {
            // Show whatever was found so far.
        }
        String label = ethernet ? getString(R.string.ethernet) : wifi ? wifiName() : getString(R.string.offline);
        // Full name; only very long ones are shortened (the pill also ellipsizes at its max width).
        if (label.length() > 24) label = label.substring(0, 23).trim() + "…";
        network.setText(label);
        // The dock status: the full name, green dot when connected.
        online = wifi || ethernet;
        if (wifi && !ethernet) {
            if (ssid == null) ssid = cleanSsid(wifiSsid());
            netLabel = ssid != null ? ssid : getString(R.string.wifi_connected);
            if (rssi == Integer.MIN_VALUE || rssi <= -127 || rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) rssi = wifiRssi();
            signalLevel = rssi <= -127 ? -1 : Math.max(0, Math.min(4, WifiManager.calculateSignalLevel(rssi, 5)));
        } else {
            netLabel = getString(ethernet ? R.string.ethernet : R.string.no_wifi);
            signalLevel = -1;
        }
        showVpn(vpn);
    }

    /** The Wi-Fi network name when it can be read, otherwise the plain label. */
    private String wifiName() {
        String ssid = cleanSsid(wifiSsid());
        return ssid != null ? ssid : getString(R.string.wifi);
    }

    private String wifiSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            return info == null ? null : info.getSSID();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The current Wi-Fi RSSI in dBm, or -127 when unknown. */
    private int wifiRssi() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            return info == null ? -127 : info.getRssi();
        } catch (RuntimeException e) {
            return -127;
        }
    }

    /** Strips the quotes; null for an empty or "<unknown ssid>" name (no location permission, or none yet). */
    private static String cleanSsid(String ssid) {
        if (ssid == null) return null;
        ssid = ssid.replace("\"", "").trim();
        if (ssid.isEmpty() || ssid.contains("unknown ssid") || ssid.equals(WifiManager.UNKNOWN_SSID)) return null;
        return ssid;
    }

    // ---- VPN -------------------------------------------------------------------------------------

    private void showVpn(Network vpn) {
        if (vpn == null) {
            vpnHandle = 0;
            vpnCountry = null;
            drawStatus();
            return;
        }
        long handle = vpn.getNetworkHandle();
        long now = SystemClock.elapsedRealtime();
        boolean newConnection = handle != vpnHandle;
        if (newConnection) {
            vpnHandle = handle;
            vpnCountry = null;
        }
        // One lookup per VPN connection, and a retry at most once a minute if no service answered.
        if (vpnCountry == null && (newConnection || (!vpnLookingUp && now - vpnLastLookup > 60_000))) {
            lookUpVpnCountry(handle, now);
        }
        drawStatus();
    }

    private void lookUpVpnCountry(long handle, long now) {
        vpnLookingUp = true;
        vpnLastLookup = now;
        VpnCountry.lookup(country -> runOnUiThread(() -> {
            if (handle != vpnHandle) return;   // the VPN changed while we were asking
            vpnLookingUp = false;
            vpnCountry = country;
            drawStatus();
        }));
    }

    /** "Home WiFi" with a green dot, "Home WiFi · VPN · Germany" while a VPN is up, "No Wi-Fi" with a grey one. */
    private void drawStatus() {
        String text = netLabel.isEmpty() ? getString(R.string.no_wifi) : netLabel;
        if (vpnHandle != 0) {
            String vpn;
            if (vpnCountry != null) vpn = getString(R.string.vpn_country, vpnCountry);
            else vpn = getString(vpnLookingUp ? R.string.vpn_checking : R.string.vpn_on);
            text = getString(R.string.with_vpn, text, vpn);
        }
        netStatus.setText(text);
        if (netLine != null) netLine.setText(text);
        if (dockPair == null) return;
        netStatus.setTextColor(dockPair.ink);
        // Tinted one by one: the dot is green or grey, the signal bars take the ink.
        Drawable dot = getDrawable(R.drawable.ic_dot).mutate();
        dot.setBounds(0, 0, dot.getIntrinsicWidth(), dot.getIntrinsicHeight());
        dot.setTint(online ? dockPair.dotOn : dockPair.dotOff);
        Drawable bars = null;
        if (signalLevel >= 0) {
            bars = getDrawable(R.drawable.ic_signal).mutate();
            bars.setLevel(signalLevel);
            bars.setBounds(0, 0, bars.getIntrinsicWidth(), bars.getIntrinsicHeight());
            bars.setTint(dockPair.ink);
        }
        netStatus.setCompoundDrawableTintList(null);
        netStatus.setCompoundDrawables(dot, null, bars, null);
    }

    private void open(Intent intent) {
        Apps.start(this, intent);
    }

    /** Dropdown under the Inputs pill: the HDMI and AV inputs the TV reports. */
    private void showInputs(View anchor) {
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) inputs.add(info);
            }
        }
        if (inputs.isEmpty()) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        // HDMI ports first, then AV and the rest, each group A to Z.
        Collections.sort(inputs, (a, b) -> {
            boolean ha = a.getType() == TvInputInfo.TYPE_HDMI;
            boolean hb = b.getType() == TvInputInfo.TYPE_HDMI;
            if (ha != hb) return ha ? -1 : 1;
            return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
        });

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setBackgroundResource(R.drawable.popup_bg);
        rows.setPadding(dp(8), dp(8), dp(8), dp(8));
        PopupWindow popup = new PopupWindow(rows, dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        for (TvInputInfo info : inputs) {
            TextView row = (TextView) getLayoutInflater().inflate(R.layout.input_row, rows, false);
            row.setTextColor(getColorStateList(isNight() ? R.color.menu_row_text_night : R.color.menu_row_text));
            String label = String.valueOf(info.loadLabel(this));
            boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
            row.setText(hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI") ? "HDMI " + label : label);
            String id = info.getId();
            row.setOnClickListener(v -> {
                popup.dismiss();
                open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                        TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel"));
            });
            rows.addView(row);
        }
        inputsPopup = popup;
        showNear(popup, rows, anchor, dp(220), false);
        rows.getChildAt(0).requestFocus();
    }

    // ---- v5: auto-jump to HDMI ---------------------------------------------------------------------

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** HDMI inputs (ports and CEC children) that are connected right now. */
    private java.util.Set<String> connectedHdmiInputs() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return ids;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI
                        && state(tim, info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) ids.add(info.getId());
            }
        } catch (RuntimeException ignored) {
        }
        return ids;
    }

    private void snapshotConnected() {
        connectedInputs.clear();
        connectedInputs.addAll(connectedHdmiInputs());
    }

    private String pendingJump;
    private final Runnable autoJumpCheck = () -> {
        String id = pendingJump;
        pendingJump = null;
        if (id == null || !resumed || !prefs().getBoolean(KEY_AUTO_HDMI, true)) return;
        if (!connectedHdmiInputs().contains(id)) return;   // flickered off again
        switchTo(id);
    };

    /** An input that newly turned connected while home is in front: tune to it after a short settle. */
    private void maybeAutoJump(String inputId) {
        java.util.Set<String> now = connectedHdmiInputs();
        boolean fresh = now.contains(inputId) && !connectedInputs.contains(inputId);
        connectedInputs.clear();
        connectedInputs.addAll(now);
        if (!fresh || !resumed || !prefs().getBoolean(KEY_AUTO_HDMI, true)) return;
        // Prefer the CEC child: if a port came up, give its device input a moment to appear and use that.
        pendingJump = inputId;
        ticker.removeCallbacks(autoJumpCheck);
        ticker.postDelayed(autoJumpCheck, 1500);
    }

    // ---- v5: settings ----------------------------------------------------------------------------

    /** Long-press on Settings: the launcher's own switches, plus the way to the system settings. */
    private void showLauncherSettings() {
        String[] items = {getString(R.string.pref_auto_hdmi), getString(R.string.pref_night), getString(R.string.pref_simple_icons),
                getString(R.string.pref_auto_style)};
        boolean[] checked = {prefs().getBoolean(KEY_AUTO_HDMI, true), prefs().getBoolean(KEY_NIGHT, true), simpleIcons(), autoStyle()};
        AlertDialog dialog = new AlertDialog.Builder(this, menuTheme())
                .setTitle(R.string.launcher_settings)
                .setMultiChoiceItems(items, checked, (d, which, on) -> {
                    if (which == 3) {
                        setAutoStyle(on);
                        return;
                    }
                    prefs().edit().putBoolean(which == 0 ? KEY_AUTO_HDMI : which == 1 ? KEY_NIGHT : KEY_SIMPLE_ICONS, on).apply();
                    if (which == 2) {
                        buildDock();
                        showLastWatched();
                    }
                    if (which == 1) {
                        applyTheme(themeTop, themePills, themeDock);
                        buildDock();
                    }
                })
                .setPositiveButton(R.string.system_settings, (d, w) -> open(new Intent(Settings.ACTION_SETTINGS)))
                .setNeutralButton(R.string.card_style, (d, w) -> showCardStyle())
                .create();
        dressDialog(dialog);
        dialog.show();
    }

    private void showCardStyle() {
        String[] names = getResources().getStringArray(R.array.card_styles);
        AlertDialog dialog = new AlertDialog.Builder(this, menuTheme())
                .setTitle(R.string.card_style)
                .setSingleChoiceItems(names, cardStyle(), (d, which) -> {
                    d.dismiss();
                    chooseStyle(which);
                })
                .setNeutralButton(getString(autoStyle() ? R.string.auto_style_on : R.string.auto_style_off),
                        (d, w) -> setAutoStyle(!autoStyle()))
                .create();
        dressDialog(dialog);
        dialog.show();
    }

    // ---- v5: night mode --------------------------------------------------------------------------

    private boolean isNight() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_NIGHT, true)) return false;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 22 || hour < 6;
    }

    // ---- v5: weather -----------------------------------------------------------------------------

    private void showWeather() {
        updateSlim();
        updateRain();
        if (weather == null) {
            weatherView.setVisibility(View.GONE);
            return;
        }
        weatherView.setText(weather.temp + "\u00B0 " + weather.word());
        Drawable icon = getDrawable(weather.icon());
        weatherView.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
        weatherView.setCompoundDrawableTintList(ColorStateList.valueOf(top != null ? top.ink : Color.WHITE));
        weatherView.setVisibility(cardStyle() == STYLE_GLASS ? View.VISIBLE : View.GONE);
    }

    // ---- v5: last watched ------------------------------------------------------------------------

    private void showLastWatched() {
        if (lastWatched == null) return;
        // Pick up a session that is playing right now, if the listener grant is there.
        try {
            android.media.session.MediaSessionManager msm = getSystemService(android.media.session.MediaSessionManager.class);
            MediaListener.record(this, msm.getActiveSessions(new android.content.ComponentName(this, MediaListener.class)));
        } catch (RuntimeException ignored) {
            // No grant: rely on what was saved.
        }
        String title = prefs().getString(MediaListener.KEY_TITLE, null);
        String pkg = prefs().getString(MediaListener.KEY_PKG, null);
        if (hdmiShown || pkg == null) {
            if (lastWatched.hasFocus()) findViewById(R.id.btnAllApps).requestFocus();
            lastWatched.setVisibility(View.GONE);
            return;
        }
        PackageManager pm = getPackageManager();
        String label;
        Drawable icon;
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            label = String.valueOf(info.loadLabel(pm));
            icon = simpleIcons() ? Glyphs.forApp(this, pkg, label) : Apps.mono(this, info.loadIcon(pm));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            lastWatched.setVisibility(View.GONE);
            return;
        }
        icon = icon.mutate();
        // Glyphs live in a 1.6:1 box; real icons are square.
        if (simpleIcons()) icon.setBounds(0, 0, dp(29), dp(18));
        else icon.setBounds(0, 0, dp(16), dp(16));
        if (simpleIcons()) icon.setTint(dockPair != null ? dockPair.ink : 0xFFFFFFFF);
        lastWatched.setCompoundDrawableTintList(null);   // tint set on the drawable itself
        lastWatched.setCompoundDrawables(icon, null, null, null);
        lastWatched.setText(title != null ? "Last used \u00B7 " + title + " \u00B7 " + label : "Last used \u00B7 " + label);
        lastWatched.setVisibility(View.VISIBLE);
    }

    private void openLastWatched() {
        String pkg = prefs().getString(MediaListener.KEY_PKG, null);
        if (pkg == null) return;
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) launch = Apps.launchIntent(pm, pkg);
        if (launch == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        Apps.start(this, launch);
    }

    private int menuTheme() {
        return isNight() ? R.style.MenuDialog_Night : R.style.MenuDialog;
    }

    /** Rows get the menu selector; the button text stays readable on the dark card. */
    private void dressDialog(AlertDialog dialog) {
        dialog.setOnShowListener(d -> {
            android.widget.ListView list = dialog.getListView();
            if (list != null) {
                list.setSelector(R.drawable.menu_row_bg);
                list.setDrawSelectorOnTop(false);
            }
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL}) {
            android.widget.Button b = dialog.getButton(which);
            if (b != null) {
                b.setTextColor(getColorStateList(isNight() ? R.color.menu_row_text_night : R.color.menu_row_text));
                b.setBackgroundResource(R.drawable.menu_row_bg);
            }
            }
        });
    }

    // ---- v10: dock styles -------------------------------------------------------------------------

    private static final float GLASS_R = 18;
    private static final int GLASS_FILL = 0x38FFFFFF, GLASS_FILL_FOCUS = 0x61FFFFFF;
    private static final ViewOutlineProvider GLASS_OUTLINE = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), GLASS_R * view.getResources().getDisplayMetrics().density);
            outline.setAlpha(0.55f);
        }
    };
    static final String EXTRA_WALLPAPER = "wallpaper";
    private static final String KEY_FORCED_WP = "forced_wallpaper";

    /** Debug: am start ... --es wallpaper 16.jpg forces that photo until the next day/night rotation. */
    private boolean takeWallpaperExtra(Intent intent) {
        String wp = intent == null ? null : intent.getStringExtra(EXTRA_WALLPAPER);
        if (wp == null || wp.isEmpty()) return false;
        if (!wp.endsWith(".jpg")) wp = wp + ".jpg";
        prefs().edit().putString(KEY_FORCED_WP, wp).apply();
        return true;
    }

    static final int STYLE_GLASS = 0, STYLE_FLOATING = 1, STYLE_POSTERS = 2;
    private static final String KEY_CARD_STYLE = "card_style";

    private AnalogClockView analog;

    // ---- v20: layout C (Glass style only) ---------------------------------------------------------
    private static final int GLASS_SQ = 150;   // square tile side, dp
    private TextView netLine, morePill;
    private FrameLayout.LayoutParams lockDefault, dockDefault;
    private int lockGravityDefault;
    private FrameLayout.LayoutParams rowDefault;
    private int rowFrameTop;
    private PopupWindow morePopup;

    /** Creates the views layout C adds (Wi-Fi line under the weather, the ⋯ pill) and remembers the defaults. */
    private void buildLayoutC() {
        LinearLayout lock = findViewById(R.id.lock);
        View block = findViewById(R.id.dockBlock);
        lockDefault = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) lock.getLayoutParams());
        dockDefault = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) block.getLayoutParams());
        lockGravityDefault = lock.getGravity();

        netLine = new TextView(this);
        netLine.setTextSize(15);
        netLine.setAlpha(0.8f);
        netLine.setSingleLine(true);
        netLine.setEllipsize(TextUtils.TruncateAt.END);
        netLine.setText(netStatus.getText());
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = dp(4);
        lock.addView(netLine, nl);

        ViewGroup row = (ViewGroup) lastWatched.getParent();
        morePill = (TextView) getLayoutInflater().inflate(R.layout.more_pill, row, false);
        row.addView(morePill, row.indexOfChild(lastWatched) + 1);
        morePill.setOnClickListener(this::showMore);
        morePill.setOnLongClickListener(v -> {
            showLauncherSettings();
            return true;
        });
    }

    /** Glass = layout C: clock block left, square tiles right, Last used + ⋯ under them. Others: the classic layout. */
    private void applyLayout() {
        if (lockDefault == null) return;
        boolean c = cardStyle() == STYLE_GLASS;
        boolean pa = cardStyle() == STYLE_POSTERS;   // v22: poster layout A
        LinearLayout lock = findViewById(R.id.lock);
        View block = findViewById(R.id.dockBlock);
        View row = (View) lastWatched.getParent();
        View rowFrame = (View) row.getParent();
        if (rowDefault == null) {
            rowDefault = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) row.getLayoutParams());
            rowFrameTop = ((ViewGroup.MarginLayoutParams) rowFrame.getLayoutParams()).topMargin;
        }
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(rowDefault);
        if (pa) rl.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        row.setLayoutParams(rl);
        // Posters end 150 + 240 = 390dp down; the pills sit 45dp above the bottom (540dp screen): 77dp gap.
        ((ViewGroup.MarginLayoutParams) rowFrame.getLayoutParams()).topMargin = pa ? dp(77) : rowFrameTop;
        rowFrame.requestLayout();
        if (pa) {
            FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            l.setMarginEnd(dp(55));
            l.topMargin = dp(40);
            lock.setLayoutParams(l);
            lock.setGravity(Gravity.END);
            FrameLayout.LayoutParams d = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            d.setMarginStart(dp(55));
            d.topMargin = dp(150);
            block.setLayoutParams(d);
        } else if (c) {
            FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
            l.setMarginStart(dp(60));
            lock.setLayoutParams(l);
            lock.setGravity(Gravity.START);
            FrameLayout.LayoutParams d = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
            d.setMarginEnd(dp(56));
            d.topMargin = dp(40);
            block.setLayoutParams(d);
        } else {
            lock.setLayoutParams(new FrameLayout.LayoutParams(lockDefault));
            lock.setGravity(lockGravityDefault);
            block.setLayoutParams(new FrameLayout.LayoutParams(dockDefault));
        }
        int classic = c || pa ? View.GONE : View.VISIBLE;
        findViewById(R.id.pills).setVisibility(classic);
        findViewById(R.id.btnAllApps).setVisibility(classic);
        findViewById(R.id.btnAdd).setVisibility(classic);
        netStatus.setVisibility(classic);
        appName.setVisibility(c || pa ? View.GONE : View.VISIBLE);
        netLine.setVisibility(c ? View.VISIBLE : View.GONE);
        morePill.setVisibility(c || pa ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams al = (LinearLayout.LayoutParams) analog.getLayoutParams();
        al.width = al.height = dp(c ? 210 : 164);
        analog.requestLayout();
    }

    /** The ⋯ menu: All apps, Add app, Inputs, Wi-Fi, Settings (long-press Settings or ⋯: launcher settings). */
    private void showMore(View anchor) {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setBackgroundResource(R.drawable.popup_bg);
        rows.setPadding(dp(8), dp(8), dp(8), dp(8));
        PopupWindow popup = new PopupWindow(rows, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        String[] labels = {getString(R.string.all_apps), getString(R.string.add_app), getString(R.string.inputs),
                getString(R.string.wifi), getString(R.string.settings)};
        int[] icons = {R.drawable.ic_apps_small, R.drawable.ic_add, R.drawable.ic_hdmi, R.drawable.ic_wifi, R.drawable.ic_app_settings};
        Runnable[] actions = {
                () -> startActivity(new Intent(this, AllAppsActivity.class)),
                this::startPicker,
                () -> showInputs(anchor),
                () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)),
                () -> open(new Intent(Settings.ACTION_SETTINGS))};
        ColorStateList text = getColorStateList(isNight() ? R.color.menu_row_text_night : R.color.menu_row_text);
        for (int i = 0; i < labels.length; i++) {
            TextView row = (TextView) getLayoutInflater().inflate(R.layout.input_row, rows, false);
            row.setText(labels[i]);
            row.setTextColor(text);
            Drawable ic = getDrawable(icons[i]).mutate();
            ic.setBounds(0, 0, dp(i == 4 ? 29 : 18), dp(18));
            row.setCompoundDrawables(ic, null, null, null);
            row.setCompoundDrawablePadding(dp(12));
            row.setCompoundDrawableTintList(text);
            Runnable action = actions[i];
            row.setOnClickListener(v -> {
                popup.dismiss();
                action.run();
            });
            if (i == 4) row.setOnLongClickListener(v -> {
                popup.dismiss();
                showLauncherSettings();
                return true;
            });
            rows.addView(row);
        }
        morePopup = popup;
        showNear(popup, rows, anchor, dp(240), !(cardStyle() == STYLE_POSTERS));
        rows.getChildAt(0).requestFocus();
    }

    /**
     * v23: shows a popup next to its anchor, below it when it fits, otherwise above it, kept on screen.
     * showAsDropDown with a WRAP_CONTENT height cannot tell that a menu under a pill near the bottom edge
     * (poster layout A) runs off the screen, so the ⋯ menu was drawn out of sight.
     */
    private void showNear(PopupWindow popup, View content, View anchor, int width, boolean alignEnd) {
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST));
        int h = content.getMeasuredHeight();
        int[] xy = new int[2];
        anchor.getLocationOnScreen(xy);
        int x = alignEnd ? xy[0] + anchor.getWidth() - width : xy[0];
        x = Math.max(dp(16), Math.min(x, screenW - width - dp(16)));
        int below = xy[1] + anchor.getHeight() + dp(10);
        int y = below + h <= screenH - dp(16) ? below : xy[1] - dp(10) - h;
        y = Math.max(dp(16), y);
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
    }

    /** Luminance (0-1) of the wallpaper behind the icon row; recomputed whenever the wallpaper changes. */
    private float rowLum = 0.5f;
    /** Background darker than this counts as dark (white glyph), otherwise light (black glyph). */
    private static final float ROW_DARK_BELOW = 0.5f;

    /** Average luminance of the bitmap's lower-middle region (x 20-80%, y 55-95%), sampled on a grid. */
    private static float rowLuminance(Bitmap b) {
        if (b == null) return 0.5f;
        int w = b.getWidth(), h = b.getHeight();
        double sum = 0;
        int n = 0;
        for (int y = (int) (h * 0.55f); y < h * 0.95f; y += Math.max(1, h / 60)) {
            for (int x = (int) (w * 0.2f); x < w * 0.8f; x += Math.max(1, w / 80)) {
                sum += lum(b.getPixel(x, y));
                n++;
            }
        }
        return n == 0 ? 0.5f : (float) (sum / n);
    }

    private static float lum(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    /**
     * The brand colour as is, unless it is near-achromatic (HSV saturation < 0.15) and near black or white
     * (luminance < 0.15 or > 0.85), like Apple TV's black: those become white on a dark row, black on a light one.
     * Chromatic brand colours (Netflix red, Prime blue, ...) are never swapped.
     */
    private int focusColour(int brand) {
        float[] hsv = new float[3];
        Color.colorToHSV(brand, hsv);
        float l = lum(brand);
        boolean neutralExtreme = hsv[1] < 0.15f && (l < 0.15f || l > 0.85f);
        if (!neutralExtreme) return brand;
        return rowLum < ROW_DARK_BELOW ? 0xFFFFFFFF : 0xFF000000;
    }

    /** Glass shows the analog clock (greeting, date and weather below it); the other styles the digital one. */
    private void updateClockStyle() {
        if (analog == null) return;
        applyLayout();
        boolean glass = cardStyle() == STYLE_GLASS;
        analog.setVisibility(glass ? View.VISIBLE : View.GONE);
        boolean posters = cardStyle() == STYLE_POSTERS;
        boolean slim = cardStyle() == STYLE_FLOATING || posters;
        if (slimClock != null) {
            // Floating: one line "h:mm | Fri 23 Oct · ☂ 21°". Posters (layout A): bigger time, then date + weather.
            ((android.widget.TextClock) slimClock).setFormat12Hour(posters ? "h:mm" : "h:mm  '|'  EEE d MMM");
            ((android.widget.TextClock) slimClock).setFormat24Hour(posters ? "HH:mm" : "HH:mm  '|'  EEE d MMM");
            slimClock.setTextSize(posters ? 32 : 22);
            slimDate.setVisibility(posters ? View.VISIBLE : View.GONE);
            slimLine.setGravity(posters ? Gravity.TOP : Gravity.CENTER_VERTICAL);   // TOP keeps baselines aligned
            slimDate.setTextSize(17);
            slimWeather.setTextSize(posters ? 17 : 22);
            slimDate.setAlpha(0.85f);
            slimWeather.setAlpha(posters ? 0.85f : 1f);
        }
        findViewById(R.id.clock).setVisibility(glass || slim ? View.GONE : View.VISIBLE);
        // v21: Floating icons swap the clock block for one slim line.
        greeting.setVisibility(slim ? View.GONE : View.VISIBLE);
        findViewById(R.id.date).setVisibility(slim ? View.GONE : View.VISIBLE);
        if (slimLine != null) slimLine.setVisibility(slim ? View.VISIBLE : View.GONE);
        showWeather();
        ((LinearLayout.LayoutParams) greeting.getLayoutParams()).topMargin = glass ? dp(10) : 0;
        greeting.requestLayout();
    }

    // ---- v21: slim clock line, rain, poster tilt, daily auto-switch, debug extras -----------------

    static final String EXTRA_FORCE_RAIN = "forceRain";
    static final String EXTRA_STYLE = "style";
    static final String EXTRA_OPEN_MORE = "openMore";
    private static final String KEY_AUTO_STYLE = "auto_style";
    private static final String KEY_AUTO_OFFSET = "auto_style_offset";
    private LinearLayout slimLine;
    private TextView slimClock, slimDate, slimWeather;
    private RainView rain;
    private boolean forceRain;
    private int lastPosterIndex = -1;

    private void buildV21(FrameLayout root) {
        LinearLayout lock = findViewById(R.id.lock);
        slimLine = new LinearLayout(this);
        slimLine.setOrientation(LinearLayout.HORIZONTAL);
        slimLine.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.TextClock clock = new android.widget.TextClock(this);
        clock.setFormat12Hour("h:mm  '|'  EEE d MMM");
        clock.setFormat24Hour("HH:mm  '|'  EEE d MMM");
        slimClock = clock;
        android.widget.TextClock date = new android.widget.TextClock(this);
        date.setFormat12Hour("'   'EEE d MMM");
        date.setFormat24Hour("'   'EEE d MMM");
        slimDate = date;
        slimWeather = new TextView(this);
        for (TextView t : new TextView[]{slimClock, slimDate, slimWeather}) {
            t.setTextSize(22);
            t.setSingleLine(true);
            t.setIncludeFontPadding(false);
            t.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            t.setTextColor(top != null ? top.ink : Color.WHITE);
            slimLine.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        slimLine.setVisibility(View.GONE);
        lock.addView(slimLine, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Rain sits over the wallpaper and scrims, under all chrome.
        rain = new RainView(this);
        rain.setVisibility(View.GONE);
        root.addView(rain, root.indexOfChild(findViewById(R.id.scrimDock)) + 1,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** "  ·  ☂ 21°" after the time and date, or nothing until the weather is known. */
    private void updateSlim() {
        if (slimWeather == null) return;
        slimWeather.setText(weather == null ? "" : "  \u00B7  " + weather.symbol() + " " + weather.temp + "\u00B0");
    }

    /** Floating icons only: rain overlay while it rains (or forceRain), running only while we are resumed. */
    private void updateRain() {
        if (rain == null) return;
        boolean wet = forceRain || (weather != null && Weather.rainy(weather.code));
        boolean on = wet && cardStyle() == STYLE_FLOATING;
        rain.setRaining(on);
        rain.setRunning(on && resumed);
    }

    private boolean autoStyle() {
        return prefs().getBoolean(KEY_AUTO_STYLE, false);
    }

    /** Local calendar days since 1970, so the rotation steps exactly at local midnight and across new year. */
    private static int dayIndex() {
        Calendar c = Calendar.getInstance();
        long local = c.getTimeInMillis() + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET);
        return (int) Math.floorDiv(local, 86_400_000L);
    }

    /** Glass -> Floating -> Posters, one step per day; the offset pins today's style to the chosen one. */
    private int autoStyleToday() {
        return Math.floorMod(dayIndex() + prefs().getInt(KEY_AUTO_OFFSET, 0), 3);
    }

    private void pinToday(int style) {
        prefs().edit().putInt(KEY_AUTO_OFFSET, Math.floorMod(style - dayIndex(), 3)).apply();
    }

    private void setAutoStyle(boolean on) {
        if (on) pinToday(cardStyle());   // switching on never changes today's look
        prefs().edit().putBoolean(KEY_AUTO_STYLE, on).apply();
        Toast.makeText(this, on ? R.string.auto_style_on : R.string.auto_style_off, Toast.LENGTH_SHORT).show();
    }

    /** With auto-switch on, applies today's style if it is not the current one. Returns whether it changed. */
    private boolean applyAutoStyle(boolean rebuild) {
        if (!autoStyle()) return false;
        int today = autoStyleToday();
        if (today == cardStyle()) return false;
        prefs().edit().putInt(KEY_CARD_STYLE, today).apply();
        if (rebuild) {
            updateClockStyle();
            buildDock();
        }
        return true;
    }

    /** A style picked by hand (menu or debug extra); with auto-switch on it becomes today's style. */
    private void chooseStyle(int style) {
        prefs().edit().putInt(KEY_CARD_STYLE, style).apply();
        if (autoStyle()) pinToday(style);
        lastPosterIndex = -1;
        updateClockStyle();
        buildDock();
        focusFirstTile();
    }

    /** Debug: am start -n com.minimaltv.home/.MainActivity --ez forceRain true --es style glass|floating|posters */
    private void takeDebugExtras(Intent intent) {
        if (intent == null) return;
        if (intent.hasExtra(EXTRA_FORCE_RAIN)) {
            forceRain = intent.getBooleanExtra(EXTRA_FORCE_RAIN, false);
            updateRain();
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_MORE, false)) {
            intent.removeExtra(EXTRA_OPEN_MORE);
            ticker.postDelayed(() -> {
                if (!isFinishing() && morePill != null && morePill.getVisibility() == View.VISIBLE
                        && morePill.isAttachedToWindow() && morePill.getWindowToken() != null) {
                    morePill.requestFocus();
                    showMore(morePill);
                }
            }, 800);
        }
        String st = intent.getStringExtra(EXTRA_STYLE);
        if (st != null) {
            st = st.trim().toLowerCase(Locale.ROOT);
            int style = st.startsWith("glass") ? STYLE_GLASS : st.startsWith("float") ? STYLE_FLOATING
                    : st.startsWith("poster") ? STYLE_POSTERS : -1;
            if (style >= 0 && analog != null) chooseStyle(style);
        }
    }

    private int cardStyle() {
        return prefs().getInt(KEY_CARD_STYLE, STYLE_GLASS);
    }

    private static android.graphics.ColorMatrixColorFilter greyFilter() {
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        m.setSaturation(0);
        return new android.graphics.ColorMatrixColorFilter(m);
    }

    /**
     * A dock item for the floating and poster styles. icon: the greyscale (or ink-tinted, for HDMI glyphs)
     * picture; colour: a separate copy shown in full colour on focus (floating only), null for none.
     */
    private View styledTile(int style, String label, String subtitle, Drawable icon, Drawable colour, boolean glyph, int brand) {
        return style == STYLE_FLOATING ? floatingTile(label, icon, colour, glyph, brand) : posterTile(label, subtitle, icon, glyph);
    }

    private View floatingTile(String label, Drawable icon, Drawable colour, boolean glyph, int brand) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setClipChildren(false);
        tile.setTag(R.id.label, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(192));
        lp.setMarginEnd(dp(24));
        tile.setLayoutParams(lp);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        boolean grey = getResources().getBoolean(R.bool.icons_greyscale);
        Drawable shown = icon.mutate();
        if (glyph) shown.setTint(dockPair.ink);
        else if (grey) shown.setColorFilter(greyFilter());
        image.setImageDrawable(shown);
        image.setAlpha(0.82f);
        image.setElevation(dp(6));
        image.setOutlineProvider(null);
        // v21: icon, a soft brand-colour halo behind it and a faint mirrored reflection under it, in one box
        // tall enough that the reflection (which ends 40dp below the focused icon) never reaches the name.
        FrameLayout box = new FrameLayout(this);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(124));
        bp.topMargin = dp(40);
        tile.addView(box, bp);
        int haloColour = brand != 0 ? focusColour(brand) : dockPair.ink;
        GradientDrawable haloShape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(haloColour & 0x00FFFFFF) | 0x59000000, (haloColour & 0x00FFFFFF) | 0x1F000000, haloColour & 0x00FFFFFF});
        haloShape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        haloShape.setGradientRadius(dp(105));
        haloShape.setShape(GradientDrawable.OVAL);
        View halo = new View(this);
        halo.setBackground(haloShape);
        halo.setAlpha(0f);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(230), dp(150), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        hp.topMargin = dp(35) - dp(8) - dp(75);
        box.addView(halo, hp);
        ReflectionView reflection = new ReflectionView(this, image, dp(112) * 1.55f, dp(70) * 1.55f);
        reflection.setAlpha(0f);
        box.addView(reflection, new FrameLayout.LayoutParams(dp(180), dp(40), Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        box.addView(image, new FrameLayout.LayoutParams(dp(112), dp(70), Gravity.CENTER_HORIZONTAL | Gravity.TOP));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(dockPair.ink);
        name.setTextSize(15);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setAlpha(0f);
        name.setShadowLayer(8, 0, 2, 0x80000000);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(4);
        tile.addView(name, np);

        tile.setOnFocusChangeListener((v, focused) -> {
            // Full colour while focused (only the icon swaps; no relayout), grey again after.
            if (colour != null) image.setImageDrawable(focused ? colour : shown);
            if (glyph && brand != 0) {
                // Brand colour on focus, the dock ink otherwise; cross-fade the tint over 150 ms.
                Object old = image.getTag();
                if (old instanceof android.animation.Animator) ((android.animation.Animator) old).cancel();
                int from = image.getTag(R.id.label) instanceof Integer ? (Integer) image.getTag(R.id.label) : dockPair.ink;
                int to = focused ? focusColour(brand) : dockPair.ink;
                android.animation.ValueAnimator ta = android.animation.ValueAnimator.ofArgb(from, to);
                ta.setDuration(150);
                ta.addUpdateListener(an -> {
                    int cval = (int) an.getAnimatedValue();
                    shown.setTint(cval);
                    image.setTag(R.id.label, cval);
                    reflection.invalidate();
                });
                image.setTag(ta);
                ta.start();
            }
            image.animate().scaleX(focused ? 1.55f : 1f).scaleY(focused ? 1.55f : 1f).alpha(focused ? 1f : 0.82f)
                    .translationY(focused ? -dp(8) : 0).setDuration(150).start();
            name.animate().alpha(focused ? 1f : 0f).setDuration(150).start();
            // Reflection: starts 4dp under the focused picture's bottom edge (icon centre 35dp, lifted 8dp).
            reflection.setTranslationY(dp(35) - dp(8) + reflection.pictureHeight() / 2f + dp(4));
            reflection.invalidate();
            reflection.animate().alpha(focused ? 1f : 0f).setDuration(focused ? 220 : 120).start();
            halo.animate().alpha(focused ? 1f : 0f).scaleX(focused ? 1f : 0.7f).scaleY(focused ? 1f : 0.7f)
                    .setDuration(focused ? 220 : 120).start();
            appName.setText("");
        });
        return tile;
    }

    private View posterTile(String label, String subtitle, Drawable icon, boolean glyph) {
        final int narrow = dp(80), wide = dp(280), height = dp(240);   // v22: layout A sizes
        PosterFrame tile = new PosterFrame(this, 11 * density);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setTag(R.id.label, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(narrow, height);
        lp.setMarginEnd(dp(13));
        tile.setLayoutParams(lp);
        int tint = (dockPair.tint & 0x00FFFFFF) | 0xA3000000;
        FrostDrawable frost = new FrostDrawable(blurred, screenW, screenH, 11 * density, tint, dockPair.edge, density);
        tile.setBackground(frost);
        trackPosition(tile, frost);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(11 * density);
        ring.setStroke(Math.max(2, (int) (1.5f * density)), 0xE6FFFFFF);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 11 * density);
            }
        });

        ImageView image = new ImageView(this);
        Drawable shown = icon.mutate();
        if (glyph) shown.setTint(dockPair.ink);
        else shown = Apps.mono(this, shown);
        image.setImageDrawable(shown);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // Pinned to the left (not centred) so widening the card does not drag it; everything clips to the card.
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(64), dp(40), Gravity.START | Gravity.TOP);
        ip.leftMargin = (narrow - dp(64)) / 2;
        ip.topMargin = dp(24);
        tile.setClipToOutline(true);
        tile.setClipChildren(true);
        tile.addView(image, ip);

        // Narrow: the name runs bottom-to-top along the card.
        TextView vertical = new TextView(this);
        vertical.setText(label);
        vertical.setTextColor(dockPair.ink);
        vertical.setTextSize(13);
        vertical.setAlpha(0.85f);
        vertical.setSingleLine(true);
        vertical.setEllipsize(TextUtils.TruncateAt.END);
        vertical.setGravity(Gravity.CENTER_VERTICAL);
        vertical.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(dp(96), dp(24), Gravity.BOTTOM | Gravity.START);
        tile.addView(vertical, vp);
        vertical.setPivotX(0);
        vertical.setPivotY(0);
        vertical.setRotation(-90);
        vertical.setTranslationX(narrow / 2f - dp(13));
        vertical.setTranslationY(dp(-14) + dp(24));

        // Wide: name + subtitle bottom-left, icon top-right.
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView big = new TextView(this);
        big.setText(label);
        big.setTextColor(dockPair.ink);
        big.setTextSize(22);
        big.setSingleLine(true);
        big.setEllipsize(TextUtils.TruncateAt.END);
        big.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        text.addView(big);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextColor(dockPair.ink);
            sub.setAlpha(0.75f);
            sub.setTextSize(12);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(sub);
        }
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(wide - dp(36), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        tp.leftMargin = dp(18);
        tp.bottomMargin = dp(16);
        text.setAlpha(0f);
        tile.addView(text, tp);

        // v23: the glare is painted by PosterFrame itself (on top of the content, clipped to the card). The
        // camera sits close enough (v21 used 8000*density, which flattened a 4 degree tilt to nothing).
        tile.setCameraDistance(2200 * density);
        tile.setOnFocusChangeListener((v, focused) -> {
            appName.setText("");
            // v21: tilt a few degrees toward the direction focus came from (parallax), flat when unfocused.
            int idx = dock.indexOfChild(v);
            if (focused) {
                float dir = lastPosterIndex < 0 || idx >= lastPosterIndex ? 1f : -1f;
                lastPosterIndex = idx;
                // Hold ~9 degrees toward the direction focus moved, plus 3 degrees back.
                tile.animate().rotationY(-9f * dir).rotationX(3f).translationZ(18 * density)
                        .setStartDelay(0).setDuration(260).start();
                tile.sweep(120, 700);
            } else {
                tile.animate().rotationY(0f).rotationX(0f).translationZ(0f).setStartDelay(0).setDuration(200).start();
                tile.stopGlare();
            }
            v.setForeground(focused ? ring : null);
            int from = v.getLayoutParams().width, to = focused ? wide : narrow;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(from, to);
            a.setDuration(170);
            a.addUpdateListener(an -> {
                v.getLayoutParams().width = (int) an.getAnimatedValue();
                v.requestLayout();
            });
            a.start();
            vertical.animate().alpha(focused ? 0f : 0.85f).setDuration(120).start();
            text.animate().alpha(focused ? 1f : 0f).setStartDelay(focused ? 80 : 0).setDuration(140).start();
            // Icon slides to the top-right of the wide card.
            // Ends inside the wide card's top-right corner (16dp in), scaled a little; never past the edge.
            float s15 = 1.15f;
            float right = wide - dp(16) - dp(64) * (1 + s15) / 2f - (narrow - dp(64)) / 2f;
            image.animate().translationX(focused ? right : 0).translationY(focused ? dp(3) : 0)
                    .scaleX(focused ? s15 : 1f).scaleY(focused ? s15 : 1f).setDuration(170).start();
        });
        return tile;
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }
}
