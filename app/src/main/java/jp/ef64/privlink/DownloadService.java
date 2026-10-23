package jp.ef64.privlink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.webkit.URLUtil;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ダウンロードを実行するフォアグラウンドサービス。
 * 保存先は 2 通り:
 *  - EXTRA_DEST_DOC : 保存先ファイルの URI（「毎回確認」モードで、ユーザーが選んだもの）
 *  - EXTRA_DEST_TREE: 保存先フォルダのツリー URI（固定フォルダモード）。ファイル名はレスポンスから決める
 * どちらも SAF（Storage Access Framework）経由なので、プライバシーモード内のフォルダにも書ける。
 */
public class DownloadService extends Service {

    static final String EXTRA_URL = "url";
    static final String EXTRA_UA = "ua";
    static final String EXTRA_COOKIE = "cookie";
    static final String EXTRA_REFERER = "referer";
    static final String EXTRA_MIME = "mime";
    static final String EXTRA_CD = "cd";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_DEST_DOC = "dest_doc";
    static final String EXTRA_DEST_TREE = "dest_tree";

    private static final String CHANNEL = "downloads";
    private static final int FG_ID = 1;

    private final AtomicInteger pending = new AtomicInteger();
    private int nextId = 100;
    private ExecutorService exec;
    private NotificationManager nm;
    private Handler main;

    @Override
    public void onCreate() {
        super.onCreate();
        exec = Executors.newSingleThreadExecutor();
        nm = getSystemService(NotificationManager.class);
        main = new Handler(Looper.getMainLooper());
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getStringExtra(EXTRA_URL) == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification fg = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.dl_running))
                .setOngoing(true)
                .build();
        startForeground(FG_ID, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        final int id = nextId++;
        final Intent job = new Intent(intent);
        pending.incrementAndGet();
        exec.execute(() -> {
            try {
                download(job, id);
            } finally {
                pending.decrementAndGet();
                main.post(() -> {
                    if (pending.get() == 0) {
                        stopForeground(Service.STOP_FOREGROUND_REMOVE);
                        stopSelf();
                    }
                });
            }
        });
        return START_NOT_STICKY;
    }

    private void download(Intent in, int id) {
        String urlStr = in.getStringExtra(EXTRA_URL);
        String ua = in.getStringExtra(EXTRA_UA);
        String cookie = in.getStringExtra(EXTRA_COOKIE);
        String referer = in.getStringExtra(EXTRA_REFERER);
        String mimeHint = in.getStringExtra(EXTRA_MIME);
        String cdHint = in.getStringExtra(EXTRA_CD);
        String nameHint = in.getStringExtra(EXTRA_NAME);
        String destDoc = in.getStringExtra(EXTRA_DEST_DOC);
        String destTree = in.getStringExtra(EXTRA_DEST_TREE);

        ContentResolver cr = getContentResolver();
        HttpURLConnection conn = null;
        Uri dest = null;
        String name = nameHint != null ? nameHint : "download";
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            if (ua != null) conn.setRequestProperty("User-Agent", ua);
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            if (referer != null) conn.setRequestProperty("Referer", referer);

            int code = conn.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);

            String cd = conn.getHeaderField("Content-Disposition");
            if (cd == null) cd = cdHint;
            String mime = conn.getContentType();
            if (mime == null) mime = mimeHint;
            if (mime != null && mime.contains(";")) mime = mime.substring(0, mime.indexOf(';')).trim();
            if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
            long len = conn.getContentLengthLong();

            if (destDoc != null) {
                dest = Uri.parse(destDoc);
            } else if (destTree != null) {
                name = URLUtil.guessFileName(conn.getURL().toString(), cd, mime);
                Uri tree = Uri.parse(destTree);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                dest = DocumentsContract.createDocument(cr, parent, mime, name);
                if (dest == null) throw new IOException(getString(R.string.dl_cannot_create));
            } else {
                throw new IOException(getString(R.string.dl_no_dest));
            }

            long done = 0;
            long lastNotify = 0;
            try (InputStream is = conn.getInputStream();
                 OutputStream os = cr.openOutputStream(dest)) {
                if (os == null) throw new IOException(getString(R.string.dl_cannot_create));
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) != -1) {
                    os.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 500) {
                        lastNotify = now;
                        progress(id, name, done, len);
                    }
                }
            }
            finished(id, name, dest);
        } catch (Exception e) {
            if (dest != null && destDoc == null) {
                // 固定フォルダモードで作った中途半端なファイルを消す
                try {
                    DocumentsContract.deleteDocument(cr, dest);
                } catch (Exception ignore) {
                    // 消せなくても致命的ではない
                }
            }
            failed(id, name, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void progress(int id, String name, long done, long total) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(name)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (total > 0) {
            b.setProgress(100, (int) (done * 100 / total), false);
            b.setContentText((done * 100 / total) + "%");
        } else {
            b.setProgress(0, 0, true);
            b.setContentText((done / 1024) + " KB");
        }
        nm.notify(id, b.build());
    }

    private void finished(int id, String name, Uri dest) {
        Intent open = new Intent(Intent.ACTION_VIEW)
                .setData(dest)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, id, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(id, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(name)
                .setContentText(getString(R.string.dl_done))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
        toast(getString(R.string.dl_done_toast, name));
    }

    private void failed(int id, String name, Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        nm.notify(id, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(name)
                .setContentText(getString(R.string.dl_failed, msg))
                .setAutoCancel(true)
                .build());
        toast(getString(R.string.dl_failed_toast, name, msg));
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        exec.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
