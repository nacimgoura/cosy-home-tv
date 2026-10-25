package com.cosyhome.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The TV apps to show as tiles: every LEANBACK_LAUNCHER activity, one per package, dock apps
 * first in their configured order, then the pinned ones, then the rest alphabetically.
 * Banner loading follows cleanyourtv's AppAdapter (MIT, https://github.com/Gathin23/cleanyourtv).
 */
final class Apps {

    static final class App {
        final String label;
        final String pkg;
        final ComponentName component;
        final Drawable banner;
        final Drawable icon;

        App(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.pkg = component.getPackageName();
            this.component = component;
            this.banner = banner;
            this.icon = icon;
        }
    }

    private Apps() {
    }

    /**
     * @param eager how many banners to decode before returning: the dock and the first grid row, the
     *              tiles the TV shows straight away. The others are decoded the first time they are
     *              drawn, so start-up does not pay for banners nobody has scrolled to yet.
     */
    static List<App> load(Context ctx, List<String> dock, List<String> pinned, Set<String> hidden, int eager) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<Candidate> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || !seen.add(pkg)) continue;
            found.add(new Candidate(ri, pkg, String.valueOf(ri.loadLabel(pm)).trim(),
                    new ComponentName(pkg, ri.activityInfo.name)));
        }
        Collections.sort(found, (a, b) -> {
            int ra = rank(a.pkg, dock, pinned), rb = rank(b.pkg, dock, pinned);
            return ra != rb ? Integer.compare(ra, rb) : a.label.compareToIgnoreCase(b.label);
        });

        float density = ctx.getResources().getDisplayMetrics().density;
        int w = Math.round(120 * density * Tile.FOCUS_SCALE), h = Math.round(68 * density * Tile.FOCUS_SCALE);
        List<App> apps = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            Candidate c = found.get(i);
            Drawable banner = null, icon = null;
            if (hasBanner(pm, c.ri)) {
                banner = i < eager ? loadBanner(ctx, pm, c.ri) : new LazyBanner(ctx, pm, c.ri, w, h);
            } else {
                // The icon only ever shows when there is no banner; decoding one per app costs time at start-up.
                icon = c.ri.loadIcon(pm);
            }
            apps.add(new App(c.label, c.component, banner, icon));
        }
        return apps;
    }

    private static final class Candidate {
        final ResolveInfo ri;
        final String pkg;
        final String label;
        final ComponentName component;

        Candidate(ResolveInfo ri, String pkg, String label, ComponentName component) {
            this.ri = ri;
            this.pkg = pkg;
            this.label = label;
            this.component = component;
        }
    }

    /** Whether this activity has a banner, without decoding it. */
    private static boolean hasBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        return id != 0 || ri.activityInfo.loadBanner(pm) != null;
    }

    /** A banner decoded the first time it is drawn, so start-up only pays for the tiles on screen. */
    static final class LazyBanner extends Drawable {
        private final Context ctx;
        private final PackageManager pm;
        private final ResolveInfo ri;
        private final int w, h;
        private Drawable real;

        LazyBanner(Context ctx, PackageManager pm, ResolveInfo ri, int w, int h) {
            this.ctx = ctx;
            this.pm = pm;
            this.ri = ri;
            this.w = w;
            this.h = h;
        }

        private Drawable real() {
            if (real == null) real = loadBanner(ctx, pm, ri);
            return real;
        }

        @Override
        public void draw(Canvas c) {
            Drawable d = real();
            if (d == null) return;
            d.setBounds(getBounds());
            d.draw(c);
        }

        @Override
        public void setAlpha(int alpha) {
            real().setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            real().setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }
    }

    private static int rank(String pkg, List<String> dock, List<String> pinned) {
        int d = dock.indexOf(pkg);
        if (d >= 0) return d;
        int p = pinned.indexOf(pkg);
        return p >= 0 ? 100 + p : 1000;
    }

    private static Drawable loadBanner(Context ctx, PackageManager pm, ResolveInfo ri) {
        Drawable d = null;
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources res = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // At least xhdpi, so the artwork stays sharp through the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                d = res.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (d == null) d = ri.activityInfo.loadBanner(pm);
        return d == null ? null : shrink(ctx, d);
    }

    /**
     * Redraws a banner at tile size. Some apps ship 1080p banners: kept as they are, twenty of them
     * cost about 40 MB of bitmaps plus the same again as GPU textures, for tiles 240 px wide.
     */
    private static Drawable shrink(Context ctx, Drawable d) {
        float density = ctx.getResources().getDisplayMetrics().density;
        // The largest tile (the grid's, 120x68dp) at its 1.1x focus zoom.
        int w = Math.round(120 * density * Tile.FOCUS_SCALE), h = Math.round(68 * density * Tile.FOCUS_SCALE);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw > 0 && ih > 0) {
            // Centre-crop, as the tile's ImageView would.
            float scale = Math.max(w / (float) iw, h / (float) ih);
            int sw = Math.round(iw * scale), sh = Math.round(ih * scale);
            d.setBounds((w - sw) / 2, (h - sh) / 2, (w - sw) / 2 + sw, (h - sh) / 2 + sh);
        } else {
            d.setBounds(0, 0, w, h);
        }
        d.draw(c);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }
}
