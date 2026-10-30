package com.cosyhome.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cosy Home: a clock over a valley that follows the sun, a dock of six apps with the rest of the apps
 * following under it on one page (Down scrolls them up), and top-right Sources, Wi-Fi and Settings pills.
 */
public class HomeActivity extends Activity implements SceneView.Listener {

    private static final long MINUTE_MS = 60_000L;
    private static final String PREFS = "cosy", ORDER_KEY = "tile_order", HIDDEN_KEY = "hidden_by_remote",
            LAST_KEY = "last_launched";
    private static final int GRID_COLUMNS = 6;
    /** Tiles fill the width between these margins, 16:9, the same size in the dock and the grid. */
    private static final int SIDE_DP = 92, COL_GAP_DP = 16, ROW_GAP_DP = 30;
    /** Dock tiles rest at this opacity until focused, as in the mockup. */
    private static final float DOCK_REST_ALPHA = 0.62f;
    /** OLED care: the clock layer moves through these dp offsets, one step a minute. */
    private static final int[][] SHIFT_DP = {{0, 0}, {3, 2}, {5, -1}, {2, -4}, {-2, -3}, {-4, 1}, {-1, 4}};

    private SceneView scene;
    private View homeLayer, sources;
    private RoomyScrollView page;
    private TextView clock, date, weather;
    private LinearLayout dock, inputs;
    private GridLayout grid;
    private GlassPanel inputsPanel, tileMenu;
    private LinearLayout tileMenuItems;
    private View root, catTarget;
    private ImageView cat;
    /** "koh" in cosy.properties: the statue and its text replace the cat, without the jump's flip. */
    private boolean mascotMode;
    private ValueAnimator catJump;

    private List<String> dockApps, pinnedApps;
    private Set<String> hiddenApps;
    private String liveTvPkg, usbPkg, place;
    private double lat, lon;
    private boolean located;
    private float density;

