package tv.zenhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Zen Home : ecran d'accueil minimaliste pour Android TV, facon Apple TV.
 * Photo plein ecran qui change + horloge + dock d'applis.
 *
 * Reglages optionnels (ecrits par le kit via adb, rien a faire a la main) :
 *   settings put global zenhome_title_b64  <titre en base64 UTF-8>   (vide = pas de titre)
 *   settings put global zenhome_dock       com.netflix.ninja,com.google.android.youtube.tv,...
 *   settings put global zenhome_interval   60   (secondes entre deux photos)
 * Photos perso : fichiers .jpg/.png dans /sdcard/ZenHome/ (remplacent les photos fournies).
 */
public class Home extends Activity {

    /** Applis de streaming connues, par ordre de priorite pour le dock automatique. */
    static final String[] KNOWN_DOCK = {
            "com.netflix.ninja",                       // Netflix
            "com.google.android.youtube.tv",           // YouTube
            "com.amazon.amazonvideo.livingroom",       // Prime Video
            "com.disney.disneyplus",                   // Disney+
            "com.canal.android.canal",                 // myCANAL
            "com.wbd.stream",                          // Max
            "com.apple.atve.androidtv.appletv",        // Apple TV
            "com.spotify.tv.android",                  // Spotify
            "com.plexapp.android",                     // Plex
            "fr.francetv.pluzz",                       // france.tv
            "fr.tf1.mytf1",                            // TF1+
            "fr.m6.m6replay",                          // M6+
            "tv.arte.plus7",                           // Arte
            "tv.molotov.app",                          // Molotov
            "com.cbs.ott",                             // Paramount+
            "com.dazn",                                // DAZN
            "tv.twitch.android.app",                   // Twitch
            "org.xbmc.kodi",                           // Kodi
            "com.google.android.youtube.tvkids",       // YouTube Kids
    };
    /** Applis "entrees TV / chaines" selon les marques. */
    static final String[][] KNOWN_INPUTS = {
            {"com.tcl.tv", "com.tcl.tv.TVActivity"},
            {"com.mediatek.wwtv.tvcenter", null},
            {"org.droidtv.playtv", null},          // Philips
            {"com.sony.dtv.tvx", null},            // Sony
            {"com.mitv.tvplayer", null},           // Xiaomi
            {"com.hisense.tv", null},
            {"com.google.android.tv", null},       // Live Channels
            {"com.android.tv", null},
    };
    static final int MAX_BIG = 4;
    static final long FADE_MS = 2500L;

    final Handler h = new Handler();
    long rotationMs = 60_000L;
    FrameLayout root, wallLayer;
    ImageView wallA, wallB;
    boolean aOnTop = true, released = false;
    TextView clock, date;
    List<String> walls = new ArrayList<>();
    boolean wallsFromFiles = false;
    int wallIdx = 0;
    View allApps;
    LinearLayout dock;
    Typeface fReg, fMed, fLight;
    float density;
    boolean fr;

    int dp(float v) { return Math.round(v * density); }
    String t(String frTxt, String enTxt) { return fr ? frTxt : enTxt; }

