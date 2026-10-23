package jp.ef64.privlink;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.List;

/**
 * 最小限の内蔵ブラウザ（WebView）。
 * 目的: ダウンロード先を、このアプリが入っているプロファイル（＝プライバシーモード内）の
 *       フォルダに指定できるようにすること。保存先は SAF のフォルダ選択で決め、
 *       未設定のときはダウンロードのたびに保存先を選ばせる。
 */
public class BrowserActivity extends Activity {

    private static final int REQ_PICK_TREE = 10;
    private static final int REQ_CREATE_DOC = 11;
    private static final int REQ_FILE = 12;
    private static final int REQ_NOTIF = 13;

    private static final String HOME = "https://www.google.com/";

    private WebView web;
    private EditText urlBar;
    private ProgressBar progress;
    private Button btnBack;
    private Button btnForward;

    private ValueCallback<Uri[]> fileCallback;
    private Intent pendingJob;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // targetSdk 35 は画面端まで描画されるので、システムバー分の余白を自前で取る
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        btnBack = smallButton("◀");
        btnBack.setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        btnForward = smallButton("▶");
        btnForward.setOnClickListener(v -> { if (web.canGoForward()) web.goForward(); });
        Button btnReload = smallButton("⟳");
        btnReload.setOnClickListener(v -> web.reload());

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setHint(R.string.url_hint);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setOnEditorActionListener((tv, actionId, ev) -> {
            load(urlBar.getText().toString());
            urlBar.clearFocus();
            return true;
        });

        Button btnMenu = smallButton("⋮");
        btnMenu.setOnClickListener(this::showMenu);

        bar.addView(btnBack);
        bar.addView(btnForward);
        bar.addView(btnReload);
        bar.addView(urlBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(btnMenu);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        setupWebView();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        handleIntent(getIntent());
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 「; wv」が付いた WebView 判定の UA を、通常のモバイル Chrome 風に直す
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                // intent: や market: などは、プライバシーモードの外へ漏れかねないので開かない
                toast(getString(R.string.blocked_scheme, String.valueOf(scheme)));
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!urlBar.hasFocus()) urlBar.setText(url);
                updateNav();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!urlBar.hasFocus()) urlBar.setText(url);
                updateNav();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                startDownload(url, userAgent, contentDisposition, mimeType));
    }

    // ---- ページ読み込み ----

    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null) {
            load(intent.getData().toString());
        } else if (web.getUrl() == null) {
            load(HOME);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void load(String input) {
        String t = input == null ? "" : input.trim();
        if (t.isEmpty()) return;
        String url;
        if (t.startsWith("http://") || t.startsWith("https://")) {
            url = t;
        } else if (!t.contains(" ") && (t.contains(".") || t.startsWith("localhost"))) {
            url = "https://" + t;
        } else {
            url = "https://www.google.com/search?q=" + Uri.encode(t);
        }
        web.loadUrl(url);
    }

    private void updateNav() {
        btnBack.setEnabled(web.canGoBack());
        btnForward.setEnabled(web.canGoForward());
    }

    // ---- ダウンロード ----

    private void startDownload(String url, String userAgent, String contentDisposition,
                               String mimeType) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast(getString(R.string.dl_unsupported));
            return;
        }
        Intent job = new Intent(this, DownloadService.class)
                .putExtra(DownloadService.EXTRA_URL, url)
                .putExtra(DownloadService.EXTRA_UA, userAgent)
                .putExtra(DownloadService.EXTRA_COOKIE, CookieManager.getInstance().getCookie(url))
                .putExtra(DownloadService.EXTRA_REFERER, web.getUrl())
                .putExtra(DownloadService.EXTRA_MIME, mimeType)
                .putExtra(DownloadService.EXTRA_CD, contentDisposition);

        String tree = Prefs.getDownloadTree(this);
        if (tree != null && hasTreePermission(Uri.parse(tree))) {
            job.putExtra(DownloadService.EXTRA_DEST_TREE, tree);
            startForegroundService(job);
            toast(getString(R.string.dl_started, describeTree(tree)));
            return;
        }
        if (tree != null) toast(getString(R.string.dl_perm_lost));

        // 保存先フォルダ未設定（または権限を失った）: ダウンロードごとに保存先を選ばせる
        String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        job.putExtra(DownloadService.EXTRA_NAME, name);
        pendingJob = job;
        String type = (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType;
        Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type)
                .putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(pick, REQ_CREATE_DOC);
        } catch (ActivityNotFoundException e) {
            pendingJob = null;
            toast(getString(R.string.dl_no_picker));
        }
    }

    private boolean hasTreePermission(Uri tree) {
        List<UriPermission> perms = getContentResolver().getPersistedUriPermissions();
        for (UriPermission p : perms) {
            if (p.getUri().equals(tree) && p.isWritePermission()) return true;
        }
        return false;
    }

    private String describeTree(String tree) {
        try {
            return DocumentsContract.getTreeDocumentId(Uri.parse(tree));
        } catch (Exception e) {
            return tree;
        }
    }

    // ---- メニュー ----

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String tree = Prefs.getDownloadTree(this);
        String current = tree == null ? getString(R.string.dl_ask_each_time) : describeTree(tree);
        menu.getMenu().add(0, 1, 0, getString(R.string.menu_pick_folder, current));
        menu.getMenu().add(0, 2, 1, R.string.menu_ask_each_time);
        menu.getMenu().add(0, 3, 2, R.string.menu_clear_data);
        menu.getMenu().add(0, 4, 3, R.string.menu_home);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    pickFolder();
                    return true;
                case 2:
                    Prefs.setDownloadTree(this, null);
                    toast(getString(R.string.dl_ask_each_time_set));
                    return true;
                case 3:
                    clearBrowsingData();
                    return true;
                case 4:
                    load(HOME);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK_TREE);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.dl_no_picker));
        }
    }

    private void clearBrowsingData() {
        web.clearCache(true);
        web.clearHistory();
        web.clearFormData();
        CookieManager.getInstance().removeAllCookies(null);
        WebStorage.getInstance().deleteAllData();
        toast(getString(R.string.cleared));
    }

    // ---- 結果の受け取り ----

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQ_PICK_TREE:
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    Uri tree = data.getData();
                    try {
                        getContentResolver().takePersistableUriPermission(tree,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (SecurityException e) {
                        toast(getString(R.string.dl_perm_failed));
                        return;
                    }
                    Prefs.setDownloadTree(this, tree.toString());
                    toast(getString(R.string.dl_folder_set, describeTree(tree.toString())));
                }
                break;

            case REQ_CREATE_DOC:
                if (pendingJob != null && resultCode == RESULT_OK
                        && data != null && data.getData() != null) {
                    Uri dest = data.getData();
                    try {
                        getContentResolver().takePersistableUriPermission(dest,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (Exception ignore) {
                        // 永続化できなくても、このダウンロード中は書ける
                    }
                    pendingJob.putExtra(DownloadService.EXTRA_DEST_DOC, dest.toString());
                    startForegroundService(pendingJob);
                } else if (pendingJob != null) {
                    toast(getString(R.string.dl_cancelled));
                }
                pendingJob = null;
                break;

            case REQ_FILE:
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(
                            WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                    fileCallback = null;
                }
                break;

            default:
                break;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    // ---- 小物 ----

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
