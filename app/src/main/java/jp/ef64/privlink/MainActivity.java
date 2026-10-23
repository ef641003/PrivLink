package jp.ef64.privlink;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 設定画面: 転送先ブラウザの選択、デフォルトブラウザ申請（試験用）、動作テスト。 */
public class MainActivity extends Activity {

    private static final int REQ_ROLE = 1;
    private static final int REQ_TREE = 2;

    private TextView status;
    private RadioGroup group;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        group = findViewById(R.id.browser_group);

        Button requestRole = findViewById(R.id.btn_request_role);
        requestRole.setOnClickListener(v -> requestBrowserRole());

        Button test = findViewById(R.id.btn_test);
        test.setOnClickListener(v -> {
            Intent i = new Intent(this, RelayActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("https://example.com/"));
            startActivity(i);
        });

        findViewById(R.id.btn_open_browser).setOnClickListener(v ->
                startActivity(new Intent(this, BrowserActivity.class)));

        findViewById(R.id.btn_pick_dir).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            try {
                startActivityForResult(i, REQ_TREE);
            } catch (Exception e) {
                Toast.makeText(this, R.string.dl_no_picker, Toast.LENGTH_LONG).show();
            }
        });

        findViewById(R.id.btn_clear_dir).setOnClickListener(v -> {
            Prefs.setDownloadTree(this, null);
            refresh();
        });

        Button clear = findViewById(R.id.btn_clear);
        clear.setOnClickListener(v -> {
            Prefs.save(this, null);
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> browsers = Prefs.browsers(this);
        String current = Prefs.resolveTarget(this);
        String saved = Prefs.getSaved(this);

        group.setOnCheckedChangeListener(null);
        group.removeAllViews();
        RadioButton self = new RadioButton(this);
        self.setText(getString(R.string.builtin_browser) + "\n" + getPackageName());
        self.setTag(getPackageName());
        self.setChecked(getPackageName().equals(current));
        group.addView(self);
        for (ResolveInfo ri : browsers) {
            String pkg = ri.activityInfo.packageName;
            RadioButton rb = new RadioButton(this);
            rb.setText(ri.loadLabel(pm) + "\n" + pkg);
            rb.setTag(pkg);
            rb.setChecked(pkg.equals(current));
            group.addView(rb);
        }
        group.setOnCheckedChangeListener((g, id) -> {
            RadioButton rb = findViewById(id);
            if (rb != null && rb.getTag() != null) {
                Prefs.save(this, rb.getTag().toString());
                status.setText(statusText(rb.getTag().toString(), rb.getTag().toString()));
            }
        });

        status.setText(statusText(current, saved));

        TextView dir = findViewById(R.id.download_dir);
        String tree = Prefs.getDownloadTree(this);
        dir.setText(tree == null
                ? getString(R.string.dl_dir_none)
                : getString(R.string.dl_dir_set, DocumentsContract.getTreeDocumentId(Uri.parse(tree))));
    }

    private String statusText(String current, String saved) {
        StringBuilder sb = new StringBuilder();
        if (current == null) {
            sb.append(getString(R.string.status_none));
        } else if (saved == null) {
            sb.append(getString(R.string.status_auto, current));
        } else {
            sb.append(getString(R.string.status_saved, current));
        }
        sb.append('\n');
        RoleManager rm = getSystemService(RoleManager.class);
        boolean held = rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER)
                && rm.isRoleHeld(RoleManager.ROLE_BROWSER);
        sb.append(getString(held ? R.string.role_held : R.string.role_not_held));
        return sb.toString();
    }

    private void requestBrowserRole() {
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
            Toast.makeText(this, R.string.role_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        if (rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
            Toast.makeText(this, R.string.role_held, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), REQ_ROLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                Prefs.setDownloadTree(this, tree.toString());
            } catch (SecurityException e) {
                Toast.makeText(this, R.string.dl_perm_failed, Toast.LENGTH_LONG).show();
            }
            refresh();
        }
        if (requestCode == REQ_ROLE) {
            Toast.makeText(this,
                    resultCode == RESULT_OK ? R.string.role_granted : R.string.role_denied,
                    Toast.LENGTH_SHORT).show();
            refresh();
        }
    }
}