    String setting(String key) {
        try { return Settings.Global.getString(getContentResolver(), key); } catch (Throwable e) { return null; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        fr = "fr".equals(Locale.getDefault().getLanguage());
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        density = Math.max(dm.widthPixels, dm.heightPixels) / 1280f; // maquette sur 1280 unites de large
        try {
            fReg = Typeface.createFromAsset(getAssets(), "Poppins-Regular.ttf");
            fMed = Typeface.createFromAsset(getAssets(), "Poppins-Medium.ttf");
            fLight = Typeface.createFromAsset(getAssets(), "Poppins-Light.ttf");
        } catch (Exception e) { fReg = fMed = fLight = Typeface.SANS_SERIF; }
        try {
            String iv = setting("zenhome_interval");
            if (iv != null) rotationMs = Math.max(10, Integer.parseInt(iv.trim())) * 1000L;
        } catch (Exception ignored) { }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        wallA = newWall(); wallB = newWall();
        wallLayer = new FrameLayout(this);
        wallLayer.addView(wallB); wallLayer.addView(wallA);
        root.addView(wallLayer, new FrameLayout.LayoutParams(-1, -1));

        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8C000000, 0x00000000}));
        root.addView(top, new FrameLayout.LayoutParams(-1, dp(200), Gravity.TOP));
        View bot = new View(this);
        bot.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x99000000, 0x00000000}));
        root.addView(bot, new FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM));

        // Titre (optionnel)
        String title = null;
        try {
            String b64 = setting("zenhome_title_b64");
            if (b64 != null && b64.trim().length() > 0)
                title = new String(Base64.decode(b64.trim(), Base64.DEFAULT), "UTF-8").trim();
        } catch (Exception ignored) { }
        if (title != null && title.length() > 0) {
            TextView tv = text(title, 26, fMed, 0xFFFFFFFF);
            FrameLayout.LayoutParams lpT = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            lpT.setMargins(dp(56), dp(38), 0, 0);
            root.addView(tv, lpT);
        }

        // Horloge + date
        LinearLayout cl = new LinearLayout(this);
        cl.setOrientation(LinearLayout.VERTICAL);
        cl.setGravity(Gravity.END);
        clock = text("", 40, fLight, 0xFFFFFFFF);
        date = text("", 15, fReg, 0xE6FFFFFF);
        cl.addView(clock); cl.addView(date);
        FrameLayout.LayoutParams lpC = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lpC.setMargins(0, dp(26), dp(56), 0);
        root.addView(cl, lpC);

        // Dock
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(22), dp(18), dp(22), dp(18));
        dock.setClipChildren(false); dock.setClipToPadding(false);
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(dp(28));
        glass.setColor(0x38FFFFFF);
        glass.setStroke(Math.max(1, dp(0.75f)), 0x40FFFFFF);
        dock.setBackground(glass);
        buildDock();
        FrameLayout.LayoutParams lpD = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lpD.setMargins(0, 0, 0, dp(46));
        root.addView(dock, lpD);

        setContentView(root);
        loadWalls();
        showWall(true);
        focusFirst();
    }

    void focusFirst() { if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus(); }

    boolean usable(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return ai.enabled && launchIntent(pkg) != null;
        } catch (Exception e) { return false; }
    }

    void buildDock() {
        List<String> pkgs = new ArrayList<>();
        String conf = setting("zenhome_dock");
        if (conf != null && conf.trim().length() > 0) {
            for (String p : conf.split("[,;\\s]+")) { p = p.trim(); if (p.length() > 0 && usable(p) && !pkgs.contains(p)) pkgs.add(p); }
        }
        if (pkgs.isEmpty()) for (String p : KNOWN_DOCK) { if (usable(p)) pkgs.add(p); }
        int n = 0;
        for (String p : pkgs) { if (n >= MAX_BIG) break; View v = bigTile(p); if (v != null) { dock.addView(v); n++; } }

        List<View> small = new ArrayList<>();
        if (usable("com.android.vending"))
            small.add(smallTile(pkgIcon("com.android.vending"), "Play Store", new Runnable() { public void run() { launch("com.android.vending"); } }));
        final String[] input = findInput();
        if (input != null)
            small.add(smallTile(pkgIcon(input[0]), t("Entrées TV", "TV inputs"), new Runnable() { public void run() { openInput(input); } }));
        small.add(smallTile(new GridIcon(), t("Toutes", "All apps"), new Runnable() { public void run() { showAllApps(); } }));
        Drawable gear = pkgIcon("com.android.tv.settings");
        if (gear == null) gear = pkgIcon("com.android.settings");
        small.add(smallTile(gear, t("Réglages", "Settings"), new Runnable() { public void run() {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { toast(t("Réglages indisponibles", "Settings unavailable")); } } }));

        if (n > 0) {
            View sep = new View(this); sep.setBackgroundColor(0x4DFFFFFF);
            LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(dp(1.5f), dp(64));
            lpS.setMargins(dp(14), 0, dp(14), 0);
            dock.addView(sep, lpS);
        }
        for (View v : small) dock.addView(v);
    }

    String[] findInput() {
        for (String[] in : KNOWN_INPUTS) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(in[0], 0);
                if (!ai.enabled) continue;
                if (in[1] != null || launchIntent(in[0]) != null) return in;
            } catch (Exception ignored) { }
        }
        return null;
    }

    void openInput(String[] in) {
        if (in[1] != null) {
            try {
                Intent i = new Intent(); i.setComponent(new ComponentName(in[0], in[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return;
            } catch (Exception ignored) { }
        }
        launch(in[0]);
    }

    ImageView newWall() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        return v;
    }

    TextView text(String s, float size, Typeface tf, int color) {
        TextView tv = new TextView(this);
        tv.setText(s); tv.setTextColor(color); tv.setTypeface(tf);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density);
        tv.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    // ---------- Tuiles ----------
    View bigTile(final String pkg) {
        PackageManager pm = getPackageManager();
        Drawable d = null;
        String label = pkg;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(ai).toString();
            d = pm.getApplicationBanner(ai);
            if (d == null) { Intent li = pm.getLeanbackLaunchIntentForPackage(pkg); if (li != null) d = pm.getActivityBanner(li); }
        } catch (Exception e) { return null; }
        FrameLayout f = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(14)); bg.setColor(0xFF1E1E1E);
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); } });
        if (d != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(d);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // pas de banniere : icone + nom
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this); iv.setImageDrawable(pkgIcon(pkg));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(44), dp(44)); il.gravity = Gravity.CENTER_HORIZONTAL;
            l.addView(iv, il);
            TextView tv = text(label, 11, fMed, 0xFFFFFFFF); tv.setGravity(Gravity.CENTER); tv.setSingleLine(true);
            tv.setPadding(dp(6), dp(6), dp(6), 0);
            l.addView(tv, new LinearLayout.LayoutParams(-1, -2));
            f.addView(l, new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(176), dp(99));
        lp.setMargins(dp(9), 0, dp(9), 0);
        f.setLayoutParams(lp);
        f.setContentDescription(label);
        wire(f, new Runnable() { public void run() { launch(pkg); } }, 1.14f);
        return f;
    }

    View smallTile(Drawable icon, String label, Runnable r) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(18)); bg.setColor(0x33FFFFFF);
        l.setBackground(bg);
        if (icon == null) icon = new GridIcon();
        ImageView iv = new ImageView(this); iv.setImageDrawable(icon);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(34), dp(34));
        il.gravity = Gravity.CENTER_HORIZONTAL;
        l.addView(iv, il);
        TextView tv = text(label, 10, fMed, 0xFFFFFFFF); tv.setShadowLayer(0, 0, 0, 0);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(true);
        tv.setPadding(0, dp(4), 0, 0);
        l.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(86), dp(80));
        lp.setMargins(dp(7), 0, dp(7), 0);
        l.setLayoutParams(lp);
        l.setTag(18);
        l.setContentDescription(label);
        wire(l, r, 1.12f);
        return l;
    }

    void wire(final View v, final Runnable r, final float scale) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { r.run(); } });
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View x, boolean has) {
                x.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                        .translationY(has ? -dp(6) : 0).translationZ(has ? dp(12) : 0)
                        .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
                x.setForeground(has ? ring(x.getTag() instanceof Integer ? (Integer) x.getTag() : 14) : null);
            }
        });
    }

    GradientDrawable ring(int r) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(r)); g.setColor(0x00000000); g.setStroke(dp(2), 0xF2FFFFFF);
        return g;
    }

    Drawable pkgIcon(String pkg) {
        try { return getPackageManager().getApplicationIcon(pkg); } catch (Exception e) { return null; }
    }

    // ---------- Lancements ----------
    Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    void launch(String pkg) {
        Intent i = launchIntent(pkg);
        if (i == null) { toast(t("Appli introuvable", "App not found")); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (ActivityNotFoundException e) { toast(t("Impossible d'ouvrir", "Cannot open")); }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------- Toutes les applis ----------
    void showAllApps() {
        if (allApps != null) return;
        final PackageManager pm = getPackageManager();
        LinkedHashMap<String, ResolveInfo> map = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN); q.addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (!map.containsKey(p) && !p.equals(getPackageName())) map.put(p, ri);
            }
        }
        List<ResolveInfo> list = new ArrayList<>(map.values());
        Collections.sort(list, new java.util.Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) { return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()); } });
        FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(0xE6101216);
        ScrollView sv = new ScrollView(this);
        GridLayout g = new GridLayout(this);
        g.setColumnCount(5);
        g.setPadding(dp(60), dp(80), dp(60), dp(60));
        g.setClipChildren(false);
        View first = null;
        for (ResolveInfo ri : list) {
            final String pkg = ri.activityInfo.packageName;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
            Drawable ban = ri.activityInfo.loadBanner(pm);
            if (ban == null) ban = ri.loadIcon(pm);
            ImageView iv = new ImageView(this); iv.setImageDrawable(ban); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(iv, new LinearLayout.LayoutParams(dp(150), dp(84)));
            TextView tv = text(ri.loadLabel(pm).toString(), 11, fReg, 0xFFFFFFFF);
            tv.setPadding(0, dp(6), 0, 0); tv.setSingleLine(true); tv.setGravity(Gravity.CENTER);
            cell.addView(tv, new LinearLayout.LayoutParams(dp(150), -2));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.setMargins(dp(10), dp(10), dp(10), dp(10));
            cell.setPadding(dp(8), dp(8), dp(8), dp(8));
            GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(14)); bg.setColor(0x14FFFFFF); cell.setBackground(bg);
            wire(cell, new Runnable() { public void run() { closeAllApps(); launch(pkg); } }, 1.08f);
            g.addView(cell, lp);
            if (first == null) first = cell;
        }
        TextView hdr = text(t("Toutes les applications", "All apps"), 22, fMed, 0xFFFFFFFF);
        FrameLayout.LayoutParams lh = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lh.setMargins(dp(70), dp(30), 0, 0);
        sv.addView(g);
        ov.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        ov.addView(hdr, lh);
        allApps = ov;
        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        ov.setAlpha(0f); ov.animate().alpha(1f).setDuration(200).start();
        if (first != null) first.requestFocus();
    }

    void closeAllApps() {
        if (allApps == null) return;
        root.removeView(allApps); allApps = null;
        focusFirst();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { closeAllApps(); return true; } // l'accueil ne se ferme pas
        if (code == KeyEvent.KEYCODE_MENU) { showAllApps(); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onBackPressed() { closeAllApps(); }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); closeAllApps(); }

    // ---------- Fonds d'ecran ----------
    void loadWalls() {
        walls.clear();
        wallsFromFiles = false;
        // 1) photos perso dans /sdcard/ZenHome
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "ZenHome");
            File[] fs = dir.listFiles();
            if (fs != null) {
                Arrays.sort(fs);
                for (File f : fs) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")))
                        walls.add(f.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) { }
        if (!walls.isEmpty()) { wallsFromFiles = true; }
        else {
            // 2) photos fournies (assets/walls)
            try {
                String[] a = getAssets().list("walls");
                if (a != null) { Arrays.sort(a); for (String n : a) { String l = n.toLowerCase(Locale.ROOT); if (l.endsWith(".jpg") || l.endsWith(".png")) walls.add(n); } }
            } catch (Exception ignored) { }
        }
        wallIdx = walls.isEmpty() ? 0 : (int) ((System.currentTimeMillis() / rotationMs) % walls.size());
    }

    Bitmap decode(String name) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            int maxW = Math.max(getResources().getDisplayMetrics().widthPixels, 1280);
            if (wallsFromFiles) {
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(name, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= maxW) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeFile(name, o);
            }
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            InputStream is = getAssets().open("walls/" + name);
            Bitmap b = BitmapFactory.decodeStream(is, null, o);
            is.close();
            return b;
        } catch (Throwable t) { return null; }
    }

    void showWall(boolean instant) {
        if (walls.isEmpty()) return;
        final ImageView next = aOnTop ? wallB : wallA;
        final ImageView cur = aOnTop ? wallA : wallB;
        Bitmap bm = decode(walls.get(wallIdx));
        if (bm == null) return;
        Drawable old = next.getDrawable();
        next.setImageBitmap(bm);
        if (old instanceof BitmapDrawable) {
            Bitmap ob = ((BitmapDrawable) old).getBitmap();
            if (ob != null && ob != bm) ob.recycle();
        }
        next.bringToFront(); // uniquement dans le calque des photos
        next.setScaleX(1f); next.setScaleY(1f);
        next.animate().cancel();
        if (instant) { next.setAlpha(1f); cur.setAlpha(0f); }
        else {
            next.setAlpha(0f);
            android.animation.ObjectAnimator.ofFloat(next, "alpha", 0f, 1f).setDuration(FADE_MS).start();
        }
        // leger zoom lent (effet Ken Burns)
        next.animate().scaleX(1.06f).scaleY(1.06f).setDuration(rotationMs + FADE_MS)
                .setInterpolator(new LinearInterpolator()).start();
        aOnTop = !aOnTop;
    }

    final Runnable rotate = new Runnable() {
        public void run() {
            if (walls.size() > 1) { wallIdx = (wallIdx + 1) % walls.size(); showWall(false); }
            h.postDelayed(this, rotationMs);
        }
    };

    final Runnable tick = new Runnable() {
        public void run() {
            Date n = new Date();
            clock.setText(DateFormat.is24HourFormat(Home.this) ? DateFormat.format("HH:mm", n) : DateFormat.format("h:mm", n));
            Locale loc = Locale.getDefault();
            String d = new SimpleDateFormat(fr ? "EEEE d MMMM" : "EEEE, MMMM d", loc).format(n);
            date.setText(d.substring(0, 1).toUpperCase(loc) + d.substring(1));
            h.postDelayed(this, 15_000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacksAndMessages(null);
        h.post(tick);
        h.postDelayed(rotate, rotationMs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacksAndMessages(null); // rien ne tourne pendant qu'une appli est ouverte
    }

    @Override
    protected void onStop() {
        super.onStop();
        // libere la memoire des photos pendant qu'on regarde une video
        for (ImageView v : new ImageView[]{wallA, wallB}) {
            v.animate().cancel();
            Drawable d = v.getDrawable(); v.setImageDrawable(null);
            if (d instanceof BitmapDrawable) { Bitmap b = ((BitmapDrawable) d).getBitmap(); if (b != null) b.recycle(); }
        }
        released = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (released) { released = false; loadWalls(); showWall(true); }
    }

    /** Icone "toutes les applis" : grille 3x3 centree. */
    static class GridIcon extends Drawable {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        GridIcon() { p.setColor(0xFFFFFFFF); }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float size = Math.min(b.width(), b.height()) * 0.74f;
            float cell = size / 3f, sq = cell * 0.66f, r = sq * 0.3f;
            float x0 = b.exactCenterX() - size / 2f, y0 = b.exactCenterY() - size / 2f;
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                float x = x0 + j * cell + (cell - sq) / 2f, y = y0 + i * cell + (cell - sq) / 2f;
                c.drawRoundRect(x, y, x + sq, y + sq, r, r, p);
            }
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
