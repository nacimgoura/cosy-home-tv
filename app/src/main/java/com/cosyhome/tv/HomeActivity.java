package com.cosyhome.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private static final int GRID_COLUMNS = 6;
    /** Tiles fill the width between these margins, 16:9, the same size in the dock and the grid. */
    private static final int SIDE_DP = 48, COL_GAP_DP = 16, ROW_GAP_DP = 30;
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
    private GlassPanel inputsPanel;
    private View root, catTarget;
    private ImageView cat;
    private ValueAnimator catJump;

    private List<String> dockApps, pinnedApps;
    private Set<String> hiddenApps;
    private String liveTvPkg, usbPkg, place;
    private double lat, lon;
    private boolean located;
    private float density;

    private boolean inputsOpen;
    private boolean resumed;
    private boolean showInputs;
    private boolean appsDirty = true;   // the first resume builds the tiles
    /** Survives the activity being recreated while an app is open; resets only with the process. */
    private static int lastDock = 0;
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
        sources = findViewById(R.id.sources);
        showInputs = Boolean.parseBoolean(getString(R.string.show_inputs));
        if (!showInputs) sources.setVisibility(View.GONE);   // a streaming stick has no inputs
        root = findViewById(R.id.root);
        cat = findViewById(R.id.cat);
        // Keep the cat on its tile while the tile zooms, the grid scrolls or the clock layer drifts.
        root.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (catJump == null && catTarget != null) pinCat();
            return true;
        });
        scene.setListener(this);

        View wifi = findViewById(R.id.wifi), settings = findViewById(R.id.settings);
        sources.setOnClickListener(v -> openInputs());
        wifi.setOnClickListener(v -> open(wifiIntent()));
        settings.setOnClickListener(v -> open(settingsIntent()));
        // The pills are the top of the screen: Up goes nowhere, and the row's ends do not fall through.
        for (View pill : new View[]{sources, wifi, settings}) {
            pill.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusDock(lastDock);
                    return true;
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
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Rebuilding the tiles reloads every banner: only when an app was installed or removed meanwhile.
        if (appsDirty) {
            appsDirty = false;
            catTarget = null;   // new tiles: the cat sits on the focused one instead of jumping there
            loadApps();
        }
        if (showInputs) buildInputs();
        minuteTick.run();
        if (located) handler.postDelayed(weatherTick, Weather.stale(this) ? 0 : Weather.REFRESH_MS);
        scene.setRunning(true);
        restoreFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(weatherTick);
        scene.setRunning(false);
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

    /** Home pressed while already home: back to the clock with the first dock app focused. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readPhaseOverride(intent);
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        lastDock = 0;
        closeInputs();
        focusDock(0);
        page.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (inputsOpen) {
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

    private void loadApps() {
        List<Apps.App> apps = Apps.load(this, dockApps, pinnedApps, hiddenApps);
        dock.removeAllViews();
        grid.removeAllViews();

        // Six tiles across the width between the side margins, 16:9, in the dock and the grid alike.
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int colGap = dp(COL_GAP_DP), rowGap = dp(ROW_GAP_DP);
        int tileW = (screenW - 2 * dp(SIDE_DP) - (GRID_COLUMNS - 1) * colGap) / GRID_COLUMNS;
        int tileH = tileW * 9 / 16;

        List<Apps.App> docked = new ArrayList<>(), rest = new ArrayList<>();
        for (Apps.App a : apps) (dockApps.contains(a.pkg) && docked.size() < GRID_COLUMNS ? docked : rest).add(a);
        for (int i = 0; i < docked.size(); i++) {
            final int index = i;
            Tile t = new Tile(this, docked.get(i), DOCK_REST_ALPHA);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            if (i > 0) lp.setMarginStart(colGap);
            t.setOnTileFocused(tile -> {
                lastDock = index;
                page.smoothScrollTo(0, 0);   // the dock lives on the clock screen: show all of it
                moveCat(tile);
            });
            final boolean first = i == 0, last = i == docked.size() - 1;
            t.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        firstPill().requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (grid.getChildCount() > 0) grid.getChildAt(Math.min(index, grid.getChildCount() - 1)).requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return first;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return last;
                    default:
                        return false;
                }
            });
            dock.addView(t, lp);
        }

        // The rest follow under the dock, the same gap away as one row from the next (it leaves the cat room to
        // sit): the grid starts up into the dock's bottom margin, so the top of the next row peeks at the bottom.
        grid.setPadding(0, rowGap, 0, grid.getPaddingBottom());
        ((LinearLayout.LayoutParams) grid.getLayoutParams()).topMargin =
                -((android.widget.FrameLayout.LayoutParams) dock.getLayoutParams()).bottomMargin;
        for (int i = 0; i < rest.size(); i++) {
            Tile t = new Tile(this, rest.get(i), 1f);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = tileW;
            lp.height = tileH;
            lp.setMargins(0, 0, i % GRID_COLUMNS == GRID_COLUMNS - 1 ? 0 : colGap, rowGap);
            t.setOnTileFocused(this::moveCat);
            final int index = i, col = i % GRID_COLUMNS, count = rest.size();
            final boolean lastInRow = col == GRID_COLUMNS - 1 || i == count - 1;
            // Every move is explicit: during a smooth scroll Android's own focus search can land a row off.
            t.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (index < GRID_COLUMNS) focusDock(col);
                        else grid.getChildAt(index - GRID_COLUMNS).requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        // A shorter last row: go to its last tile; on the last row, stay.
                        int below = Math.min(index + GRID_COLUMNS, count - 1);
                        if (below / GRID_COLUMNS > index / GRID_COLUMNS) grid.getChildAt(below).requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return col == 0;   // stay on the row at its ends instead of wrapping
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return lastInRow;
                    default:
                        return false;
                }
            });
            grid.addView(t, lp);
        }
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
        final float height = dp(32);
        // As quick as the focus zoom (240 ms), so the cat keeps up when the D-pad is held or pressed fast.
        catJump = ValueAnimator.ofFloat(0f, 1f).setDuration(200);
        catJump.setInterpolator(new AccelerateDecelerateInterpolator());
        catJump.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float[] end = catSpot(tile);   // the target zooms and scrolls while the cat is in the air
            cat.setScaleX(end[0] < x0 ? -1f : 1f);
            cat.setTranslationX(x0 + (end[0] - x0) * f);
            cat.setTranslationY(y0 + (end[1] - y0) * f - height * 4f * f * (1f - f));
            cat.setRotation((end[0] < x0 ? -1f : 1f) * 12f * (float) Math.sin(Math.PI * f));
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
        if (f == null || !f.isAttachedToWindow()) focusDock(lastDock);   // after a rebuild; else keep the place
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
    private void buildInputs() {
        inputs.removeAllViews();

        Intent liveTv = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) addInputChip(getString(R.string.live_tv), () -> open(liveTv));

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
                addInputChip(label, () -> openInput(id));
            }
        }

        Intent usb = getPackageManager().getLeanbackLaunchIntentForPackage(usbPkg);
        if (usb != null) addInputChip(getString(R.string.usb), () -> open(usb));

        // A dropdown: Up and Down walk the list and stop at its ends, Left and Right go nowhere.
        int n = inputs.getChildCount();
        for (int i = 0; i < n; i++) {
            final boolean top = i == 0, bottom = i == n - 1;
            inputs.getChildAt(i).setOnKeyListener((v, keyCode, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || (keyCode == KeyEvent.KEYCODE_DPAD_UP && top) || (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && bottom)));
        }
        if (n == 0) sources.setVisibility(View.GONE);   // no input on this set after all
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

    /** Wi-Fi settings; where the TV answers that with nothing usable (Fire OS), the Settings menu instead. */
    private Intent wifiIntent() {
        Intent wifi = new Intent(Settings.ACTION_WIFI_SETTINGS);
        android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(wifi, 0);
        boolean usable = ri != null && ri.activityInfo != null && !ri.activityInfo.name.contains("CTSDummy");
        return usable ? wifi : settingsIntent();
    }

    /** Fire OS answers ACTION_SETTINGS with a stub that lands on Amazon's home; its real menu lives in its launcher. */
    private Intent settingsIntent() {
        Intent fire = new Intent().setClassName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity");
        return getPackageManager().resolveActivity(fire, 0) != null ? fire : new Intent(Settings.ACTION_SETTINGS);
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
        List<String> out = new ArrayList<>();
        for (String s : getString(resId).split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
