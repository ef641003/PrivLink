package jp.ef64.privlink;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 設定画面: 転送先ブラウザの選択、デフォルトブラウザ申請（試験用）、動作テスト。 */
public class MainActivity extends Activity {

    private static final int REQ_ROLE = 1;

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
        if (requestCode == REQ_ROLE) {
            Toast.makeText(this,
                    resultCode == RESULT_OK ? R.string.role_granted : R.string.role_denied,
                    Toast.LENGTH_SHORT).show();
            refresh();
        }
    }
}
