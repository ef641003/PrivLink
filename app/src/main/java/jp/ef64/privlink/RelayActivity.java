package jp.ef64.privlink;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受け取ったインテント（VIEW / SEND / PROCESS_TEXT）から URL を取り出し、
 * 同じプロファイル内の転送先ブラウザへ「パッケージ指定の明示的インテント」で投げ直す。
 *
 * ポイント: setPackage() で宛先を固定するので、システムの「http(s) はどのアプリで開くか」
 * という解決処理（＝メイン側ブラウザへ転送してしまう処理）を通らない。
 */
public class RelayActivity extends Activity {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"'）」】]+");
    private static final Pattern BARE_DOMAIN = Pattern.compile("^[\\w.-]+\\.[A-Za-z]{2,}(?:[/?#].*)?$");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            handle(getIntent());
        } finally {
            finish();
        }
    }

    private void handle(Intent intent) {
        Uri uri = extractUri(intent);
        if (uri == null) {
            toast(getString(R.string.no_url));
            return;
        }

        String target = Prefs.resolveTarget(this);
        if (target != null) {
            if (openWith(uri, target)) return;
            toast(getString(R.string.target_failed, target));
        }
        // 転送先が無い／起動できない場合は、このプロファイル内の候補から選ばせる
        showChooser(uri);
    }

    /** VIEW ならデータ URI、SEND / PROCESS_TEXT なら本文から最初の URL を拾う。 */
    private Uri extractUri(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();

        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            return intent.getData();
        }

        CharSequence text = null;
        if (Intent.ACTION_SEND.equals(action)) {
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        }
        if (text == null) return null;

        Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) return Uri.parse(m.group());

        String t = text.toString().trim();
        if (BARE_DOMAIN.matcher(t).matches()) return Uri.parse("https://" + t);
        return null;
    }

    private boolean openWith(Uri uri, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, uri)
                .setPackage(pkg)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private void showChooser(Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE);
        Intent chooser = Intent.createChooser(view, getString(R.string.choose_browser));
        // 自分自身を候補から外す（自分がデフォルトブラウザのときの無限ループ防止）
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                new ComponentName[]{Prefs.relayComponent(this)});
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(chooser);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.no_browser));
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
