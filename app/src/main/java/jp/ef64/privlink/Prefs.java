package jp.ef64.privlink;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** 転送先ブラウザの保存と、同一プロファイル内のブラウザ列挙。 */
final class Prefs {
    private static final String FILE = "privlink";
    private static final String KEY_TARGET = "target_package";

    /** 保存がない場合に優先的に使うパッケージ（上から順に、入っているものを採用） */
    static final String[] CANDIDATES = {
            "com.chrome.dev",      // Chrome Dev
            "com.chrome.beta",     // Chrome Beta
            "com.chrome.canary",   // Chrome Canary
            "com.android.chrome",  // Chrome（同じプロファイル内にある場合のみ見える）
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.kiwibrowser.browser",
    };

    private Prefs() {}

    static String getSaved(Context c) {
        return prefs(c).getString(KEY_TARGET, null);
    }

    static void save(Context c, String pkg) {
        prefs(c).edit().putString(KEY_TARGET, pkg).apply();
    }

    /** 保存済みで、かつ今も入っているパッケージ。なければ候補リストから自動選択。どれも無ければ null。 */
    static String resolveTarget(Context c) {
        String saved = getSaved(c);
        if (saved != null && isInstalled(c, saved)) return saved;
        for (String p : CANDIDATES) {
            if (isInstalled(c, p)) return p;
        }
        return null;
    }

    static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** このプロファイル内で http(s) を開けるアクティビティ（自分自身は除外）。 */
    static List<ResolveInfo> browsers(Context c) {
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        List<ResolveInfo> all = c.getPackageManager()
                .queryIntentActivities(probe, PackageManager.MATCH_ALL);
        List<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo ri : all) {
            if (ri.activityInfo == null) continue;
            if (c.getPackageName().equals(ri.activityInfo.packageName)) continue;
            out.add(ri);
        }
        return out;
    }

    static ComponentName relayComponent(Context c) {
        return new ComponentName(c, RelayActivity.class);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