    private boolean inputsOpen;
    /** Every tile in order: the first dockCount are the dock, the rest the grid under it. */
    private List<Apps.App> tiles = new ArrayList<>();
    private int dockCount;
    /** The tile picked up to be moved (index in tiles), or -1. */
    private int moving = -1;
    private boolean resumed;
    private boolean showInputs;
    private boolean appsDirty = true;   // the first resume builds the tiles
    /** Survives the activity being recreated while an app is open; resets only with the process. */
    private static int lastDock = 0;
    /** Package of the last app opened from a tile: Home comes back with the cat on it. Kept in prefs too. */
    private String lastLaunched = "";
    /** Preview a phase from adb: `am start -n com.cosyhome.tv/.HomeActivity --es phase day` ("auto" clears it). */
    private static SceneView.Phase forcedPhase;
    private int shiftStep;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            tick();
            shiftClockLayer();
            // Land just after the next minute boundary so the clock never shows the old minute.
            long now = System.currentTimeMillis();
            handler.postDelayed(this, MINUTE_MS - now % MINUTE_MS + 200);
        }
    };
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            Weather.fetch(HomeActivity.this, lat, lon, text -> showWeather(text));
            handler.postDelayed(this, Weather.REFRESH_MS);
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED then ADDED; wait for the new APK instead of dropping the tile.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            if (!resumed) {
                appsDirty = true;   // rebuild on the next resume, not behind an app
                return;
            }
            loadApps();
            restoreFocus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        density = getResources().getDisplayMetrics().density;

        // Built from cosy.defaults.properties and cosy.properties, see app/build.gradle.
        dockApps = list(R.string.dock);
        pinnedApps = list(R.string.pinned);
        hiddenApps = new HashSet<>(list(R.string.hidden));
        lastLaunched = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_KEY, "");
        liveTvPkg = getString(R.string.live_tv_package);
        usbPkg = getString(R.string.usb_package);
        place = getString(R.string.location_name);
        try {
            lat = Double.parseDouble(getString(R.string.location_latitude));
            lon = Double.parseDouble(getString(R.string.location_longitude));
            located = true;
        } catch (NumberFormatException e) {
            located = false;   // no location set: fixed phase times and no weather
        }

        scene = findViewById(R.id.scene);
        page = findViewById(R.id.page);
        homeLayer = findViewById(R.id.homeLayer);
        // The clock screen is exactly the first screen, so the dock sits at its bottom and the grid starts below.
        homeLayer.getLayoutParams().height = getResources().getDisplayMetrics().heightPixels;
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        weather = findViewById(R.id.weather);
        dock = findViewById(R.id.dock);
        inputs = findViewById(R.id.inputs);
        grid = findViewById(R.id.appsGrid);
        inputsPanel = findViewById(R.id.inputsPanel);
        tileMenu = findViewById(R.id.tileMenu);
        tileMenuItems = findViewById(R.id.tileMenuItems);
        sources = findViewById(R.id.sources);
        showInputs = Boolean.parseBoolean(getString(R.string.show_inputs));
        if (!showInputs) sources.setVisibility(View.GONE);   // a streaming stick has no inputs
        root = findViewById(R.id.root);
        cat = findViewById(R.id.cat);
        mascotMode = "koh".equals(getString(R.string.mascot));
        if (mascotMode) {
            ViewGroup.LayoutParams lp = cat.getLayoutParams();
            lp.width = dp(44);
            lp.height = dp(80);
            cat.setLayoutParams(lp);
            cat.setImageResource(R.drawable.koh_mascot);
        }
        // Keep the cat on its tile while the tile zooms, the grid scrolls or the clock layer drifts.
        root.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (catJump == null && catTarget != null) pinCat();
            return true;
        });
        scene.setListener(this);

        View wifi = findViewById(R.id.wifi), store = findViewById(R.id.store),
                settings = findViewById(R.id.settings);
        sources.setOnClickListener(v -> openInputs());
        wifi.setOnClickListener(v -> open(wifiIntent()));
        store.setOnClickListener(v -> openStore());
        settings.setOnClickListener(v -> open(settingsIntent()));
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SOUND_EFFECTS_ENABLED), false, uiSounds);
        // The pills are the top of the screen: Up goes nowhere, and the row's ends do not fall through.
        for (View pill : new View[]{sources, wifi, store, settings}) {
            pill.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    navSound(keyCode);
                    focusDock(lastDock);
                    return true;
                }
                // Left and right move between the pills: play the click, then let the focus search do the move.
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v != firstPill()) {
                    navSound(keyCode);
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v != settings) {
                    navSound(keyCode);
                    return false;
                }
                return keyCode == KeyEvent.KEYCODE_DPAD_UP
                        || (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v == firstPill())
                        || (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == settings);
            });
            pill.setOnFocusChangeListener((v, focused) -> {
                if (focused) page.smoothScrollTo(0, 0);
            });
        }

        weather.setText(weatherLine(Weather.cached(this)));
        readPhaseOverride(getIntent());
        registerPackageReceiver();
    }

    private static void readPhaseOverride(Intent intent) {
        String p = intent == null ? null : intent.getStringExtra("phase");
        if (p == null) return;
        try {
            forcedPhase = SceneView.Phase.valueOf(p.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            forcedPhase = null;   // "auto" or anything else: follow the sun again
        }
    }

    /** Registered for the activity's whole life, so an install while an app is in front still marks the tiles stale. */
    private void registerPackageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        getContentResolver().unregisterContentObserver(uiSounds);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        restoreUiSounds();
        // Rebuilding the tiles reloads every banner: only when an app was installed or removed meanwhile.
        if (appsDirty) {
            appsDirty = false;
            catTarget = null;   // new tiles: the cat sits on the focused one instead of jumping there
            loadApps();
        }
        if (showInputs) buildInputs();
        minuteTick.run();
        if (located) handler.postDelayed(weatherTick, Weather.stale(this) ? 0 : Weather.REFRESH_MS);
        scene.onHome();   // repaint the still if a memory trim released it
        restoreFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        endMove();
        resumed = false;
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(weatherTick);
    }

    /**
     * Keep the scene while an app is in front, so Home comes back without redrawing it (Amazon's home shows meanwhile
     * on Fire OS). Give its 8 MB back only when Android runs short of memory.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE) scene.release();
    }

    /** Home pressed while already home: back to the app last opened, or the first dock app. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readPhaseOverride(intent);
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        lastDock = 0;
        closeTileMenu(false);
        endMove();
        closeInputs();
        scene.onHome();   // Home pressed while already home: repaint the still if a trim released it
        if (!focusLastLaunched()) {
            focusDock(0);
            page.smoothScrollTo(0, 0);
        }
    }

    @Override
    public void onBackPressed() {
        if (menuFor >= 0) {
            closeTileMenu(true);
        } else if (moving >= 0) {
            endMove();
        } else if (inputsOpen) {
            closeInputs();
            sources.requestFocus();
        } else if (dock.getChildCount() > 0 && !dock.hasFocus()) {
            focusDock(lastDock);   // from the grid or the pills, back to the dock and the clock
        }
        // On the dock Back goes nowhere: this is home.
    }

    // ---- scene, clock and weather -----------------------------------------------------------------

    @Override
    public void onSceneChanged() {
        SceneView.Phase p = scene.phase();
        int glassTint = p == SceneView.Phase.DAWN ? 0x571E1A3C
                : p == SceneView.Phase.DAY ? 0x6B0E222E
                : p == SceneView.Phase.DUSK ? 0x5C1E1028 : 0x6B0A0E1E;
        GlassPanel.setSource(scene.blurred(), glassTint);
        inputsPanel.invalidate();
        int text = p == SceneView.Phase.DAY ? 0xFF12222C
                : p == SceneView.Phase.DAWN ? 0xFFFFF8F0
                : p == SceneView.Phase.DUSK ? 0xFFFFF4EA : 0xFFEEF0F8;
        clock.setTextColor(text);
        date.setTextColor(text);
        weather.setTextColor((text & 0x00FFFFFF) | 0xD1000000);
    }

    private void tick() {
        Date now = new Date();
        Locale locale = Locale.FRANCE;   // the stick's system language is English; the home screen is French
        String hm = new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", locale).format(now);
        clock.setText(hm);
        String day = new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(now);
        date.setText(day.substring(0, 1).toUpperCase(locale) + day.substring(1));   // "vendredi" -> "Vendredi"
        scene.setPhase(forcedPhase != null ? forcedPhase : phaseAt(now.getTime()));
    }

    /**
     * Dawn and dusk straddle the real sunrise and sunset for the configured place, or 07:00 and
     * 19:00 when no place is set.
     */
    private SceneView.Phase phaseAt(long now) {
        long rise, set;
        if (located) {
            long[] sun = Sun.riseSet(now, lat, lon);
            rise = sun[0];
            set = sun[1];
        } else {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.set(Calendar.HOUR_OF_DAY, 7);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            rise = c.getTimeInMillis();
            set = rise + 12 * 60 * MINUTE_MS;
        }
        if (now >= rise - 40 * MINUTE_MS && now < rise + 50 * MINUTE_MS) return SceneView.Phase.DAWN;
        if (now >= rise + 50 * MINUTE_MS && now < set - 50 * MINUTE_MS) return SceneView.Phase.DAY;
        if (now >= set - 50 * MINUTE_MS && now < set + 40 * MINUTE_MS) return SceneView.Phase.DUSK;
        return SceneView.Phase.NIGHT;
    }

    private void showWeather(String text) {
        weather.setText(weatherLine(text));
    }

    private String weatherLine(String reading) {
        if (!located || reading.isEmpty()) return place;
        return place.isEmpty() ? reading : place + " · " + reading;
    }

    private void shiftClockLayer() {
        shiftStep = (shiftStep + 1) % SHIFT_DP.length;
        homeLayer.animate()
                .translationX(SHIFT_DP[shiftStep][0] * density)
                .translationY(SHIFT_DP[shiftStep][1] * density)
                .setDuration(2000).start();
    }

    // ---- tiles ------------------------------------------------------------------------------------

    /** Reads the apps (banners included), in the order the remote last left them; then builds the tiles. */
    private void loadApps() {
        Set<String> hide = new HashSet<>(hiddenApps);
        hide.addAll(hiddenByRemote());
        // The dock and the row below it are on screen at start: decode those banners, the rest on first draw.
        List<Apps.App> loaded = Apps.load(this, dockApps, pinnedApps, hide, dockApps.size() + GRID_COLUMNS);
        // Six tiles on the first row: the dock, then the next apps in order, so the row lines up with the grid.
        dockCount = Math.min(loaded.size(), GRID_COLUMNS);
        // A saved order wins; apps it does not know (new installs) keep the default order after the known ones.
        List<String> saved = list(getSharedPreferences(PREFS, MODE_PRIVATE).getString(ORDER_KEY, ""));
        List<Apps.App> sorted = new ArrayList<>(loaded);
        if (!saved.isEmpty()) {
            Collections.sort(sorted, (a, b) -> Integer.compare(rank(saved, a.pkg), rank(saved, b.pkg)));
        }
        tiles = sorted;
        buildTiles();
    }

    private static int rank(List<String> saved, String pkg) {
        int i = saved.indexOf(pkg);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Lays the tiles out from {@link #tiles} without reloading anything, so moving a tile stays instant. */
    private void buildTiles() {
        dock.removeAllViews();
        grid.removeAllViews();

        // Six tiles across the width between the side margins, 16:9, in the dock and the grid alike.
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int side = dp(SIDE_DP), colGap = dp(COL_GAP_DP), rowGap = dp(ROW_GAP_DP);
        int tileW = (screenW - 2 * side - (GRID_COLUMNS - 1) * colGap) / GRID_COLUMNS;
        int tileH = tileW * 9 / 16;

        // The rest follow under the dock, the same gap away as one row from the next (it leaves the cat room to
        // sit): the grid starts up into the dock's bottom margin, so the top of the next row peeks at the bottom.
        android.widget.FrameLayout.LayoutParams dlp = (android.widget.FrameLayout.LayoutParams) dock.getLayoutParams();
        dlp.setMarginStart(side);
        LinearLayout.LayoutParams glp = (LinearLayout.LayoutParams) grid.getLayoutParams();
        glp.setMarginStart(side);
        glp.topMargin = -dlp.bottomMargin;
        grid.setPadding(0, rowGap, 0, grid.getPaddingBottom());

        for (int g = 0; g < tiles.size(); g++) {
            final int index = g;
            boolean inDock = g < dockCount;
            Tile t = new Tile(this, tiles.get(g), inDock ? DOCK_REST_ALPHA : 1f);
            t.setOnTileFocused(tile -> {
                if (index < dockCount) {
                    lastDock = index;
                    page.smoothScrollTo(0, 0);   // the dock lives on the clock screen: show all of it
                }
                moveCat(tile);
            });
            t.setOnTileHeld(() -> showTileMenu(index));
            t.setOnLaunch(() -> rememberLaunched(t.app.pkg));
            t.setOnKeyListener((v, keyCode, e) -> e.getAction() == KeyEvent.ACTION_DOWN && onTileKey(index, keyCode, e));
            if (inDock) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
                if (g > 0) lp.setMarginStart(colGap);
                dock.addView(t, lp);
            } else {
                int i = g - dockCount;
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = tileW;
                lp.height = tileH;
                lp.setMargins(0, 0, i % GRID_COLUMNS == GRID_COLUMNS - 1 ? 0 : colGap, rowGap);
                grid.addView(t, lp);
            }
        }

        // The deferred banners are decoded once the tiles are up, off the main thread: the grid opens
        // without decoding on its first frame, and the tiles are ready before anyone scrolls to them.
        grid.post(() -> {
            List<Apps.LazyBanner> pending = new ArrayList<>();
            for (Apps.App a : tiles) {
                if (a.banner instanceof Apps.LazyBanner) pending.add((Apps.LazyBanner) a.banner);
            }
            if (pending.isEmpty()) return;
            Thread t = new Thread(() -> {
                for (Apps.LazyBanner b : pending) b.resolve();
                grid.postInvalidate();
            }, "cosy-banners");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        });
    }

    private static final int TO_PILLS = -2, NOWHERE = -1;

    /**
     * Where a D-pad key goes from tile {@code g}: another tile's index, TO_PILLS or NOWHERE. Every move is explicit:
     * during a smooth scroll Android's own focus search can land a row off. Rows stop at their ends.
     */
    private int target(int g, int keyCode) {
        int n = tiles.size(), rest = n - dockCount;
        if (g < dockCount) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP: return TO_PILLS;
                case KeyEvent.KEYCODE_DPAD_DOWN: return rest > 0 ? dockCount + Math.min(g, rest - 1) : NOWHERE;
                case KeyEvent.KEYCODE_DPAD_LEFT: return g > 0 ? g - 1 : NOWHERE;
                case KeyEvent.KEYCODE_DPAD_RIGHT: return g < dockCount - 1 ? g + 1 : NOWHERE;
                default: return NOWHERE;
            }
        }
        int i = g - dockCount, col = i % GRID_COLUMNS;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (i >= GRID_COLUMNS) return g - GRID_COLUMNS;
                return dockCount > 0 ? Math.min(col, dockCount - 1) : TO_PILLS;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // A shorter last row: go to its last tile; on the last row, stay.
                int below = Math.min(i + GRID_COLUMNS, rest - 1);
                return below / GRID_COLUMNS > i / GRID_COLUMNS ? dockCount + below : NOWHERE;
            case KeyEvent.KEYCODE_DPAD_LEFT: return col > 0 ? g - 1 : NOWHERE;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return col < GRID_COLUMNS - 1 && i < rest - 1 ? g + 1 : NOWHERE;
            default: return NOWHERE;
        }
    }

    private boolean onTileKey(int g, int keyCode, KeyEvent e) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        if (moving >= 0) {
            // The OK still held from picking the tile up keeps repeating: only a fresh press puts it down.
            if (ok && e.getRepeatCount() > 0) return true;
            if (ok) {
                endMove();
                return true;
            }
            int to = target(g, keyCode);
            if (to >= 0) {
                navSound(keyCode);
                Collections.swap(tiles, g, to);
                saveOrder();
                buildTiles();
                moving = to;
                tileAt(to).requestFocus();
                tileAt(to).setMoving(true);
            }
            return isArrow(keyCode);   // Back puts it down (onBackPressed); volume and the rest work as usual
        }
        if (ok && e.getRepeatCount() > 0) {   // OK held: the tile's menu, whichever of this and the long click comes first
            showTileMenu(g);
            return true;
        }
        if (ok) return false;   // a click opens the app
        int to = target(g, keyCode);
        if (to == TO_PILLS || to >= 0) navSound(keyCode);
        if (to == TO_PILLS) firstPill().requestFocus();
        else if (to >= 0) tileAt(to).requestFocus();
        return isArrow(keyCode);
    }

    /** The system's navigation click: Android plays it only for focus moves it makes itself, and these are ours. */
    private void navSound(int keyCode) {
        int dir = keyCode == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP
                : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN
                : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
        root.playSoundEffect(android.view.SoundEffectConstants.getContantForFocusDirection(dir));
    }

    /**
     * Fire OS sets the UI sounds to 0 as soon as audio goes to an external system, and leaves them there after a
     * power cut; the launcher puts them back when it runs, so the system's own clicks keep working.
     */
    private void restoreUiSounds() {
        try {
            if (Settings.System.getInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) {
                Settings.System.putInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1);
            }
        } catch (SecurityException ignored) {
            // WRITE_SETTINGS was never granted: the system keeps its own value.
        }
    }

    /** Fire OS drops the UI sounds whenever it likes: watch the key and put it back straight away. */
    private final ContentObserver uiSounds = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            restoreUiSounds();
        }
    };

    private static boolean isArrow(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private Tile tileAt(int g) {
        return (Tile) (g < dockCount ? dock.getChildAt(g) : grid.getChildAt(g - dockCount));
    }

    // ---- the tile menu: Move, Hide, App info, Show hidden apps --------------------------------------

    private int menuFor = -1;

    private Set<String> hiddenByRemote() {
        return new HashSet<>(list(getSharedPreferences(PREFS, MODE_PRIVATE).getString(HIDDEN_KEY, "")));
    }

    private void saveHiddenByRemote(Set<String> pkgs) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(HIDDEN_KEY, String.join(",", pkgs)).apply();
    }

    private void showTileMenu(int g) {
        if (menuFor >= 0 || moving >= 0) return;
        menuFor = g;
        Apps.App app = tiles.get(g);
        tileMenuItems.removeAllViews();
        addMenuItem(getString(R.string.menu_move), () -> startMove(g));
        addMenuItem(getString(R.string.menu_hide), () -> hideTile(g));
        addMenuItem(getString(R.string.menu_info), () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + app.pkg))));
        addMenuItem(getString(R.string.menu_uninstall), () -> uninstall(app.pkg));
        int hidden = hiddenByRemote().size();
        if (hidden > 0) addMenuItem(getString(R.string.menu_show_hidden, hidden), this::showHiddenTiles);
        // A dropdown: Up and Down walk it and stop at its ends, Left and Right go nowhere.
        int n = tileMenuItems.getChildCount();
        for (int i = 0; i < n; i++) {
            final boolean top = i == 0, bottom = i == n - 1;
            tileMenuItems.getChildAt(i).setOnKeyListener((v, keyCode, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || (keyCode == KeyEvent.KEYCODE_DPAD_UP && top) || (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && bottom)
                    // The OK still held from opening the menu keeps repeating: only a fresh press picks an item.
                    || ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && e.getRepeatCount() > 0)));
        }
        // Above the tile when it fits, else under it; never past the screen's edges.
        Tile tile = tileAt(g);
        tileMenu.setVisibility(View.INVISIBLE);
        tileMenu.post(() -> {
            int[] t = new int[2], r = new int[2];
            tile.getLocationInWindow(t);
            root.getLocationInWindow(r);
            int x = t[0] - r[0], y = t[1] - r[1], w = tileMenu.getWidth(), h = tileMenu.getHeight();
            int top = y - h - dp(14) >= dp(12) ? y - h - dp(14) : y + Math.round(tile.getHeight() * tile.getScaleY()) + dp(14);
            tileMenu.setX(Math.max(dp(12), Math.min(x, root.getWidth() - w - dp(12))));
            tileMenu.setY(Math.min(top, root.getHeight() - h - dp(12)));
            tileMenu.setVisibility(View.VISIBLE);
            tileMenuItems.getChildAt(0).requestFocus();
        });
    }

    private void addMenuItem(String label, Runnable action) {
        TextView chip = new TextView(this, null, 0, R.style.Chip);
        chip.setText(label);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        chip.setTextSize(14f);
        chip.setOnClickListener(v -> {
            closeTileMenu(true);
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        if (tileMenuItems.getChildCount() > 0) lp.topMargin = dp(6);
        tileMenuItems.addView(chip, lp);
    }

    /** Closes the menu; {@code refocus} gives focus back to the tile it was opened on. */
    private void closeTileMenu(boolean refocus) {
        if (menuFor < 0) return;
        int g = menuFor;
        menuFor = -1;
        tileMenu.setVisibility(View.GONE);
        if (refocus && g < tiles.size()) tileAt(g).requestFocus();
    }

    /** Out of the dock and the grid until "Show hidden apps"; the app itself is untouched. */
    private void hideTile(int g) {
        Set<String> hidden = hiddenByRemote();
        hidden.add(tiles.get(g).pkg);
        saveHiddenByRemote(hidden);
        tiles.remove(g);
        dockCount = Math.min(tiles.size(), GRID_COLUMNS);
        saveOrder();
        catTarget = null;
        buildTiles();
        if (!tiles.isEmpty()) tileAt(Math.min(g, tiles.size() - 1)).requestFocus();
    }

    /** The system's own uninstall dialog: the package manager refuses system apps itself. */
    private void uninstall(String pkg) {
        Intent i = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + pkg));
        i.putExtra(Intent.EXTRA_RETURN_RESULT, false);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        }
    }

    private void showHiddenTiles() {
        saveHiddenByRemote(new HashSet<>());
        catTarget = null;
        loadApps();   // they come back where the saved order had them, or after the rest
        focusDock(lastDock);
    }

    // ---- moving a tile ----------------------------------------------------------------------------

    /** Holding OK picks the tile up: the arrows move it (dock and grid alike), OK or Back puts it down. */
    private void startMove(int g) {
        if (moving == g) return;
        moving = g;
        tileAt(g).setMoving(true);
        Toast.makeText(this, R.string.move_hint, Toast.LENGTH_SHORT).show();
    }

    private void endMove() {
        if (moving < 0) return;
        Tile t = tileAt(moving);
        moving = -1;
        if (t != null) t.setMoving(false);
    }

    private void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (Apps.App a : tiles) sb.append(sb.length() == 0 ? "" : ",").append(a.pkg);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(ORDER_KEY, sb.toString()).apply();
    }

    private View firstPill() {
        return sources.getVisibility() == View.VISIBLE ? sources : findViewById(R.id.wifi);
    }

    private void focusDock(int index) {
        if (dock.getChildCount() == 0) return;
        dock.getChildAt(Math.max(0, Math.min(index, dock.getChildCount() - 1))).requestFocus();
    }

    // ---- the black cat ----------------------------------------------------------------------------

    /** Where the cat sits on a tile: centred on its top edge, as the tile is drawn now (zoom included). */
    private float[] catSpot(View tile) {
        int[] t = new int[2], r = new int[2];
        tile.getLocationInWindow(t);
        root.getLocationInWindow(r);
        float w = tile.getWidth() * tile.getScaleX();
        return new float[]{t[0] - r[0] + w / 2f - cat.getWidth() / 2f, t[1] - r[1] - cat.getHeight() + dp(3)};
    }

    private void pinCat() {
        if (!catTarget.isAttachedToWindow()) return;   // loadApps() rebuilt the tiles; focus moves it on
        float[] p = catSpot(catTarget);
        cat.setTranslationX(p[0]);
        cat.setTranslationY(p[1]);
    }

    /** Jumps in an arc from the tile it sits on to the newly focused one; the first focus just puts it there. */
    private void moveCat(View tile) {
        if (catJump != null) catJump.cancel();
        boolean first = catTarget == null || cat.getVisibility() != View.VISIBLE;
        catTarget = tile;
        if (first) {
            cat.setVisibility(View.VISIBLE);
            pinCat();
            return;
        }
        final float x0 = cat.getTranslationX(), y0 = cat.getTranslationY();
        final float height = dp(mascotMode ? 22 : 32);
        // As quick as the focus zoom (240 ms), so the cat keeps up when the D-pad is held or pressed fast.
        catJump = ValueAnimator.ofFloat(0f, 1f).setDuration(200);
        catJump.setInterpolator(new AccelerateDecelerateInterpolator());
        catJump.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float[] end = catSpot(tile);   // the target zooms and scrolls while the cat is in the air
            // The statue is not a cat: it glides, without the mirror flip or the tumble.
            float dir = mascotMode ? 1f : (end[0] < x0 ? -1f : 1f);
            cat.setScaleX(dir);
            cat.setTranslationX(x0 + (end[0] - x0) * f);
            cat.setTranslationY(y0 + (end[1] - y0) * f - height * 4f * f * (1f - f));
            cat.setRotation(mascotMode ? 0f : dir * 12f * (float) Math.sin(Math.PI * f));
        });
        catJump.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                cat.setRotation(0f);
                catJump = null;
            }
        });
        catJump.start();
    }

    private void restoreFocus() {
        if (inputsOpen) return;
        View f = getCurrentFocus();
        if (f == null || !f.isAttachedToWindow()) {   // after a rebuild; else keep the place
            if (!focusLastLaunched()) focusDock(lastDock);
        }
    }

    /** Remembered so Home, and the app coming back to the front, put the cat back where you left it. */
    private void rememberLaunched(String pkg) {
        lastLaunched = pkg;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_KEY, pkg).apply();
    }

    /** Focuses the tile of the last app opened; false when that app is no longer on the page. */
    private boolean focusLastLaunched() {
        if (lastLaunched.isEmpty()) return false;
        for (int g = 0; g < tiles.size(); g++) {
            if (!tiles.get(g).pkg.equals(lastLaunched)) continue;
            if (g < dockCount) page.smoothScrollTo(0, 0);   // the dock lives on the clock screen
            tileAt(g).requestFocus();
            return true;
        }
        return false;
    }

    // ---- the Sources dropdown ---------------------------------------------------------------------

    private void openInputs() {
        if (inputs.getChildCount() == 0) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        inputsOpen = true;
        inputsPanel.setAlpha(0f);
        inputsPanel.setVisibility(View.VISIBLE);
        inputsPanel.animate().alpha(1f).setDuration(160).start();
        inputs.getChildAt(0).requestFocus();
    }

    private void closeInputs() {
        if (!inputsOpen) return;
        inputsOpen = false;
        inputsPanel.setVisibility(View.GONE);
    }

    // ---- inputs -----------------------------------------------------------------------------------

    /** Live TV, the HDMI ports in order, and USB media. Rebuilt on every resume. */
    /** What the input chips were last built from, so a resume with the same inputs keeps them. */
    private List<String> inputsKey = Collections.emptyList();

    /**
     * Live TV, the HDMI ports in order, and USB media. Checked on every resume and rebuilt only when
     * they change: each chip costs about 4 ms to create, and this TV has six.
     */
    private void buildInputs() {
        List<String> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        Intent liveTv = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            keys.add("live");
            labels.add(getString(R.string.live_tv));
            actions.add(() -> open(liveTv));
        }

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> hdmi = new ArrayList<>();
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info);
            }
            Collections.sort(hdmi, (a, b) -> a.getId().compareTo(b.getId()));
            for (int i = 0; i < hdmi.size(); i++) {
                TvInputInfo info = hdmi.get(i);
                CharSequence l = info.loadLabel(this);
                String label = l == null || l.length() == 0 ? "HDMI " + (i + 1) : l.toString();
                if (!label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                keys.add("hdmi:" + id + ":" + label);
                labels.add(label);
                actions.add(() -> openInput(id));
            }
        }

        Intent usb = getPackageManager().getLeanbackLaunchIntentForPackage(usbPkg);
        if (usb != null) {
            keys.add("usb");
            labels.add(getString(R.string.usb));
            actions.add(() -> open(usb));
        }

        if (keys.equals(inputsKey)) return;   // same inputs: the chips already say so
        inputsKey = keys;

        inputs.removeAllViews();
        for (int i = 0; i < labels.size(); i++) addInputChip(labels.get(i), actions.get(i));

        // A dropdown: Up and Down walk the list and stop at its ends, Left and Right go nowhere.
        int n = inputs.getChildCount();
        for (int i = 0; i < n; i++) {
            final boolean top = i == 0, bottom = i == n - 1;
            inputs.getChildAt(i).setOnKeyListener((v, keyCode, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || (keyCode == KeyEvent.KEYCODE_DPAD_UP && top) || (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && bottom)));
        }
        // No input on this set after all, or one came back: the pill follows.
        sources.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
    }

    private void addInputChip(String label, Runnable action) {
        TextView chip = new TextView(this, null, 0, R.style.Chip);
        chip.setText(label);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        chip.setTextSize(14f);
        chip.setOnClickListener(v -> {
            closeInputs();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        if (inputs.getChildCount() > 0) lp.topMargin = dp(6);
        inputs.addView(chip, lp);
    }

    /** The standard way to switch to an HDMI port: VIEW its passthrough channel, which Live TV handles. */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        open(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** The store on this platform: Fire TV's Appstore, else the Play Store on Android TV, else a toast. */
    private void openStore() {
        PackageManager pm = getPackageManager();
        Intent store = storeIntent("com.amazon.venezia", "com.amazon.venezia.grid.AppsGridLauncherActivity");
        // Android TV: the Play Store's leanback entry (TvMainActivity); the component changes between versions.
        if (store == null) store = pm.getLeanbackLaunchIntentForPackage("com.android.vending");
        if (store == null) store = storeIntent("com.android.vending", "com.google.android.finsky.tvmainactivity.TvMainActivity");
        if (store == null) store = pm.getLeanbackLaunchIntentForPackage("com.amazon.venezia");
        if (store == null) {
            Toast.makeText(this, R.string.store_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        open(store);
    }

    /** One store screen by component, or null when that store is not on this TV. */
    private Intent storeIntent(String pkg, String activity) {
        Intent i = new Intent().setClassName(pkg, activity);
        return getPackageManager().resolveActivity(i, 0) != null ? i : null;
    }

    /**
     * Wi-Fi: Fire OS's own network screen by component (its generic intent answers a CTSDummy stub that
     * lands on the launcher), then the plain intent when it is real.
     */
    private Intent wifiIntent() {
        Intent stock = new Intent().setClassName("com.amazon.tv.settings.v2",
                "com.amazon.tv.settings.v2.tv.network.NetworkActivity");
        if (getPackageManager().resolveActivity(stock, 0) != null) return stock;
        Intent wifi = new Intent(Settings.ACTION_WIFI_SETTINGS);
        android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(wifi, 0);
        boolean usable = ri != null && ri.activityInfo != null && !ri.activityInfo.name.contains("CTSDummy");
        return usable ? wifi : settingsIntent();
    }

    /**
     * Settings: the launcher's own menu when it is installed (it is the settings bridge and holds the permission
     * for Display & Audio), then Fire OS's device screen, then the platform intent.
     */
    private Intent settingsIntent() {
        Intent all = new Intent().setClassName("com.amazon.tv.launcher",
                "com.amazon.tv.launcher.ui.MainSettingsActivity");
        if (getPackageManager().resolveActivity(all, 0) != null) return all;
        Intent stock = new Intent().setClassName("com.amazon.tv.settings.v2",
                "com.amazon.tv.settings.v2.tv.device.DeviceActivity");
        return getPackageManager().resolveActivity(stock, 0) != null ? stock : new Intent(Settings.ACTION_SETTINGS);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private List<String> list(int resId) {
        return list(getString(resId));
    }

    private static List<String> list(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
