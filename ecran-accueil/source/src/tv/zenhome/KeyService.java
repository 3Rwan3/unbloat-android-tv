package tv.zenhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Boutons d'applis de la telecommande (Netflix, YouTube, Prime Video) : TCL d'office, autres marques via zenhome_keymap.
 * Ces touches envoient des codes propres a TCL, que seul l'accueil Google TV traitait :
 * une fois cet accueil desactive par le kit, elles ne faisaient plus rien.
 * Le kit n'active ce service que dans ce cas. Seules ces touches sont interceptees,
 * toutes les autres passent sans modification.
 */
public class KeyService extends AccessibilityService {

    /** Codes de touche TCL -> appli a ouvrir. */
    static final int[] TCL_KEYS = {4062, 4065, 4099};
    static final String[] TCL_APPS = {"com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom"};

    static boolean isTcl() {
        String b = (Build.BRAND + " " + Build.MANUFACTURER).toUpperCase(java.util.Locale.ROOT);
        return b.contains("TCL");
    }

    /**
     * Appli associee a ce code de touche sur cette TV, ou null.
     * Autres marques (Xiaomi...) : table posee par le kit dans le reglage global
     * zenhome_keymap, au format "code:paquet,code:paquet". Elle prime sur la table TCL.
     */
    static String appForKey(Context c, int code) {
        String map = null;
        try { map = Settings.Global.getString(c.getContentResolver(), "zenhome_keymap"); } catch (Throwable ignored) { }
        if (map != null && map.length() > 0) {
            for (String pair : map.split(",")) {
                int sep = pair.indexOf(':');
                if (sep > 0 && pair.substring(0, sep).trim().equals(String.valueOf(code))) return pair.substring(sep + 1).trim();
            }
            return null;
        }
        if (!isTcl()) return null;
        for (int i = 0; i < TCL_KEYS.length; i++) if (TCL_KEYS[i] == code) return TCL_APPS[i];
        return null;
    }

    /** zenhome_keylog=1 : chaque touche est notee dans logcat (tag ZenKeys), pour trouver les codes d'une telecommande. */
    static boolean keyLog(Context c) {
        try { return "1".equals(Settings.Global.getString(c.getContentResolver(), "zenhome_keylog")); } catch (Throwable e) { return false; }
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    /**
     * Ecran eteint : on arrete de filtrer les touches, sinon certaines TV ne se rallument plus
     * avec le bouton Marche de la telecommande. Le filtrage reprend a l'allumage de l'ecran.
     */
    final android.content.BroadcastReceiver screen = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { setKeyFilter(Intent.ACTION_SCREEN_ON.equals(i.getAction())); }
    };

    void setKeyFilter(boolean on) {
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            int f = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.flags = on ? (info.flags | f) : (info.flags & ~f);
            setServiceInfo(info);
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        android.content.IntentFilter fl = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        fl.addAction(Intent.ACTION_SCREEN_ON);
        try { registerReceiver(screen, fl); } catch (Throwable ignored) { }
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(screen); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && keyLog(this))
            Log.i("ZenKeys", "keyCode=" + e.getKeyCode() + " scanCode=" + e.getScanCode() + " device=" + e.getDeviceId());
        String pkg = appForKey(this, e.getKeyCode());
        if (pkg == null) return false;
        Intent i = launchIntent(getPackageManager(), pkg);
        if (i == null) return false;                       // appli absente : la touche suit son chemin normal
        if (e.getAction() == KeyEvent.ACTION_UP) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Exception ignored) { }
        }
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
